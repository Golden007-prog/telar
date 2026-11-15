package dev.deeptelar.telar

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * Watches the runs of a graph: when a run starts and how it ends, when each node starts and how it
 * ends, when the condition of an edge decides where the run goes, which nodes come next after a
 * step, and when a checkpoint is saved. Use it to send traces
 * and metrics to the tools a team already has, such as OpenTelemetry with `telar-otel`. Give it to
 * a run with [GraphConfig.observers]:
 *
 * ```kotlin
 * val timer = object : GraphObserver {
 *     override fun onNodeCompleted(node: ObservedNode) = println("${node.name} done in step ${node.step}")
 * }
 * graph.invoke(order, GraphConfig(observers = listOf(timer)))
 * ```
 *
 * Every function does nothing unless it is overridden, so an observer overrides only what it needs.
 * An observer is told about every run of the config: [CompiledGraph.invoke] and
 * [CompiledGraph.stream], [CompiledGraph.resume], [CompiledGraph.fork], and the runs of the
 * subgraphs inside them.
 *
 * What an observer gets:
 * - **No state.** The state can hold personal data, so an observer only learns names, steps,
 *   timing and failures. [onNodeProgress] is the one exception: it gets what a node reports.
 * - **Every start has one end.** A run that started ends with exactly one of [onRunCompleted],
 *   [onRunInterrupted] and [onRunFailed], and a node that started ends with exactly one of
 *   [onNodeCompleted], [onNodeInterrupted] and [onNodeFailed], also when the run is cancelled. An
 *   edge that started ends with exactly one of [onEdgeCompleted] and [onEdgeFailed].
 * - **Calls from several coroutines.** The nodes of a step run in parallel and call the observer at
 *   the same time, possibly on different threads. An observer that keeps data must be safe for that.
 *
 * The functions are not `suspend`: they are called in the middle of the engine and must return
 * quickly. Hand slow work, such as sending data over the network, to a queue of your own. What an
 * observer throws fails the run, so an observer must not throw.
 */
public interface GraphObserver {
    /** [run] starts, before anything is loaded or saved. */
    public fun onRunStarted(run: ObservedRun) {}

    /** [run] reached [END] on every branch. */
    public fun onRunCompleted(run: ObservedRun) {}

    /**
     * [run] paused: at [GraphConfig.interruptBefore] or [GraphConfig.interruptAfter], or because a
     * node called [interrupt]. [nextNodes] run when the thread is resumed.
     */
    public fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) {}

    /**
     * [run] failed with [cause], the exception that the caller of the run gets, or a
     * `CancellationException` when the run was cancelled.
     */
    public fun onRunFailed(run: ObservedRun, cause: Throwable) {}

    /** [node] starts. A node with a [Retry] starts once, however often it is tried. */
    public fun onNodeStarted(node: ObservedNode) {}

    /**
     * [node] reported [value] with [reportProgress], such as a piece of the text a model writes.
     * Only a run that is collected with [CompiledGraph.stream], [CompiledGraph.streamResume] or
     * [CompiledGraph.streamFork] has progress: an observer does not turn it on.
     */
    public fun onNodeProgress(node: ObservedNode, value: Any) {}

    /**
     * A try of [node] failed with [cause], and the node runs again after [pause]. See
     * [GraphEvent.NodeRetry].
     */
    public fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) {}

    /** [node] finished its work. Its result is combined with those of the other nodes of its step next. */
    public fun onNodeCompleted(node: ObservedNode) {}

    /**
     * [node] paused the run with [interrupt]. It runs again when the thread is resumed. Its own
     * run follows with [onRunInterrupted].
     */
    public fun onNodeInterrupted(node: ObservedNode) {}

    /**
     * [node] failed with [cause]: a [NodeExecutionException] when its last try failed, or a
     * `CancellationException` when it was cancelled, because another node of its step failed or
     * paused the run, or because the run was cancelled.
     */
    public fun onNodeFailed(node: ObservedNode, cause: Throwable) {}

    /**
     * The condition of [edge] starts, on the state that its step left. Only an edge with a condition
     * is reported: `conditionalEdge`, `conditionalFanOut` and the edges built on them, such as
     * `decisionEdge`. The conditions of a step run one after the other, after every node of the step
     * ended and before [onStepCompleted].
     */
    public fun onEdgeStarted(edge: ObservedEdge) {}

    /** The condition of [edge] chose [targets], [END] among them when it ends a branch. */
    public fun onEdgeCompleted(edge: ObservedEdge, targets: List<String>) {}

    /**
     * The condition of [edge] failed with [cause]: an [EdgeConditionException] when it threw, an
     * [InvalidRouteException] when it chose a node that it may not go to, or a
     * `CancellationException` when the run was cancelled.
     */
    public fun onEdgeFailed(edge: ObservedEdge, cause: Throwable) {}

    /**
     * Step [step] of [run] finished: [nodes] ran, their results were combined and the edges chose
     * [nextNodes]. An empty [nextNodes] means the run completes.
     */
    public fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) {}

    /**
     * The checkpointer of [run] saved a checkpoint after step [step] (0 when the run starts), with
     * [nextNodes] to run next. A subgraph has no checkpointer of its own: the graph around it saves.
     */
    public fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) {}

    /**
     * Returns what to add to the coroutine context in which [node] runs, for example the trace span
     * of the node, so that what the node calls is recorded inside it. Called once for each node in
     * each step, after [onNodeStarted].
     */
    public fun nodeContext(node: ObservedNode): CoroutineContext = EmptyCoroutineContext

    /**
     * Returns what to add to the coroutine context in which the condition of [edge] runs, like
     * [nodeContext] for a node. Called once for each edge, after [onEdgeStarted].
     */
    public fun edgeContext(edge: ObservedEdge): CoroutineContext = EmptyCoroutineContext
}

/**
 * A run that a [GraphObserver] is told about. It is the same object from the start of the run to its
 * end, so an observer can keep what belongs to a run, such as a trace span, in a map with the run as
 * its key.
 *
 * @property threadId the thread of the run, from [GraphConfig.threadId].
 * @property parent the node that runs this run as a subgraph. `null` for the run that the app started.
 * @property context the coroutine context the run was started in: that of the code that called
 * [CompiledGraph.invoke] or collected [CompiledGraph.stream], or that of [parent] for a subgraph. An
 * observer reads its own elements there, such as the span the app opened around the run, so that it
 * can record the run inside it. It must not cancel the run's [kotlinx.coroutines.Job].
 */
public class ObservedRun internal constructor(
    public val threadId: String,
    public val parent: ObservedNode?,
    public val context: CoroutineContext,
) {
    override fun toString(): String = if (parent == null) "run of thread '$threadId'" else "subgraph run of $parent"
}

/**
 * The part of a run whose code is running: a node, or the condition of an edge. A library that
 * reports what happens inside, such as the calls to models of `telar-agent`, reports it under the
 * caller that [forEachObserver] gives.
 */
public sealed interface ObservedCaller {
    /** The run the caller belongs to. */
    public val run: ObservedRun

    /** The step of [run] in which the caller runs. */
    public val step: Int
}

/**
 * A node in one step of a run, which a [GraphObserver] is told about. It is the same object from the
 * start of the node to its end.
 *
 * @property run the run the node belongs to.
 * @property step the step of [run] in which the node runs.
 * @property name the name of the node.
 */
public class ObservedNode internal constructor(
    override val run: ObservedRun,
    override val step: Int,
    public val name: String,
) : ObservedCaller {
    override fun toString(): String = "node '$name' in step $step"
}

/**
 * An edge with a condition, which a [GraphObserver] is told about when the condition runs. It is the
 * same object from the start of the condition to its end.
 *
 * @property run the run the edge belongs to.
 * @property step the step whose results the edge routes: the step in which [from] ran, or 0 for an
 * edge from [START] when the run starts.
 * @property from the node the edge leaves, or [START].
 */
public class ObservedEdge internal constructor(
    override val run: ObservedRun,
    override val step: Int,
    public val from: String,
) : ObservedCaller {
    override fun toString(): String = "edge from '$from' after step $step"
}

/**
 * Calls [notify] with each observer of the run and the node or edge that runs this code, so that a
 * library can tell observers of its own kind about what happens inside a node or in the condition of
 * an edge. `telar-agent` tells its `AgentObserver`s about the calls to models and tools this way:
 *
 * ```kotlin
 * forEachObserver { observer, caller -> (observer as? CacheObserver)?.onCacheHit(caller) }
 * ```
 *
 * The rules of [GraphObserver] hold for what the observers are told. Does nothing outside a running
 * node or condition. In a subgraph, the caller is the node or edge of the subgraph that runs this
 * code.
 */
public suspend fun forEachObserver(notify: (observer: GraphObserver, caller: ObservedCaller) -> Unit) {
    val current = currentCoroutineContext()[ObservingCaller] ?: return
    val observer = current.observer
    val observers = if (observer is ObserverGroup) observer.observers else listOf(observer)
    observers.forEach { notify(it, current.caller) }
}

/**
 * Calls [notify] with each observer of the run and the node that runs this code. Does nothing outside
 * a running node, also in the condition of an edge.
 */
@Deprecated(
    "Use forEachObserver, which also reports in the condition of an edge. Its caller is an ObservedNode inside a node.",
    ReplaceWith("forEachObserver(notify)"),
)
public suspend fun forEachNodeObserver(notify: (observer: GraphObserver, node: ObservedNode) -> Unit) {
    val running = currentCoroutineContext()[RunningNode] ?: return
    val observer = running.observer
    val observers = if (observer is ObserverGroup) observer.observers else listOf(observer)
    observers.forEach { notify(it, running.observed) }
}

/** The observer of the run and the node or edge whose code runs in this coroutine, for [forEachObserver]. */
internal class ObservingCaller(
    val observer: GraphObserver,
    val caller: ObservedCaller,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ObservingCaller>
}

/** The observers of a config, told about everything as one. */
internal fun GraphConfig<*>.observer(): GraphObserver = observers.singleOrNull() ?: ObserverGroup(observers)

/** Tells each of [observers] in turn. */
private class ObserverGroup(
    val observers: List<GraphObserver>,
) : GraphObserver {
    override fun onRunStarted(run: ObservedRun) = observers.forEach { it.onRunStarted(run) }

    override fun onRunCompleted(run: ObservedRun) = observers.forEach { it.onRunCompleted(run) }

    override fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) = observers.forEach { it.onRunInterrupted(run, nextNodes) }

    override fun onRunFailed(run: ObservedRun, cause: Throwable) = observers.forEach { it.onRunFailed(run, cause) }

    override fun onNodeStarted(node: ObservedNode) = observers.forEach { it.onNodeStarted(node) }

    override fun onNodeProgress(node: ObservedNode, value: Any) = observers.forEach { it.onNodeProgress(node, value) }

    override fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) =
        observers.forEach { it.onNodeRetry(node, attempt, cause, pause) }

    override fun onNodeCompleted(node: ObservedNode) = observers.forEach { it.onNodeCompleted(node) }

    override fun onNodeInterrupted(node: ObservedNode) = observers.forEach { it.onNodeInterrupted(node) }

    override fun onNodeFailed(node: ObservedNode, cause: Throwable) = observers.forEach { it.onNodeFailed(node, cause) }

    override fun onEdgeStarted(edge: ObservedEdge) = observers.forEach { it.onEdgeStarted(edge) }

    override fun onEdgeCompleted(edge: ObservedEdge, targets: List<String>) = observers.forEach { it.onEdgeCompleted(edge, targets) }

    override fun onEdgeFailed(edge: ObservedEdge, cause: Throwable) = observers.forEach { it.onEdgeFailed(edge, cause) }

    override fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) =
        observers.forEach { it.onStepCompleted(run, step, nodes, nextNodes) }

    override fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) =
        observers.forEach { it.onCheckpointSaved(run, step, nextNodes) }

    override fun nodeContext(node: ObservedNode): CoroutineContext =
        observers.fold<GraphObserver, CoroutineContext>(EmptyCoroutineContext) { context, observer -> context + observer.nodeContext(node) }

    override fun edgeContext(edge: ObservedEdge): CoroutineContext =
        observers.fold<GraphObserver, CoroutineContext>(EmptyCoroutineContext) { context, observer -> context + observer.edgeContext(edge) }
}

/**
 * Tells an observer how [run] goes. A run ends once for the observer, also when the collector of its
 * stream throws after the last event.
 */
internal class RunWatch(
    val observer: GraphObserver,
    val run: ObservedRun,
) {
    private var ended = false

    fun started() = observer.onRunStarted(run)

    fun completed() {
        ended = true
        observer.onRunCompleted(run)
    }

    fun interrupted(nextNodes: List<String>) {
        ended = true
        observer.onRunInterrupted(run, nextNodes)
    }

    fun failed(cause: Throwable) {
        if (ended) return
        ended = true
        observer.onRunFailed(run, cause)
    }
}
