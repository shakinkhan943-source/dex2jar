package riad.codes.d2j.engine

import org.objectweb.asm.MethodVisitor
import riad.codes.d2j.Method
import riad.codes.d2j.dex.Dex2jar
import riad.codes.d2j.dex.DexExceptionHandler
import riad.codes.d2j.node.DexMethodNode
import riad.codes.d2j.reader.BaseDexFileReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The modern engine: one call converts any DEX / APK / ZIP into a validated jar
 * with atomic publish, phase + per-class progress, cancellation, and a
 * structured report.
 *
 * Guarantees:
 * - the destination path is only touched at the very end, via atomic move
 *   (non-atomic fallback documented); a failed/cancelled run never corrupts
 *   an existing valid output;
 * - all temporary staging data is removed on success, failure, and cancel;
 * - recoverable errors are surfaced in [Report.warnings] (non-strict) or fail
 *   the run (strict) — never silently dropped.
 */
@JvmSuppressWildcards
object DexEngine {
    /** Structured result of a conversion run. */
    data class Report(
        /** Number of classes*.dex inputs actually processed. */
        val inputDexCount: Int,
        /** Number of .class entries published to the jar. */
        val classCount: Int,
        /** DEX-declared classes that were dropped as duplicates across multidex inputs. */
        val duplicateClasses: List<String>,
        /** Highest DEX version seen in the input. */
        val dexVersion: Int,
        /** Container entry names processed, in Android multidex order. */
        val dexEntryNames: List<String>,
        /** Recoverable errors: failed methods/classes; empty in a clean run. */
        val warnings: List<String>,
        val durationMillis: Long,
        val publishedTo: Path,
        val bytesWritten: Long,
        /** Classes skipped because a [Cancellation] requested stop (0 if complete). */
        val cancelledAfterClasses: Int,
    ) {
        val complete: Boolean get() = cancelledAfterClasses == 0
    }

    class EngineException(val phase: EnginePhase, cause: Throwable) :
        RuntimeException("Engine failed during $phase: ${cause.message}", cause)

    /**
     * Convert [data] (a DEX file or ZIP/APK container) into a jar at [output].
     *
     * @throws EngineException on any failure; the cause chain keeps the original error.
     */
    @Throws(EngineException::class)
    fun convert(data: ByteArray, output: Path, options: ConversionOptions): Report {
        if (options.cancellation.isCancelled()) {
            throw EngineException(EnginePhase.DISCOVERY, CancellationException())
        }
        val started = System.currentTimeMillis()
        try {
            return convertInternal(data, output, options, started)
        } catch (e: Dex2jar.ConversionCancelledException) {
            throw EngineException(EnginePhase.TRANSLATE, CancellationException())
        } catch (e: CancellationException) {
            throw EngineException(EnginePhase.TRANSLATE, e)
        } catch (e: IOException) {
            throw EngineException(EnginePhase.DISCOVERY, e)
        } catch (e: EngineException) {
            throw e
        } catch (t: Throwable) {
            throw EngineException(EnginePhase.TRANSLATE, t)
        }
    }

    private fun convertInternal(
        data: ByteArray,
        output: Path,
        options: ConversionOptions,
        started: Long,
    ): Report {
        if (data.size.toLong() > options.maxInputBytes) {
            throw EngineException(
                EnginePhase.DISCOVERY,
                IllegalArgumentException("Input of ${data.size} bytes exceeds maxInputBytes=${options.maxInputBytes}")
            )
        }
        options.listener.onPhase(EnginePhase.DISCOVERY, "${data.size} bytes")
        val discovery = DexInput.discover(data, options)
        if (discovery.dexVersion > options.maxSupportedDexVersion) {
            throw EngineException(
                EnginePhase.VALIDATION,
                IllegalArgumentException(
                    "DEX version 0x${"%08x".format(discovery.dexVersion)} exceeds " +
                        "maxSupportedDexVersion=0x${"%08x".format(options.maxSupportedDexVersion)}"
                )
            )
        }
        options.listener.onPhase(
            EnginePhase.VALIDATION,
            "${discovery.dexCount} dex, ${discovery.classNames.size} classes, version=${discovery.dexVersion}"
        )
        if (options.cancellation.isCancelled()) {
            throw CancellationException()
        }

        // 1. Translate into a staging temp dir with per-class progress + cancel.
        val staging = Files.createTempDirectory("d2j-engine-")
        try {
            options.listener.onPhase(EnginePhase.TRANSLATE, "staging=${staging.fileName}")
            val handler = CollectingExceptionHandler(options)
            val cancelledAfter = java.util.concurrent.atomic.AtomicInteger(-1) // -1 = not cancelled
            val lock = Any()
            val readers = discovery.readers
            // Per-dex (multidex) translation. A raw single-DEX input is always
            // translated on the calling thread; containers get a bounded pool.
            val parallelism = if (options.parallelism > 0) {
                options.parallelism
            } else {
                minOf(readers.size, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
            }
            if (readers.size > 1 && parallelism > 1) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism)
                try {
                    val futures = readers.indices.map { i ->
                        pool.submit {
                            translateReader(readers[i], staging.resolve("dex$i"), options, handler, lock, cancelledAfter)
                        }
                    }
                    for (f in futures) {
                        try {
                            f.get()
                        } catch (e: java.util.concurrent.ExecutionException) {
                            val cause = e.cause
                            if (cause is Dex2jar.ConversionCancelledException) {
                                cancelledAfter.compareAndSet(-1, 0)
                            } else {
                                throw cause ?: e
                            }
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }
            } else {
                for (i in readers.indices) {
                    translateReader(readers[i], staging.resolve("dex$i"), options, handler, lock, cancelledAfter)
                }
            }

            if (cancelledAfter.get() >= 0) {
                throw CancellationException()
            }
            val warnings = handler.warnings()
            if (options.strict && warnings.isNotEmpty()) {
                throw IllegalStateException(
                    "Strict mode: ${warnings.size} recoverable translation errors; refusing to publish. " +
                        "First: ${warnings.first()}"
                )
            }
            options.listener.onPhase(EnginePhase.WRITE, "staging complete")

            // 2. Collect class files, validate, build jar in memory.
            val classes = collectClassFiles(staging, readers.size)
            if (classes.isEmpty()) {
                throw IllegalStateException("Conversion produced no .class files; refusing to publish an empty jar.")
            }
            val built = SafeOutput.buildJar(classes)
            options.listener.onPhase(EnginePhase.PUBLISH, "validating ${classes.size} entries")
            SafeOutput.validate(built, classes.map { it.first }.toSet(), options.validation)

            if (options.cancellation.isCancelled()) {
                throw CancellationException()
            }

            // 3. Atomic publish.
            publish(built, output, options)
            return Report(
                inputDexCount = discovery.dexCount,
                classCount = classes.count { it.first.endsWith(".class") },
                duplicateClasses = discovery.duplicates,
                dexVersion = discovery.dexVersion,
                dexEntryNames = discovery.entryNames,
                warnings = warnings,
                durationMillis = System.currentTimeMillis() - started,
                publishedTo = output,
                bytesWritten = built.size.toLong(),
                cancelledAfterClasses = 0,
            )
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun publish(built: ByteArray, output: Path, options: ConversionOptions) {
        val absOutput = output.toAbsolutePath()
        val parent = absOutput.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }
        val temp: Path = Files.createTempFile(parent, "d2j-engine-", ".publish")
        try {
            Files.write(temp, built)
            // Atomic replace where the filesystem supports it; documented
            // fallback is a plain move (still never a partial write: the temp
            // file is complete and validated before the move starts).
            try {
                Files.move(
                    temp, absOutput,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, absOutput, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        options.listener.onPhase(EnginePhase.PUBLISH, "-> $absOutput")
    }

    /**
     * Translate one DEX reader into [target]. Safe to run concurrently: progress
     * callbacks are serialized on [lock] and cancellation is reported through the
     * shared [cancelledAfter] counter.
     */
    private fun translateReader(
        reader: BaseDexFileReader,
        target: Path,
        options: ConversionOptions,
        handler: CollectingExceptionHandler,
        lock: Any,
        cancelledAfter: java.util.concurrent.atomic.AtomicInteger,
    ) {
        Files.createDirectories(target)
        Dex2jar.from(reader)
            .withExceptionHandler(handler)
            .withClassHook { name, index, total ->
                synchronized(lock) { options.listener.onClass(name, index, total) }
                if (options.cancellation.isCancelled()) {
                    cancelledAfter.compareAndSet(-1, index)
                    false
                } else if (cancelledAfter.get() >= 0) {
                    false // another dex already reported cancellation
                } else {
                    true
                }
            }
            .reUseReg(options.reuseReg)
            .topoLogicalSort(options.topologicalSort)
            .skipDebug(!options.debugInfo)
            .optimizeSynchronized(options.optimizeSynchronized)
            .printIR(options.printIR)
            .noCode(options.noCode)
            .skipExceptions(options.skipExceptions)
            .computeFrames(options.computeFrames)
            .to(target)
    }

    /**
     * Merge the per-dex staging dirs into a single class list. Duplicate class
     * names are resolved first-DEX-wins (matching MultiDexFileReader), then the
     * list is sorted by entry name so the jar is deterministic regardless of
     * thread scheduling.
     */
    private fun collectClassFiles(staging: Path, dexCount: Int): List<Pair<String, ByteArray>> {
        val byName = LinkedHashMap<String, ByteArray>()
        for (i in 0 until dexCount) {
            val dir = staging.resolve("dex$i")
            if (!Files.isDirectory(dir)) continue
            val files = Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
            files.sortedBy { it.toString() }.forEach { p ->
                val rel = dir.relativize(p).toString().replace('\\', '/')
                byName.putIfAbsent(rel, Files.readAllBytes(p))
            }
        }
        return byName.entries.map { it.key to it.value }.sortedBy { it.first }
    }
}

/** Signals an explicit user cancellation at a phase boundary. */
class CancellationException : RuntimeException("conversion cancelled")

/**
 * Exception handler for the Dex2jar translate phase: rethrows in strict mode,
 * collects diagnostics otherwise so they surface in the engine report.
 */
class CollectingExceptionHandler(private val options: ConversionOptions) : DexExceptionHandler {
    private val collected = java.util.Collections.synchronizedList(ArrayList<String>())
    private val delegate = options.exceptionHandler
    // The delegate (e.g. the CLI's Baksmali error collector) is not thread-safe and
    // per-dex translation may run concurrently, so serialize calls into it.
    private val delegateLock = Any()

    fun warnings(): List<String> = ArrayList(collected)

    override fun handleFileException(e: Exception) {
        synchronized(delegateLock) { delegate?.handleFileException(e) }
        if (options.strict) throw e
        collected.add("file: ${e.message ?: e.javaClass.simpleName}")
    }

    override fun handleMethodTranslateException(
        method: Method,
        methodNode: DexMethodNode,
        mv: MethodVisitor,
        e: Exception,
    ) {
        synchronized(delegateLock) { delegate?.handleMethodTranslateException(method, methodNode, mv, e) }
        if (options.strict) throw e
        collected.add("${method.getOwner()}.${method.getName()}${method.getDesc()}: ${e.message ?: e.javaClass.simpleName}")
    }
}
