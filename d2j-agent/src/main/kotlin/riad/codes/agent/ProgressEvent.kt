package riad.codes.agent

import java.time.Instant

/** Lifecycle phase of a single tool run. */
enum class ProgressPhase {
    STARTED,
    PROGRESS,
    ITEM,
    COMPLETED,
    FAILED,
}

/**
 * A single real-time progress event streamed from a running tool.
 *
 * Events are emitted through a cold [kotlinx.coroutines.flow.Flow] so an agent can
 * consume them incrementally (SSE, WebSocket, stdout, or a remote HTTP sink).
 */
data class ProgressEvent(
    val jobId: String,
    val tool: String,
    val phase: ProgressPhase,
    val message: String,
    val percent: Double? = null,
    val current: Int? = null,
    val total: Int? = null,
    val timestamp: String = Instant.now().toString(),
    val data: Map<String, Any?> = emptyMap(),
)

/** Terminal result of a tool run. */
data class ToolResult(
    val jobId: String,
    val tool: String,
    val success: Boolean,
    val output: Map<String, Any?> = emptyMap(),
    val error: String? = null,
    val durationMs: Long = 0,
)
