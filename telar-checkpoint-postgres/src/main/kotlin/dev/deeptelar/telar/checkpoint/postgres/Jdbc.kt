package dev.deeptelar.telar.checkpoint.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Runs [block] in one transaction on a connection of [dataSource], on `Dispatchers.IO` because JDBC
 * blocks. The transaction is committed when [block] returns and rolled back when it throws. The
 * connection goes back to the pool as it came, with auto-commit on.
 */
internal suspend fun <Result> DataSource.transaction(block: Connection.() -> Result): Result =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = autoCommit
            }
        }
    }

/** Runs [sql] with [arguments] in their order, and returns how many rows it changed. */
internal fun Connection.update(sql: String, vararg arguments: Any): Int = prepareStatement(sql).use { it.bind(arguments).executeUpdate() }

/** Returns the rows of [sql] with [arguments], each read with [row]. */
internal fun <Value> Connection.query(sql: String, vararg arguments: Any, row: ResultSet.() -> Value): List<Value> =
    prepareStatement(sql).use { statement ->
        statement.bind(arguments).executeQuery().use { result ->
            buildList { while (result.next()) add(result.row()) }
        }
    }

/**
 * Waits until no other transaction holds the lock of [key] in [table], and holds it until this
 * transaction ends. The saves of one thread take turns this way, also from several instances of an
 * app. A lock is two numbers in PostgreSQL, so different keys may share one: they only wait for
 * each other a little.
 */
internal fun Connection.lock(table: String, key: String) {
    query("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))", table, key) { }
}

private fun PreparedStatement.bind(arguments: Array<out Any>): PreparedStatement =
    apply {
        arguments.forEachIndexed { index, argument ->
            when (argument) {
                is String -> setString(index + 1, argument)
                is Int -> setInt(index + 1, argument)
                is Long -> setLong(index + 1, argument)
                is OffsetDateTime -> setObject(index + 1, argument)
                else -> error("No binding for ${argument::class}")
            }
        }
    }

/** A table name: letters, digits and `_`, not starting with a digit, with a schema in front or not. */
internal val TABLE_NAME = Regex("([A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z_][A-Za-z0-9_]*")
