package riad.codes.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks running and completed tool jobs.
 *
 * Every job broadcasts its progress through a per-job [MutableSharedFlow], so
 * each subscriber gets its own view of the event stream. A slow or late
 * consumer can therefore never steal events from another consumer — the
 * previous single unbounded `Channel` had exactly that single-subscriber bug.
 * The bounded replay buffer lets an SSE client that (re)connects to
 * `/v1/jobs/{id}/events` catch up on recent events without unbounded growth.
 *
 * Completed jobs are retained for [ttlMs] and at most [maxRetainedJobs]
 * entries, so polling agents do not leak memory in a long-lived bridge.
 */
class JobManager(
    private val scope: CoroutineScope,
    private val extraListeners: List<ProgressListener> = emptyList(),
    private val maxRetainedJobs: Int = 256,
    private val ttlMs: Long = 15 * 60_000L,
    private val replayBufferSize: Int = 256,
) {

    /** A single running or completed job. */
    class JobHandle internal constructor(
        val id: String,
        val tool: String,
        replayBufferSize: Int,
    ) {
        internal val flow = MutableSharedFlow<ProgressEvent>(
            replay = replayBufferSize,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        private val completion = CompletableDeferred<Unit>()

        @Volatile
        var result: ToolResult? = null
            internal set

        @Volatile
        var done: Boolean = false
            internal set

        internal lateinit var coroutine: Job
        internal var startedAt: Long = 0
        internal var finishedAt: Long = 0

        /** Cold view over this job's events; safe for many concurrent subscribers. */
        fun events(): Flow<ProgressEvent> = flow.asSharedFlow()

        /** Events emitted so far, for a late or reconnecting subscriber. */
        fun replay(): List<ProgressEvent> = flow.replayCache.toList()

        /** Suspends until the job reaches a terminal state. */
        suspend fun awaitCompletion() = completion.await()

        internal fun complete() {
            completion.complete(Unit)
        }
    }

    private val jobs = ConcurrentHashMap<String, JobHandle>()

    /** Start [tool] with [args]; returns the created job handle immediately. */
    fun start(tool: AgentTool, args: Map<String, Any?>): JobHandle {
        val id = UUID.randomUUID().toString()
        val handle = JobHandle(id, tool.spec.name, replayBufferSize)
        handle.startedAt = System.currentTimeMillis()
        jobs[id] = handle
        retain()

        val listeners = listOf(ProgressListener { handle.flow.tryEmit(it) }) + extraListeners
        val reporter = ProgressReporter(id, tool.spec.name, listeners)

        handle.coroutine = scope.launch {
            val result = try {
                tool.run(ToolArguments(args), reporter)
            } catch (t: Throwable) {
                reporter.failed(t.message ?: t.javaClass.simpleName)
                ToolResult(
                    jobId = id,
                    tool = tool.spec.name,
                    success = false,
                    error = t.message ?: t.javaClass.name,
                    durationMs = System.currentTimeMillis() - handle.startedAt,
                )
            }
            handle.result = result
            handle.finishedAt = System.currentTimeMillis()
            handle.done = true
            handle.complete()
            retain()
        }
        return handle
    }

    /** Run [tool] synchronously and return its result (used by the plain HTTP endpoint). */
    suspend fun runBlocking(tool: AgentTool, args: Map<String, Any?>): ToolResult {
        val id = UUID.randomUUID().toString()
        val reporter = ProgressReporter(id, tool.spec.name, extraListeners)
        val startedAt = System.currentTimeMillis()
        return try {
            tool.run(ToolArguments(args), reporter)
        } catch (t: Throwable) {
            ToolResult(
                jobId = id,
                tool = tool.spec.name,
                success = false,
                error = t.message ?: t.javaClass.name,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    fun get(id: String): JobHandle? = jobs[id]

    fun ids(): List<String> = jobs.keys.sorted()

    /** A cold, multi-subscriber view of a job's events, or null for an unknown id. */
    fun events(id: String): Flow<ProgressEvent>? = jobs[id]?.events()

    /** Enforce retention: drop expired jobs, then oldest completed jobs past [maxRetainedJobs]. */
    private fun retain() {
        val now = System.currentTimeMillis()
        val expired = jobs.values.filter { it.done && now - it.finishedAt > ttlMs }.map { it.id }
        for (id in expired) jobs.remove(id)

        if (jobs.size > maxRetainedJobs) {
            val completed = jobs.values
                .filter { it.done }
                .sortedBy { it.finishedAt }
            val overflow = jobs.size - maxRetainedJobs
            for (handle in completed.take(overflow)) jobs.remove(handle.id)
        }
    }
}
