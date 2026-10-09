package riad.codes.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import riad.codes.agent.AgentTool
import riad.codes.agent.ProgressReporter
import riad.codes.agent.ToolArguments
import riad.codes.agent.ToolParameter
import riad.codes.agent.ToolResult
import riad.codes.agent.ToolSpec
import riad.codes.d2j.smali.Baksmali
import java.io.File
import java.nio.file.Files

/** Disassemble a `.dex` into smali source files. */
class BaksmaliTool : AgentTool {

    override val spec = ToolSpec(
        name = "baksmali",
        description = "Disassemble a .dex file into smali source files.",
        parameters = listOf(
            ToolParameter("input", "string", "Path to the input .dex file.", required = true),
            ToolParameter("outputDir", "string", "Directory to write smali into (default: <input>-smali)."),
            ToolParameter("noDebug", "boolean", "Drop debug info (.line/.local).", default = "false"),
            ToolParameter("useLocals", "boolean", "Emit .locals instead of .register.", default = "false"),
            ToolParameter("noParameterRegisters", "boolean", "Use v<n> instead of p<n> for parameters.", default = "false"),
        ),
    )

    override suspend fun run(args: ToolArguments, reporter: ProgressReporter): ToolResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val input = File(args.string("input", required = true)!!)
            require(input.isFile) { "input not found: ${input.path}" }

            val outputDir = args.string("outputDir")?.let(::File)
                ?: File(input.parentFile ?: File("."), "${input.nameWithoutExtension}-smali")

            reporter.started("disassembling ${input.name}", mapOf("outputDir" to outputDir.path))
            outputDir.mkdirs()

            var baksmali = Baksmali.from(Files.readAllBytes(input.toPath()))
            if (args.boolean("noDebug")) baksmali = baksmali.noDebug()
            if (args.boolean("useLocals")) baksmali = baksmali.useLocals()
            if (args.boolean("noParameterRegisters")) baksmali = baksmali.noParameterRegisters()

            baksmali.to(outputDir)

            val files = outputDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".smali") }
                .map { it.nameWithoutExtension }
                .sorted()
                .toList()
            files.forEachIndexed { i, name -> reporter.item(name, i + 1, files.size) }

            val payload = mapOf(
                "input" to input.path,
                "outputDir" to outputDir.path,
                "smaliFileCount" to files.size,
            )
            reporter.completed("wrote ${files.size} smali files", payload)
            ToolResult(
                jobId = reporter.jobId,
                tool = spec.name,
                success = true,
                output = payload,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
}
