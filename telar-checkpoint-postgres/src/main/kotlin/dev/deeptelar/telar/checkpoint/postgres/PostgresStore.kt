package dev.deeptelar.telar.checkpoint.postgres

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.StoreItem
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * A persistent [Store] that keeps its items in a table of a PostgreSQL database, through the
 * [DataSource] of the app, so that every instance of a server remembers what an agent learned:
 *
 * ```kotlin
 * val config = GraphConfig(
 *     threadId = "chat-7",
 *     checkpointer = PostgresCheckpointer(dataSource, KotlinxStateSerializer<AgentState>()),
 *     store = PostgresStore(dataSource).within("users", userId),
 * )
 * ```
 *
 * The app creates the data source, with the JDBC driver of PostgreSQL and the pool it likes, and
 * closes it. The store creates its [table] when it first needs it, with `CREATE TABLE IF NOT EXISTS`,
 * unless `createTable = false`, for an app whose migrations create it. The table has one row for
 * each item:
 *
 * ```sql
 * CREATE TABLE telar_store (
 *     namespace TEXT COLLATE "C" NOT NULL, -- each part followed by '/', with '%' and '/' in a part written as %25 and %2F
 *     key TEXT NOT NULL,
 *     value TEXT NOT NULL,
 *     updated_at TIMESTAMPTZ NOT NULL,
 *     PRIMARY KEY (namespace, key)
 * )
 * ```
 *
 * The namespace compares byte by byte (`COLLATE "C"`), whatever the locale of the database, so the
 * primary key finds the items under a namespace.
 *
 * Each write is one statement, so a crash leaves the items as they were or with the write done. Two
 * writes of one instance never get the same time, so [list] keeps their order also with a clock that
 * is not precise or that went back. Writes from several instances are ordered by their clocks. One
 * instance of the class is safe to share between coroutines. JDBC blocks, so every call runs on
 * `Dispatchers.IO`.
 *
 * @param dataSource the database. It has to be PostgreSQL, version 12 or later.
 * @param table the name of the table, letters, digits and `_`, not starting with a digit, with a
 * schema in front or not.
 * @param clock gives [StoreItem.updatedAt], to the microsecond, the precision of PostgreSQL.
 * @param createTable whether the store creates [table] when it does not exist.
 * @throws GraphValidationException if [table] is not a plain name.
 */
public class PostgresStore(
    private val dataSource: DataSource,
    private val table: String = DEFAULT_TABLE,
    private val clock: Clock = Clock.System,
    private val createTable: Boolean = true,
) : Store {
    @Volatile
    private var ready = false

    /** The time of the last write of this instance, in microseconds since 1970. */
    private val lastWrite = AtomicLong(Long.MIN_VALUE)

    init {
        if (!table.matches(TABLE_NAME)) {
            throw GraphValidationException(
                "table must be letters, digits and '_', not starting with a digit, with a schema in front or not, was '$table'.",
            )
        }
    }

    override suspend fun get(namespace: List<String>, key: String): StoreItem? {
        Store.checkAddress(namespace, key)
        return database {
            query("SELECT namespace, key, value, updated_at FROM $table WHERE namespace = ? AND key = ?", encodeNamespace(namespace), key) {
                item()
            }.singleOrNull()
        }
    }

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        Store.checkAddress(namespace, key)
        database {
            update(
                "INSERT INTO $table (namespace, key, value, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (namespace, key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at",
                encodeNamespace(namespace),
                key,
                value,
                nextTime(),
            )
        }
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        Store.checkAddress(namespace, key)
        database { update("DELETE FROM $table WHERE namespace = ? AND key = ?", encodeNamespace(namespace), key) }
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> {
        Store.checkNamespace(namespace)
        Store.checkLimit(limit)
        val columns = "SELECT namespace, key, value, updated_at FROM $table"
        val order = "ORDER BY updated_at DESC, namespace, key LIMIT ?"
        return database {
            if (namespace.isEmpty()) {
                query("$columns $order", limit) { item() }
            } else {
                // Every encoded namespace ends with '/', and '0' comes right after '/': the range holds exactly
                // the namespaces that start with this one, and the primary key finds them.
                val prefix = encodeNamespace(namespace)
                query("$columns WHERE namespace >= ? AND namespace < ? $order", prefix, prefix.dropLast(1) + "0", limit) { item() }
            }
        }
    }

    /** Runs [block] in one transaction, with the table in place. */
    private suspend fun <Result> database(block: Connection.() -> Result): Result {
        if (!ready) {
            dataSource.transaction {
                if (createTable) {
                    lock(table, CREATE_TABLE_LOCK)
                    update(
                        "CREATE TABLE IF NOT EXISTS $table (" +
                            "namespace TEXT COLLATE \"C\" NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, " +
                            "updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (namespace, key))",
                    )
                }
                // A store starts after the last write it finds, so that its first write comes after it in a list.
                query("SELECT MAX(updated_at) FROM $table") { getObject(1, OffsetDateTime::class.java) }
                    .singleOrNull()
                    ?.let { last -> lastWrite.accumulateAndGet(last.toInstant().toKotlinInstant().micros(), ::maxOf) }
            }
            ready = true
        }
        return dataSource.transaction(block)
    }

    /** The time of a write: now, or just after the last write when the clock has not moved past it. */
    private fun nextTime(): OffsetDateTime {
        val now = clock.now().micros()
        val micros = lastWrite.updateAndGet { last -> if (now <= last) last + 1 else now }
        val instant = Instant.fromEpochSeconds(micros.floorDiv(MICROS_PER_SECOND), micros.mod(MICROS_PER_SECOND) * NANOS_PER_MICRO)
        return OffsetDateTime.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
    }

    private fun ResultSet.item(): StoreItem {
        // The columns are NOT NULL.
        val encoded = getString(1)
        val namespace =
            decodeNamespace(encoded)
                ?: throw StoreCorruptedException(listOf(encoded), null, "the table $table has a namespace it did not write")
        val updatedAt = getObject(4, OffsetDateTime::class.java).toInstant().toKotlinInstant()
        return StoreItem(namespace, getString(2), getString(3), updatedAt)
    }

    private companion object {
        const val DEFAULT_TABLE = "telar_store"
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L

        fun Instant.micros(): Long = epochSeconds * MICROS_PER_SECOND + nanosecondsOfSecond / NANOS_PER_MICRO
    }
}

/** Writes [namespace] as one string: each part followed by `/`, with `%` and `/` in a part escaped. */
internal fun encodeNamespace(namespace: List<String>): String =
    namespace.joinToString("") { part -> part.replace("%", "%25").replace("/", "%2F") + "/" }

/** Reads back what [encodeNamespace] wrote, or returns `null` for a string it does not write. */
internal fun decodeNamespace(encoded: String): List<String>? {
    if (encoded.isEmpty()) return emptyList()
    if (!encoded.endsWith("/")) return null
    return encoded
        .dropLast(1)
        .split("/")
        .map { it.replace("%2F", "/").replace("%25", "%") }
        .takeIf { encodeNamespace(it) == encoded }
}
