package dev.deeptelar.telar.checkpoint.postgres

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.SubgraphPosition
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Order(
    val item: String,
    val approved: Boolean = false,
    val log: List<String> = emptyList(),
)

class PostgresCheckpointerTest {
    private val serializer = KotlinxStateSerializer<Order>()
    private val database = Database.fresh()

    private fun checkpointer(maxHistory: Int = Int.MAX_VALUE) = PostgresCheckpointer(database, serializer, maxHistory = maxHistory)

    private fun checkpoint(item: String, step: Int, nextNodes: List<String> = listOf("next")) = Checkpoint(Order(item), nextNodes, step)

    @Test
    fun `saves and loads a checkpoint with everything it holds`() =
        runTest {
            val checkpointer = checkpointer()
            val checkpoint =
                Checkpoint(
                    Order("pizza", log = listOf("asked")),
                    listOf("pay"),
                    step = 3,
                    interruptedBefore = true,
                    subgraphs = mapOf("pay" to SubgraphPosition(listOf("card"), step = 1, interruptedBefore = true)),
                    effects = mapOf("pay/charge" to "\"receipt-7\""),
                )

            checkpointer.save("order-42", checkpoint)

            assertEquals(checkpoint, checkpointer.load("order-42"))
            assertEquals(listOf(checkpoint), checkpointer.history("order-42"))
        }

    @Test
    fun `a thread without checkpoints loads null and has no history`() =
        runTest {
            val checkpointer = checkpointer()

            assertNull(checkpointer.load("nobody"))
            assertEquals(emptyList(), checkpointer.history("nobody"))
            checkpointer.delete("nobody")
        }

    @Test
    fun `a thread keeps one checkpoint per step and a save replaces its step and the later ones`() =
        runTest {
            val checkpointer = checkpointer()
            (0..3).forEach { checkpointer.save("t", checkpoint("v$it", it)) }

            // A fork or a resume from step 1 writes step 1 again: steps 2 and 3 belong to the old path.
            checkpointer.save("t", checkpoint("again", 1))

            assertEquals(listOf("v0", "again"), checkpointer.history("t").map { it.state.item })
            assertEquals("again", checkpointer.load("t")?.state?.item)
        }

    @Test
    fun `maxHistory keeps the latest checkpoints of a thread`() =
        runTest {
            val checkpointer = checkpointer(maxHistory = 2)
            (0..4).forEach { checkpointer.save("t", checkpoint("v$it", it)) }

            assertEquals(listOf(3, 4), checkpointer.history("t").map { it.step })
        }

    @Test
    fun `threads are separate and delete removes only its own`() =
        runTest {
            val checkpointer = checkpointer()
            checkpointer.save("a", checkpoint("for a", 1))
            checkpointer.save("b", checkpoint("for b", 1))

            checkpointer.delete("a")

            assertNull(checkpointer.load("a"))
            assertEquals("for b", checkpointer.load("b")?.state?.item)
        }

    @Test
    fun `a run paused on one instance is resumed on another`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    START then node("ask") { it.copy(log = it.log + "asked") } then
                        node("pay") { it.copy(log = it.log + "paid") } then END
                }.compile()
            val first = GraphConfig("order-42", checkpointer(), interruptBefore = setOf("pay"))
            val paused = graph.invoke(Order("pizza"), first)
            assertIs<GraphResult.Interrupted<Order>>(paused)

            // Another server, with its own checkpointer on the same database.
            val second = GraphConfig("order-42", checkpointer(), interruptBefore = setOf("pay"))
            val done = graph.resume(second) { it.copy(approved = true) }

            assertIs<GraphResult.Completed<Order>>(done)
            assertEquals(Order("pizza", approved = true, log = listOf("asked", "paid")), done.state)
        }

    @Test
    fun `two instances that save the same thread at once take turns`() =
        runTest {
            val instances = List(4) { checkpointer(maxHistory = 3) }

            withContext(Dispatchers.IO) {
                (1..40)
                    .map { step -> async { instances[step % instances.size].save("t", checkpoint("v$step", step)) } }
                    .awaitAll()
            }

            // Whatever the order, the rows are those of one save after another: at most 3, in order of step.
            val history = instances.first().history("t")
            assertTrue(history.size in 1..3, "history was $history")
            assertEquals(history.map { it.step }.sorted(), history.map { it.step })
        }

    @Test
    fun `instances that start together all create the table`() =
        runTest {
            withContext(Dispatchers.IO) {
                (1..8).map { thread -> async { checkpointer().save("t$thread", checkpoint("x", 1)) } }.awaitAll()
            }

            assertEquals(8, (1..8).count { checkpointer().load("t$it") != null })
        }

    @Test
    fun `a table in a schema of the app, created by its migrations`() =
        runTest {
            database.execute("CREATE SCHEMA agents")
            database.execute(
                "CREATE TABLE agents.runs (thread_id TEXT NOT NULL, step INTEGER NOT NULL, checkpoint TEXT NOT NULL, " +
                    "PRIMARY KEY (thread_id, step))",
            )
            val checkpointer = PostgresCheckpointer(database, serializer, table = "agents.runs", createTable = false)

            checkpointer.save("t", checkpoint("in a schema", 1))

            assertEquals("in a schema", checkpointer.load("t")?.state?.item)
        }

    @Test
    fun `without createTable a missing table is a failure of the database`() =
        runTest {
            val checkpointer = PostgresCheckpointer(database, serializer, table = "missing", createTable = false)

            assertFailsWith<SQLException> { checkpointer.load("t") }
        }

    @Test
    fun `a checkpoint that cannot be read is reported as corrupted`() =
        runTest {
            val checkpointer = checkpointer()
            checkpointer.save("t", checkpoint("fine", 1))
            database.execute("UPDATE telar_checkpoints SET checkpoint = 'not json'")

            assertFailsWith<CheckpointCorruptedException> { checkpointer.load("t") }
        }

    @Test
    fun `a failed save changes nothing`() =
        runTest {
            val checkpointer = checkpointer()
            checkpointer.save("t", checkpoint("kept", 1))
            // A column the insert does not fill makes the second statement of a save fail.
            database.execute("ALTER TABLE telar_checkpoints ADD COLUMN required TEXT NOT NULL DEFAULT 'x'")
            database.execute("ALTER TABLE telar_checkpoints ALTER COLUMN required DROP DEFAULT")

            assertFailsWith<SQLException> { checkpointer.save("t", checkpoint("lost", 1)) }

            assertEquals("kept", checkpointer.load("t")?.state?.item)
        }

    @Test
    fun `a table name that is not plain and a maxHistory below one are refused`() {
        assertFailsWith<GraphValidationException> { PostgresCheckpointer(database, serializer, table = "runs; DROP TABLE x") }
        assertFailsWith<GraphValidationException> { PostgresCheckpointer(database, serializer, table = "a.b.c") }
        assertFailsWith<GraphValidationException> { PostgresCheckpointer(database, serializer, maxHistory = 0) }
    }
}
