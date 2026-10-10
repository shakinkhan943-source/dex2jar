package riad.codes.d2j.engine

import riad.codes.d2j.DexConstants
import riad.codes.d2j.dex.DexExceptionHandler

/**
 * Typed conversion options for the modern engine. Every option is traced
 * end-to-end into the conversion pipeline; all limits are enforced.
 *
 * Defaults target mobile-sized inputs (APKs up to a few hundred MB).
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
    /** Skip method bodies entirely (stubs only; <clinit> also skipped). */
    val noCode: Boolean = false,
    /**
     * Strict mode: any recoverable per-method/per-class translation error fails
     * the whole conversion and nothing is published. Non-strict (default):
     * errors are collected into [DexEngine.Report.warnings], the affected
     * methods are emitted as best-effort stubs, and the jar is published.
     * Either way, errors are never silently dropped.
     */
    val strict: Boolean = false,
    /** Print IR to stdout (diagnostics, off by default). */
    val printIR: Boolean = false,
    /** Skip reading try/catch exception handlers (smaller/faster, less faithful). */
    val skipExceptions: Boolean = false,
    /**
     * Emit stack map frames (ASM COMPUTE_FRAMES) with a safe fallback. Off by
     * default; see [riad.codes.d2j.dex.Dex2jar.computeFrames].
     */
    val computeFrames: Boolean = false,

    // -------- resource limits (all enforced; see DexInput/SafeOutput) --------

    /** Maximum number of classes*.dex entries accepted from one container. */
    val maxDexCount: Int = 512,
    /** Maximum accepted compressed input size in bytes. */
    val maxInputBytes: Long = 512L shl 20,
    /** Maximum decompressed size of a single classes*.dex entry (zip-bomb guard). */
    val maxDexEntryBytes: Long = 512L shl 20,
    /** Maximum total decompressed bytes read from a container (zip-bomb guard). */
    val maxTotalDecompressedBytes: Long = 2L shl 30,
    /** Maximum number of ZIP entries scanned before rejecting the container. */
    val maxZipEntries: Int = 65_536,

    // -------- output validation --------

    /**
     * How deeply generated class files are checked before publish:
     * [ValidationLevel.STRUCTURE] checks archive layout, entry safety and
     * class-file headers; [ValidationLevel.BYTECODE] additionally runs ASM's
     * structural bytecode verifier (CheckClassAdapter) over every method.
     * This is not a substitute for full JVM verification (which needs a
     * classpath for type hierarchy); see SafeOutput docs.
     */
    val validation: ValidationLevel = ValidationLevel.BYTECODE,

    // -------- DEX version gate --------

    /** Highest DEX version accepted; defaults to what this build actually implements. */
    val maxSupportedDexVersion: Int = DexConstants.MAX_SUPPORTED_VERSION,

    // -------- runtime control --------

    /**
     * Worker threads for per-dex (multidex) translation. 0 = auto (one worker per
     * DEX entry, capped by the CPU count); 1 = sequential. Raw single-DEX inputs
     * are always translated on the calling thread regardless of this value.
     */
    val parallelism: Int = 0,
    /** Cooperative cancellation, checked between phases and per class. */
    val cancellation: Cancellation = Cancellation.NONE,
    /** Phase + per-class progress listener. */
    val listener: ProgressListener = ProgressListener.NONE,
    /**
     * Optional legacy-compatible sink for per-method/per-file translate errors.
     * The engine always records these in [DexEngine.Report.warnings]; when a sink
     * is supplied it also receives the original (method node, exception) pairs,
     * which is what the CLI's `-error.zip` dump needs.
     */
    val exceptionHandler: DexExceptionHandler? = null,
) {
    init {
        require(maxDexCount > 0) { "maxDexCount must be positive (got $maxDexCount)" }
        require(maxInputBytes > 0) { "maxInputBytes must be positive (got $maxInputBytes)" }
        require(maxDexEntryBytes > 0) { "maxDexEntryBytes must be positive (got $maxDexEntryBytes)" }
        require(maxTotalDecompressedBytes > 0) {
            "maxTotalDecompressedBytes must be positive (got $maxTotalDecompressedBytes)"
        }
        require(maxZipEntries > 0) { "maxZipEntries must be positive (got $maxZipEntries)" }
        require(parallelism >= 0) { "parallelism must be >= 0 (0 = auto, 1 = sequential)" }
    }

    companion object {
        /**
         * Java-friendly factory mirroring the legacy `d2j-dex2jar` flags, so
         * `dex-tools` can adopt the engine without spelling out every default.
         * Uses STRUCTURE validation (fast) and wires the error-dump handler.
         */
        @JvmStatic
        fun cli(
            reuseReg: Boolean,
            debugInfo: Boolean,
            optimizeSynchronized: Boolean,
            printIR: Boolean,
            noCode: Boolean,
            skipExceptions: Boolean,
            strict: Boolean,
            exceptionHandler: DexExceptionHandler?,
        ): ConversionOptions = ConversionOptions(
            reuseReg = reuseReg,
            debugInfo = debugInfo,
            optimizeSynchronized = optimizeSynchronized,
            printIR = printIR,
            noCode = noCode,
            skipExceptions = skipExceptions,
            strict = strict,
            exceptionHandler = exceptionHandler,
            validation = ValidationLevel.STRUCTURE,
        )
    }
}

enum class ValidationLevel {
    /** Archive structure, safe entry names, class-file magic/version/size. */
    STRUCTURE,
    /** STRUCTURE + ASM CheckClassAdapter bytecode structure verification. */
    BYTECODE,
}

/** Co-operative cancellation handle. */
interface Cancellation {
    fun isCancelled(): Boolean

    object NONE : Cancellation {
        override fun isCancelled(): Boolean = false
    }

    /**
     * A one-shot callback form: `Cancellation.Token { isCancelled() }`.
     *(Reopened in test packages to make `@TempDir`-scope lambdas explicit.)
     */
    fun interface Token : Cancellation
}

/** Progress events: phase boundaries and per-class translation. */
interface ProgressListener {
    fun onPhase(phase: EnginePhase, detail: String)

    /** Called per translated class; [index] is 0-based, [total] is the class count. */
    fun onClass(className: String, index: Int, total: Int) {
    }

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
