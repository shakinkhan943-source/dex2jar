package riad.codes.agent

/** A single argument accepted by a tool. */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val default: String? = null,
)

/**
 * Machine-readable description of a tool. Agents fetch these from `GET /v1/tools`
 * and use them to build calls without any out-of-band documentation.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
)

/** Thrown when an agent calls a tool with invalid arguments. */
class ToolArgumentException(message: String) : RuntimeException(message)
