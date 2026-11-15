package dev.deeptelar.telar.checkpoint.postgres

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import dev.deeptelar.telar.within
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock that stays at [now] until a test moves it. */
private class FixedClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

class PostgresStoreTest {
    private val database = Database.fresh()

    @Test
    fun `keeps replaces and deletes a value, also for another instance`() =
        runTest {
            val store = PostgresStore(database)
            store.put(listOf("users", "ana"), "diet", "vegetarian")
            store.put(listOf("users", "ana"), "diet", "vegan")
            store.put(listOf("users", "ana"), "city", "Girona")
            store.delete(listOf("users", "ana"), "city")

            val other = PostgresStore(database)

            assertEquals("vegan", other.get(listOf("users", "ana"), "diet")?.value)
            assertNull(other.get(listOf("users", "ana"), "city"))
            assertNull(other.get(listOf("users", "joan"), "diet"))
        }

    @Test
    fun `a list has the namespaces under it, the last changed first`() =
        runTest {
            val store = PostgresStore(database)
            store.put(listOf("users", "ana"), "diet", "vegan")
            store.put(listOf("users", "ana", "trips"), "last", "Lisbon")
            store.put(listOf("users", "anabel"), "diet", "none")
            store.put(listOf("users", "joan"), "diet", "fish")
            store.put(listOf("teams"), "name", "kitchen")

            assertEquals(listOf("last", "diet"), store.list(listOf("users", "ana")).map { it.key })
            assertEquals(listOf("fish", "none", "Lisbon", "vegan"), store.list(listOf("users")).map { it.value })
            assertEquals(5, store.list(emptyList()).size)
            assertEquals(listOf("kitchen", "fish"), store.list(emptyList(), limit = 2).map { it.value })
        }

    @Test
    fun `any character in a namespace keeps its items apart, whatever the locale`() =
        runTest {
            val store = PostgresStore(database)
            val namespaces = listOf(listOf("a/b"), listOf("a", "b"), listOf("a%2Fb"), listOf("A"), listOf("é"), listOf("a b"), listOf("a0"))
            namespaces.forEach { store.put(it, "k", it.joinToString("|")) }

            namespaces.forEach { namespace ->
                assertEquals(listOf(namespace.joinToString("|")), store.list(namespace).map { it.value })
                assertEquals(namespace, store.get(namespace, "k")?.namespace)
            }
        }

    @Test
    fun `search finds the items with the words of a query`() =
        runTest {
            val store = PostgresStore(database).within("users", "ana")
            store.put(emptyList(), "diet", "Ana is vegan and eats no fish")
            store.put(emptyList(), "city", "Ana lives in Girona")

            assertEquals(listOf("diet"), store.search(emptyList(), "fish").map { it.key })
        }

    @Test
    fun `the checkpointer and the store share one database`() =
        runTest {
            val checkpointer = PostgresCheckpointer(database, KotlinxStateSerializer<Order>())
            val store = PostgresStore(database)

            checkpointer.save("t", dev.deeptelar.telar.Checkpoint(Order("pizza"), listOf("pay"), 1))
            store.put(listOf("users"), "ana", "likes pizza")

            assertEquals("pizza", checkpointer.load("t")?.state?.item)
            assertEquals("likes pizza", store.get(listOf("users"), "ana")?.value)
        }

    @Test
    fun `a time is kept to the microsecond and writes keep their order with a clock that stands still`() =
        runTest {
            val clock = FixedClock(Instant.parse("2026-11-15T10:00:00.123456789Z"))
            val store = PostgresStore(database, clock = clock)
            store.put(emptyList(), "first", "1")
            store.put(emptyList(), "second", "2")

            assertEquals(Instant.parse("2026-11-15T10:00:00.123456Z"), store.get(emptyList(), "first")?.updatedAt)
            assertEquals(Instant.parse("2026-11-15T10:00:00.123457Z"), store.get(emptyList(), "second")?.updatedAt)

            // A new instance, whose clock went back, still writes after the last write it finds.
            val later = PostgresStore(database, clock = FixedClock(Instant.parse("2026-11-15T09:00:00Z")))
            later.put(emptyList(), "third", "3")
            assertEquals(listOf("third", "second", "first"), later.list(emptyList()).map { it.key })
        }

    @Test
    fun `a namespace the store did not write is told`() =
        runTest {
            val store = PostgresStore(database)
            store.put(listOf("users"), "ana", "x")
            database.execute("UPDATE telar_store SET namespace = 'no slash'")

            assertFailsWith<StoreCorruptedException> { store.list(emptyList()) }
        }

    @Test
    fun `a table of the app's migrations is used as it is`() =
        runTest {
            database.execute(
                "CREATE TABLE memories (namespace TEXT COLLATE \"C\" NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, " +
                    "updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (namespace, key))",
            )
            val store = PostgresStore(database, table = "memories", createTable = false)

            store.put(listOf("users"), "ana", "vegan")

            assertEquals("vegan", store.get(listOf("users"), "ana")?.value)
        }

    @Test
    fun `a table name, a key, a namespace and a limit are checked`() =
        runTest {
            assertFailsWith<GraphValidationException> { PostgresStore(database, table = "1table") }
            val store = PostgresStore(database)
            assertFailsWith<GraphValidationException> { store.put(listOf("users"), "", "x") }
            assertFailsWith<GraphValidationException> { store.put(listOf(""), "k", "x") }
            assertFailsWith<GraphValidationException> { store.list(emptyList(), limit = 0) }
        }
}
