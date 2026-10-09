package riad.codes.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import riad.codes.agent.AgentTool
import riad.codes.agent.ProgressReporter
import riad.codes.agent.ToolArguments
import riad.codes.agent.ToolParameter
import riad.codes.agent.ToolResult
import riad.codes.agent.ToolSpec
import riad.codes.d2j.dex.BaseDexExceptionHandler
import riad.codes.d2j.dex.Dex2jar
import riad.codes.d2j.reader.MultiDexFileReader
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Convert a `.dex` or `.apk` into a `.jar` of Java class files.
 *
 * Progress is streamed while the conversion runs: a heartbeat keeps the agent
 * informed, and every generated class is announced as the output archive is
 * enumerated.
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

            val reader = MultiDexFileReader.open(Files.readAllBytes(input.toPath()))
            val handler = BaseDexExceptionHandler()

            coroutineScope {
                val heartbeat = launch {
                    var tick = 0
                    while (isActive) {
                        delay(500)
                        tick++
                        reporter.progress("converting… (${tick * 500} ms)")
                    }
                }
                try {
                    Files.deleteIfExists(output.toPath())
                    Dex2jar.from(reader)
                        .withExceptionHandler(handler)
                        .reUseReg(reuseReg)
                        .topoLogicalSort()
                        .skipDebug(!debugInfo)
                        .skipExceptions(skipExceptions)
                        .to(output.toPath())
                } finally {
                    heartbeat.cancel()
                }
            }

            val classes = listClasses(output)
            classes.forEachIndexed { i, name ->
                reporter.item(name, i + 1, classes.size)
            }

            val payload = mapOf(
                "input" to input.path,
                "output" to output.path,
                "outputSize" to output.length(),
                "classCount" to classes.size,
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
