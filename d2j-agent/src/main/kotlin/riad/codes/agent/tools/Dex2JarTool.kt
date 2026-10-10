package riad.codes.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import riad.codes.agent.AgentTool
import riad.codes.agent.ProgressReporter
import riad.codes.agent.ToolArguments
import riad.codes.agent.ToolParameter
import riad.codes.agent.ToolResult
import riad.codes.agent.ToolSpec
import riad.codes.d2j.engine.Cancellation
import riad.codes.d2j.engine.ConversionOptions
import riad.codes.d2j.engine.DexEngine
import riad.codes.d2j.engine.EnginePhase
import riad.codes.d2j.engine.ProgressListener
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Convert a `.dex` or `.apk` into a `.jar` of Java class files.
 *
 * The conversion runs through the modern [DexEngine], so the agent gets the
 * engine's guarantees for free: atomic publish (a failed run never corrupts an
 * existing jar), input limits, output validation, per-class progress, and
 * cooperative cancellation when the job's coroutine is cancelled.
 */
class Dex2JarTool : AgentTool {

    override val spec = ToolSpec(
        name = "dex2jar",
        description = "Convert a .dex or .apk file into a .jar of Java .class files.",
        parameters = listOf(
            ToolParameter("input", "string", "Path to the input .dex or .apk file.", required = true),
            ToolParameter("output", "string", "Path of the output .jar (default: <input>-dex2jar.jar)."),
            ToolParameter("debugInfo", "boolean", "Translate debug info (default: false).", default = "false"),
            ToolParameter("reuseReg", "boolean", "Reuse registers while generating classes.", default = "false"),
            ToolParameter("skipExceptions", "boolean", "Skip exception handling in code.", default = "false"),
        ),
    )

    override suspend fun run(args: ToolArguments, reporter: ProgressReporter): ToolResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val input = File(args.string("input", required = true)!!)
            require(input.isFile) { "input not found: ${input.path}" }

            val debugInfo = args.boolean("debugInfo", false)
            val reuseReg = args.boolean("reuseReg", false)
            val skipExceptions = args.boolean("skipExceptions", false)

            val output = args.string("output")?.let(::File)
                ?: File(input.parentFile ?: File("."), "${input.nameWithoutExtension}-dex2jar.jar")

            reporter.started(
                "converting ${input.name} -> ${output.name}",
                mapOf("input" to input.path, "output" to output.path),
            )

            val bytes = Files.readAllBytes(input.toPath())
            val job: Job? = coroutineContext[Job]
            val listener = object : ProgressListener {
                override fun onPhase(phase: EnginePhase, detail: String) {
                    reporter.progress("${phase.name.lowercase()}: $detail")
                }

                override fun onClass(className: String, index: Int, total: Int) {
                    reporter.item(className, index + 1, total)
                }
            }
            val options = ConversionOptions(
                reuseReg = reuseReg,
                debugInfo = debugInfo,
                skipExceptions = skipExceptions,
                listener = listener,
                cancellation = Cancellation.Token { job?.isActive == false },
            )

            val report = DexEngine.convert(bytes, output.toPath(), options)

            val classes = listClasses(output)
            val payload = mapOf(
                "input" to input.path,
                "output" to output.path,
                "outputSize" to output.length(),
                "classCount" to classes.size,
                "dexVersion" to report.dexVersion,
                "dexEntryNames" to report.dexEntryNames,
                "duplicateClasses" to report.duplicateClasses,
                "warnings" to report.warnings,
            )
            reporter.completed("wrote ${classes.size} classes to ${output.name}", payload)
            ToolResult(
                jobId = reporter.jobId,
                tool = spec.name,
                success = true,
                output = payload,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

    private fun listClasses(jar: File): List<String> =
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .sorted()
                .toList()
        }
}
