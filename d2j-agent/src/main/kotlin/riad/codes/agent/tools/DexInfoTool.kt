package riad.codes.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import riad.codes.agent.AgentTool
import riad.codes.agent.ProgressReporter
import riad.codes.agent.ToolArguments
import riad.codes.agent.ToolParameter
import riad.codes.agent.ToolResult
import riad.codes.agent.ToolSpec
import riad.codes.d2j.node.DexFileNode
import riad.codes.d2j.reader.DexFileReader
import riad.codes.d2j.reader.MultiDexFileReader
import java.io.File
import java.nio.file.Files

/** Inspect a .dex/.apk and stream the class inventory. */
class DexInfoTool : AgentTool {

    override val spec = ToolSpec(
        name = "dexInfo",
        description = "List the classes and dex version inside a .dex or .apk file.",
        parameters = listOf(
            ToolParameter("input", "string", "Path to the .dex or .apk file.", required = true),
        ),
    )

    override suspend fun run(args: ToolArguments, reporter: ProgressReporter): ToolResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val input = File(args.string("input", required = true)!!)
            require(input.isFile) { "input not found: ${input.path}" }

            reporter.started("reading ${input.name}")
            val bytes = Files.readAllBytes(input.toPath())
            val reader = MultiDexFileReader.open(bytes)

            val node = DexFileNode()
            reader.accept(node, DexFileReader.SKIP_CODE or DexFileReader.SKIP_DEBUG)

            val names = node.clzs.map { it.className }
            names.forEachIndexed { i, name ->
                reporter.item(name, i + 1, names.size)
            }

            val payload = mapOf(
                "input" to input.path,
                "dexVersion" to node.dexVersion,
                "classCount" to names.size,
                "classes" to names,
            )
            reporter.completed("found ${names.size} classes", payload)
            ToolResult(
                jobId = reporter.jobId,
                tool = spec.name,
                success = true,
                output = payload,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
}
