package dev.deeptelar.telar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Writes down the nodes, edges and steps it is told about, one line per call. */
private class EdgeRecorder : GraphObserver {
    val log = mutableListOf<String>()

    override fun onNodeStarted(node: ObservedNode) {
        log += "${node.name}@${node.step} started"
    }

    override fun onEdgeStarted(edge: ObservedEdge) {
        log += "edge from ${edge.from}@${edge.step} started"
    }

    override fun onEdgeCompleted(edge: ObservedEdge, targets: List<String>) {
        log += "edge from ${edge.from}@${edge.step} chose $targets"
    }

    override fun onEdgeFailed(edge: ObservedEdge, cause: Throwable) {
        val reason =
            when (cause) {
                is CancellationException -> "cancelled"
                is EdgeConditionException -> "condition failed with '${cause.cause?.message}'"
                else -> cause::class.simpleName
            }
        log += "edge from ${edge.from}@${edge.step} failed: $reason"
    }

    override fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) {
        log += "step $step: $nodes -> $nextNodes"
    }
}

class EdgeObserverTest {
    private val recorder = EdgeRecorder()
    private val config = GraphConfig<TestState>(observers = listOf(recorder))

    @Test
    fun `an edge with a condition is told with what it chose and a plain edge is not`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    val count = node("count") { it.copy(count = it.count + 1) }
                    val small = node("small") { it }
                    val large = node("large") { it }
                    conditionalEdge(START, setOf(count, large)) { if (it.count > 5) large else count }
                    conditionalEdge(count, setOf(small.name, END)) { if (it.count < 2) small.name else END }
                    small then END
                    large then END
                }.compile()

            graph.invoke(TestState(), config)

            assertEquals(
                listOf(
                    "edge from __START__@0 started",
                    "edge from __START__@0 chose [count]",
                    "count@1 started",
                    "edge from count@1 started",
                    "edge from count@1 chose [small]",
                    "step 1: [count] -> [small]",
                    "small@2 started",
                    "step 2: [small] -> []",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a fan-out is told with every target it chose`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    val a = node("a") { it }
                    val b = node("b") { it }
                    conditionalFanOut(START, setOf(a, b)) { setOf(a, b) }
                    a then END
                    b then END
                }.compile(reducer = { _, states -> states.last() })

            graph.invoke(TestState(), config)

            assertEquals("edge from __START__@0 chose [a, b]", recorder.log[1])
        }

    @Test
    fun `a condition that throws or chooses a node it may not go to fails its edge`() =
        runTest {
            val throwing =
                StateGraph<TestState> {
                    val a = node("a") { it }
                    START then a
                    conditionalEdge(a, setOf(END)) { error("No route for this ticket.") }
                }.compile()
            val lost =
                StateGraph<TestState> {
                    val a = node("a") { it }
                    START then a
                    conditionalEdge(a, setOf(END)) { "nowhere" }
                }.compile()

            assertFailsWith<EdgeConditionException> { throwing.invoke(TestState(), config) }
            assertFailsWith<InvalidRouteException> { lost.invoke(TestState(), config) }

            assertEquals(
                listOf(
                    "a@1 started",
                    "edge from a@1 started",
                    "edge from a@1 failed: condition failed with 'No route for this ticket.'",
                    "a@1 started",
                    "edge from a@1 started",
                    "edge from a@1 failed: InvalidRouteException",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a cancelled condition ends its edge as cancelled`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    val a = node("a") { it }
                    START then a
                    conditionalEdge(a, setOf(END)) { awaitCancellation() }
                }.compile()

            val job = launch { graph.invoke(TestState(), config) }
            testScheduler.runCurrent()
            job.cancel()
            job.join()

            assertEquals(listOf("a@1 started", "edge from a@1 started", "edge from a@1 failed: cancelled"), recorder.log)
        }

    @Test
    fun `a condition runs in the context its observer adds`() =
        runTest {
            val naming =
                object : GraphObserver {
                    override fun edgeContext(edge: ObservedEdge): CoroutineContext = CoroutineName("span of the edge from ${edge.from}")
                }
            var seen: String? = null
            val graph =
                StateGraph<TestState> {
                    val a = node("a") { it }
                    START then a
                    conditionalEdge(a, setOf(END)) {
                        seen = currentCoroutineContext()[CoroutineName]?.name
                        END
                    }
                }.compile()

            graph.invoke(TestState(), GraphConfig(observers = listOf(recorder, naming)))

            assertEquals("span of the edge from a", seen)
        }

    @Test
    fun `forEachObserver reaches the observers with the node or edge that runs the code`() =
        runTest {
            val second = EdgeRecorder()
            val reached = mutableListOf<String>()

            suspend fun report(where: String) =
                forEachObserver { observer, caller ->
                    val who = if (observer === recorder) "first" else "second"
                    reached +=
                        when (caller) {
                            is ObservedNode -> "$who in $where: node ${caller.name}@${caller.step}"
                            is ObservedEdge -> "$who in $where: edge from ${caller.from}@${caller.step}"
                        }
                }
            val inner =
                StateGraph<TestState> {
                    val deep = node("deep") { it }
                    START then deep
                    conditionalEdge(deep, setOf(END)) {
                        report("subgraph")
                        END
                    }
                }.compile()
            val graph =
                StateGraph<TestState> {
                    val read = node("read") { report("graph").let { _ -> it } }
                    START then read then subgraph("inner", inner) then END
                }.compile()

            report("nothing")
            graph.invoke(TestState(), GraphConfig(observers = listOf(recorder, second)))

            assertEquals(
                listOf(
                    "first in graph: node read@1",
                    "second in graph: node read@1",
                    "first in subgraph: edge from deep@1",
                    "second in subgraph: edge from deep@1",
                ),
                reached,
            )
        }
}
