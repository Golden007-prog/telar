package dev.deeptelar.telar.checkpoint.postgres

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.serialization.CheckpointCodec
import java.sql.Connection
import javax.sql.DataSource

/**
 * A persistent [Checkpointer] that saves the checkpoints of each thread in a table of a PostgreSQL
 * database, through the [DataSource] of the app. Servers with several instances share it, so a run
 * that paused on one instance is resumed on another:
 *
 * ```kotlin
 * val dataSource = HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:postgresql://db/app" })
 * val config = GraphConfig(
 *     threadId = "order-42",
 *     checkpointer = PostgresCheckpointer(dataSource, KotlinxStateSerializer<Order>()),
 *     interruptBefore = setOf("pay"),
 * )
 * ```
 *
 * The app creates the data source, with the JDBC driver of PostgreSQL (`org.postgresql:postgresql`)
 * and the pool it likes, and closes it. Each call takes a connection for one transaction and gives it
 * back.
 *
 * **The table.** The checkpointer creates its [table] when it first needs it, with
 * `CREATE TABLE IF NOT EXISTS`, so the database may be the one the app keeps its own data in. An app
 * that creates its tables with migrations, such as Flyway or Liquibase, passes `createTable = false`
 * and adds this table itself. It has one row for each step of a thread:
 *
 * ```sql
 * CREATE TABLE telar_checkpoints (
 *     thread_id TEXT NOT NULL,
 *     step INTEGER NOT NULL,
 *     checkpoint TEXT NOT NULL, -- the checkpoint in the format of CheckpointCodec
 *     PRIMARY KEY (thread_id, step)
 * )
 * ```
 *
 * **A save** is one transaction: it replaces the checkpoints of the thread with the same step or a
 * later one, adds the new one, and removes the oldest beyond [maxHistory]. A crash in the middle
 * leaves the checkpoints as they were.
 *
 * **Two instances that save the same thread at the same time** take turns: a save first takes a
 * lock of its thread (`pg_advisory_xact_lock`), held until its transaction ends, so the rows of a
 * thread are always those of one save after another, and the last save is the one that is loaded.
 * Which of two runs of the same thread saves last is not decided by the checkpointer, so an app
 * still runs a thread in one place at a time, as with every checkpointer. One instance of the class is
 * safe to share between coroutines.
 *
 * JDBC blocks, so every call runs on `Dispatchers.IO`. A failure of the database, such as a
 * `java.sql.SQLException`, is thrown as it is.
 *
 * @param dataSource the database. It has to be PostgreSQL, version 12 or later.
 * @param serializer converts the graph state to and from a string.
 * @param table the name of the table, letters, digits and `_`, not starting with a digit, with a
 * schema in front or not, such as `agents.telar_checkpoints`.
 * @param maxHistory how many checkpoints a thread keeps, counted from the latest, for
 * `CompiledGraph.history` and `fork`. The default keeps all of them; `1` keeps only the latest.
 * @param createTable whether the checkpointer creates [table] when it does not exist. `false` for an
 * app whose migrations create it.
 * @throws GraphValidationException if [table] is not a plain name or [maxHistory] is not positive.
 */
public class PostgresCheckpointer<State>(
    private val dataSource: DataSource,
    serializer: StateSerializer<State>,
    private val table: String = DEFAULT_TABLE,
    private val maxHistory: Int = Int.MAX_VALUE,
    private val createTable: Boolean = true,
) : Checkpointer<State> {
    private val codec = CheckpointCodec(serializer)

    @Volatile
    private var tableReady = !createTable

    init {
        if (!table.matches(TABLE_NAME)) {
            throw GraphValidationException(
                "table must be letters, digits and '_', not starting with a digit, with a schema in front or not, was '$table'.",
            )
        }
        if (maxHistory <= 0) throw GraphValidationException("maxHistory must be positive, was $maxHistory.")
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        val encoded = codec.encode(checkpoint)
        database {
            lock(table, threadId)
            update("DELETE FROM $table WHERE thread_id = ? AND step >= ?", threadId, checkpoint.step)
            update("INSERT INTO $table (thread_id, step, checkpoint) VALUES (?, ?, ?)", threadId, checkpoint.step, encoded)
            if (maxHistory != Int.MAX_VALUE) {
                // The oldest step to keep, if the thread has more than maxHistory checkpoints.
                val oldestKept =
                    query("SELECT step FROM $table WHERE thread_id = ? ORDER BY step DESC LIMIT 1 OFFSET ?", threadId, maxHistory - 1) {
                        getInt(1)
                    }.singleOrNull()
                if (oldestKept != null) update("DELETE FROM $table WHERE thread_id = ? AND step < ?", threadId, oldestKept)
            }
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? =
        database { checkpoints("SELECT checkpoint FROM $table WHERE thread_id = ? ORDER BY step DESC LIMIT 1", threadId) }
            .singleOrNull()
            ?.let { codec.decode(threadId, it) }

    override suspend fun history(threadId: String): List<Checkpoint<State>> =
        database { checkpoints("SELECT checkpoint FROM $table WHERE thread_id = ? ORDER BY step", threadId) }
            .map { codec.decode(threadId, it) }

    override suspend fun delete(threadId: String) {
        database {
            lock(table, threadId)
            update("DELETE FROM $table WHERE thread_id = ?", threadId)
        }
    }

    /** Runs [block] in one transaction, with the table in place. */
    private suspend fun <Result> database(block: Connection.() -> Result): Result {
        if (!tableReady) {
            dataSource.transaction {
                // Two instances that start together would both create the table, and one of them fail.
                lock(table, CREATE_TABLE_LOCK)
                update(
                    "CREATE TABLE IF NOT EXISTS $table (" +
                        "thread_id TEXT NOT NULL, step INTEGER NOT NULL, checkpoint TEXT NOT NULL, " +
                        "PRIMARY KEY (thread_id, step))",
                )
            }
            tableReady = true
        }
        return dataSource.transaction(block)
    }

    private fun Connection.checkpoints(sql: String, threadId: String): List<String> = query(sql, threadId) { getString(1) }

    private companion object {
        const val DEFAULT_TABLE = "telar_checkpoints"
    }
}

/**
 * The key of the lock that the creation of a table takes. A thread with this id would only wait for
 * a creation, which happens once.
 */
internal const val CREATE_TABLE_LOCK = "telar: create table"
