package riad.codes.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import riad.codes.agent.tools.Dex2JarTool
import riad.codes.agent.tools.DexInfoTool
import java.io.File
import java.nio.file.Path

class DexToolsTest {

    private val sampleDex = File("../dex-translator/src/test/resources/dexes/dex038.dex")

    @Test
    fun `dexInfo lists classes and streams item events`() = runBlocking {
        val tool = DexInfoTool()
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter("t1", tool.spec.name, listOf(ProgressListener { events.add(it) }))

        val result = tool.run(
            ToolArguments(mapOf("input" to sampleDex.absolutePath)),
            reporter,
        )

        assertTrue(result.success, result.error)
        val classCount = (result.output["classCount"] as Number).toInt()
        assertTrue(classCount > 0, "expected classes in the sample dex")

        assertEquals(ProgressPhase.STARTED, events.first().phase)
        assertEquals(ProgressPhase.COMPLETED, events.last().phase)
        assertTrue(events.count { it.phase == ProgressPhase.ITEM } == classCount)
        assertTrue(events.any { it.percent == 100.0 })
    }

    @Test
    fun `dex2jar writes a jar with class files`(@TempDir temp: Path) = runBlocking {
        val tool = Dex2JarTool()
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter("t2", tool.spec.name, listOf(ProgressListener { events.add(it) }))
        val output = temp.resolve("out.jar").toFile()

        val result = tool.run(
            ToolArguments(
                mapOf(
                    "input" to sampleDex.absolutePath,
                    "output" to output.absolutePath,
                ),
            ),
            reporter,
        )

        assertTrue(result.success, result.error)
        assertTrue(output.isFile && output.length() > 0, "output jar should be written")
        val classCount = (result.output["classCount"] as Number).toInt()
        assertTrue(classCount > 0, "expected generated classes")
        assertEquals(ProgressPhase.COMPLETED, events.last().phase)
    }
}
