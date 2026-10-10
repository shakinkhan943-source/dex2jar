package riad.codes.d2j.engine

import riad.codes.d2j.DexConstants

/**
 * Typed conversion options for the modern engine. All knobs that the legacy
 * fluent `Dex2jar` builder exposes, stated explicitly so CLI and agent
 * integrations share one API surface.
 */
data class ConversionOptions(
    /** Reuse dex registers instead of allocating fresh locals per SSA variable. */
    val reuseReg: Boolean = false,
    /** Sort basic blocks topologically for more readable decompiled output. */
    val topologicalSort: Boolean = true,
    /** Preserve debug info (line numbers / local variables) into the jar. */
    val debugInfo: Boolean = false,
    /** Optimize synchronized blocks/monitors during ir2j lowering. */
    val optimizeSynchronized: Boolean = false,
    /** Skip method bodies entirely (stubs only). */
    val noCode: Boolean = false,
    /** Do not attempt to recover from malformed method bodies; fail fast instead. */
    val strict: Boolean = false,
    /** Print IR to stdout (diagnostics). */
    val printIR: Boolean = false,
    /** Maximum number of classes*.dex entries accepted from one container. */
    val maxDexCount: Int = 512,
    /** Maximum accepted input size in bytes; protects against zip-bomb style inputs. */
    val maxInputBytes: Long = 1L shl 32,
    /** DEX versions above this are refused instead of best-effort translated. */
    val maxSupportedDexVersion: Int = DexConstants.MAX_SUPPORTED_VERSION,
    /** Cancelled inputs stop translation at phase boundaries; check between classes. */
    val cancellation: Cancellation = Cancellation.NONE,
    /** Optional phase progress listener. */
    val listener: ProgressListener = ProgressListener.NONE,
) {
    init {
        require(maxDexCount > 0) { "maxDexCount must be positive (got $maxDexCount)" }
        require(maxInputBytes > 0) { "maxInputBytes must be positive (got $maxInputBytes)" }
    }
}

/** Co-operative cancellation handle checked at phase boundaries. */
interface Cancellation {
    fun isCancelled(): Boolean

    object NONE : Cancellation {
        override fun isCancelled(): Boolean = false
    }

    fun interface Token : Cancellation
}

/** Progress events emitted at engine phase boundaries. */
interface ProgressListener {
    fun onPhase(phase: EnginePhase, detail: String)

    object NONE : ProgressListener {
        override fun onPhase(phase: EnginePhase, detail: String) = Unit
    }
}

enum class EnginePhase {
    DISCOVERY,
    VALIDATION,
    TRANSLATE,
    WRITE,
    PUBLISH,
}
