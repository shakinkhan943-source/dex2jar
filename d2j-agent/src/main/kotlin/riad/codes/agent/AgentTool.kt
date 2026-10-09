package riad.codes.agent

/** Receives every progress event a tool emits. */
fun interface ProgressListener {
    fun onEvent(event: ProgressEvent)
}

/**
 * Handed to a running [AgentTool] so the tool can publish real-time progress.
 *
 * A reporter is fan-out: the local job stream plus any remote sinks (for example a
 * progress API endpoint the agent subscribes to) all receive the same events.
 */
class ProgressReporter(
    val jobId: String,
    val tool: String,
    private val listeners: List<ProgressListener>,
) {
    fun emit(
        phase: ProgressPhase,
        message: String,
        percent: Double? = null,
        current: Int? = null,
        total: Int? = null,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val event = ProgressEvent(
            jobId = jobId,
            tool = tool,
            phase = phase,
            message = message,
            percent = percent,
            current = current,
            total = total,
            data = data,
        )
        listeners.forEach { it.onEvent(event) }
    }

    fun started(message: String, data: Map<String, Any?> = emptyMap()) =
        emit(ProgressPhase.STARTED, message, percent = 0.0, data = data)

    fun progress(message: String, percent: Double? = null, current: Int? = null, total: Int? = null) =
        emit(ProgressPhase.PROGRESS, message, percent, current, total)

    fun item(message: String, current: Int, total: Int) =
        emit(ProgressPhase.ITEM, message, percent = pct(current, total), current = current, total = total)

    fun completed(message: String, data: Map<String, Any?> = emptyMap()) =
        emit(ProgressPhase.COMPLETED, message, percent = 100.0, data = data)

    fun failed(message: String) =
        emit(ProgressPhase.FAILED, message)

    private fun pct(current: Int, total: Int): Double =
        if (total <= 0) 0.0 else (current.toDouble() / total.toDouble()) * 100.0
}

/** A tool the agent bridge can execute. */
interface AgentTool {
    val spec: ToolSpec

    /**
     * Run the tool. Implementations should be cooperative with cancellation and
     * emit progress through [reporter].
     */
    suspend fun run(args: ToolArguments, reporter: ProgressReporter): ToolResult
}

/** Typed, validated access to raw JSON arguments. */
class ToolArguments(private val raw: Map<String, Any?>) {

    fun string(name: String, required: Boolean = false, default: String? = null): String? {
        val v = raw[name] ?: return requireOrNull(name, required, default)
        return v.toString()
    }

    fun int(name: String, default: Int? = null): Int? =
        raw[name]?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() } ?: default

    fun boolean(name: String, default: Boolean = false): Boolean =
        raw[name]?.let {
            when (it) {
                is Boolean -> it
                else -> it.toString().equals("true", ignoreCase = true)
            }
        } ?: default

    fun raw(): Map<String, Any?> = raw

    private fun requireOrNull(name: String, required: Boolean, default: String?): String? {
        if (required && default == null) throw ToolArgumentException("missing required argument: $name")
        return default
    }
}
