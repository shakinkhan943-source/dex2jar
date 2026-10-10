package riad.codes.d2j.engine

import org.objectweb.asm.MethodVisitor
import riad.codes.d2j.Method
import riad.codes.d2j.dex.Dex2jar
import riad.codes.d2j.dex.DexExceptionHandler
import riad.codes.d2j.node.DexMethodNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The modern engine: one call converts any DEX / APK / ZIP into a validated jar
 * with atomic publish, phase progress, cancellation and a structured report.
 */
object DexEngine {

    /** Structured result of a conversion run. */
    data class Report(
        val inputDexCount: Int,
        val classCount: Int,
        val duplicateClasses: List<String>,
        val dexVersion: Int,
        val durationMillis: Long,
        val publishedTo: Path,
        val bytesWritten: Long,
    )

    class EngineException(val phase: EnginePhase, cause: Throwable) :
        RuntimeException("Engine failed during $phase: ${cause.message}", cause)

    /**
     * Convert [data] (a DEX file or ZIP/APK container) into a jar at [output].
     *
     * Returns the [Report]; on any failure the destination keeps its previous
     * content and all temporary files are removed.
     */
    @Throws(EngineException::class)
    fun convert(data: ByteArray, output: Path, options: ConversionOptions): Report {
        if (options.cancellation.isCancelled()) {
            throw EngineException(EnginePhase.DISCOVERY, CancellationException())
        }
        val started = System.currentTimeMillis()
        try {
            return convertInternal(data, output, options, started)
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
        options.listener.onPhase(
            EnginePhase.VALIDATION,
            "${discovery.dexCount} dex, ${discovery.classNames.size} classes, version=${discovery.dexVersion}"
        )
        if (options.cancellation.isCancelled()) {
            throw CancellationException()
        }

        // 1. Translate into a staging temp dir.
        val staging = Files.createTempDirectory("d2j-engine-")
        try {
            options.listener.onPhase(EnginePhase.TRANSLATE, "staging=${staging.fileName}")
            val handler = CollectingExceptionHandler(options)
            Dex2jar.from(discovery.reader)
                .withExceptionHandler(handler)
                .reUseReg(options.reuseReg)
                .topoLogicalSort(options.topologicalSort)
                .skipDebug(!options.debugInfo)
                .optimizeSynchronized(options.optimizeSynchronized)
                .printIR(options.printIR)
                .noCode(options.noCode)
                .skipExceptions(false)
                .to(staging)
            if (options.strict && handler.failureCount > 0) {
                throw IllegalStateException(
                    "Conversion had ${handler.failureCount} recoverable errors; strict mode refuses to publish."
                )
            }
            options.listener.onPhase(EnginePhase.WRITE, "staging complete")

            // 2. Collect class files and build the jar in memory.
            val classes = collectClassFiles(staging)
            val built = SafeOutput.buildJar(classes)
            options.listener.onPhase(EnginePhase.PUBLISH, "validating ${classes.size} entries")
            // Only validate classes that actually produce .class entries; dex
            // class names are 'Lfoo/Bar;' while jar entries are 'foo/Bar.class'.
            SafeOutput.validateJar(
                built,
                discovery.classNames
                    .filter { it.startsWith("L") && it.endsWith(";") && !it.contains('/') == false }
                    .map { it.removeSurrounding("L", ";") + ".class" },
            )

            if (options.cancellation.isCancelled()) {
                throw CancellationException()
            }

            // 3. Atomic publish.
            publish(built, output, options)
            return Report(
                inputDexCount = discovery.dexCount,
                classCount = discovery.classNames.size,
                duplicateClasses = discovery.duplicates,
                dexVersion = discovery.dexVersion,
                durationMillis = System.currentTimeMillis() - started,
                publishedTo = output,
                bytesWritten = built.size.toLong(),
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
            if (Files.exists(absOutput)) {
                try {
                    Files.move(temp, absOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) {
                    Files.move(temp, absOutput, StandardCopyOption.REPLACE_EXISTING)
                }
            } else {
                try {
                    Files.move(temp, absOutput, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: IOException) {
                    Files.move(temp, absOutput)
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        options.listener.onPhase(EnginePhase.PUBLISH, "-> $absOutput")
    }

    private fun collectClassFiles(staging: Path): List<Pair<String, ByteArray>> {
        val results = ArrayList<Pair<String, ByteArray>>()
        Files.walk(staging).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                val rel = staging.relativize(p).toString().replace('\\', '/')
                results.add(rel to Files.readAllBytes(p))
            }
        }
        // Deterministic jar layout regardless of filesystem enumeration order.
        results.sortBy { it.first }
        return results
    }
}

/** Signals an explicit user cancellation at a phase boundary. */
class CancellationException : RuntimeException("conversion cancelled")

/**
 * Exception handler for the Dex2jar translate phase: rethrows in strict mode,
 * counts otherwise so partial results are visible in the engine report.
 */
class CollectingExceptionHandler(private val options: ConversionOptions) : DexExceptionHandler {
    @Volatile
    var failureCount: Int = 0
        private set

    override fun handleFileException(e: Exception) {
        if (options.strict) throw e
        failureCount++
    }

    override fun handleMethodTranslateException(method: Method, methodNode: DexMethodNode, mv: MethodVisitor, e: Exception) {
        if (options.strict) throw e
        failureCount++
    }
}
