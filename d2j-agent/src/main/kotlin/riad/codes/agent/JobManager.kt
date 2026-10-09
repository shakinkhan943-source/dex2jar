package riad.codes.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks running and finished tool jobs.
 *
 * Every job owns an unbounded event [Channel]. Consumers (the SSE endpoint, an
 * in-process coroutine, or a remote sink) read from [JobHandle.events] while the
 * job runs; the channel is closed once the terminal result is stored.
 */
class JobManager(
    private val scope: CoroutineScope,
    private val extraListeners: List<ProgressListener> = emptyList(),
) {

    /** A single running or completed job. */
    class JobHandle(
        val id: String,
        val tool: String,
    ) {
        val events: Channel<ProgressEvent> = Channel(Channel.UNLIMITED)

        @Volatile
        var result: ToolResult? = null

        @Volatile
        var done: Boolean = false

        internal lateinit var coroutine: Job
    }

    private val jobs = ConcurrentHashMap<String, JobHandle>()

    /** Start [tool] with [args]; returns the created job handle immediately. */
    fun start(tool: AgentTool, args: Map<String, Any?>): JobHandle {
        val id = UUID.randomUUID().toString()
        val handle = JobHandle(id, tool.spec.name)
        jobs[id] = handle

        val listeners = listOf(ProgressListener { handle.events.trySend(it) }) + extraListeners
        val reporter = ProgressReporter(id, tool.spec.name, listeners)

        handle.coroutine = scope.launch {
            val startedAt = System.currentTimeMillis()
            val result = try {
                tool.run(ToolArguments(args), reporter)
            } catch (t: Throwable) {
                reporter.failed(t.message ?: t.javaClass.simpleName)
                ToolResult(
                    jobId = id,
                    tool = tool.spec.name,
                    success = false,
                    error = t.message ?: t.javaClass.name,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
            handle.result = result
            handle.done = true
            handle.events.close()
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

    fun events(id: String): ReceiveChannel<ProgressEvent>? = jobs[id]?.events
}
