package riad.codes.d2j.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexEngineTest {

    private val sampleDex: ByteArray by lazy {
        javaClass.getResourceAsStream("/dexes/dex040.dex")!!.readBytes()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun opts(cancel: Cancellation = Cancellation.NONE): ConversionOptions =
        ConversionOptions(reuseReg = false, cancellation = cancel)

    @Test
    fun convertsRealDex(@TempDir tmp: Path) {
        val out = tmp.resolve("out.jar")
        val report = DexEngine.convert(sampleDex, out, opts())
        assertTrue(Files.exists(out), "output jar should exist")
        assertTrue(report.classCount > 0, "should have translated classes")
        assertEquals(1, report.inputDexCount)
        SafeOutput.validateJar(Files.readAllBytes(out), emptyList())
    }

    @Test
    fun convertsApkWithSingleDex(@TempDir tmp: Path) {
        val apk = zipOf(
            "classes.dex" to sampleDex,
            "AndroidManifest.xml" to byteArrayOf(1),
            "resources.arsc" to byteArrayOf(2),
        )
        val out = tmp.resolve("out.jar")
        val report = DexEngine.convert(apk, out, opts())
        assertEquals(1, report.inputDexCount)
        assertTrue(Files.exists(out))
    }

    @Test
    fun refusesGarbageInput(@TempDir tmp: Path) {
        val e = assertThrows(DexEngine.EngineException::class.java)  {
            DexEngine.convert("not a dex".toByteArray(), tmp.resolve("o.jar"), opts())
        }
        assertEquals(EnginePhase.DISCOVERY, e.phase)
    }

    @Test
    fun refusesEmptyInput(@TempDir tmp: Path) {
        assertThrows(DexEngine.EngineException::class.java)  {
            DexEngine.convert(ByteArray(0), tmp.resolve("o.jar"), opts())
        }
    }

    @Test
    fun refusesZipWithoutDex(@TempDir tmp: Path) {
        val zip = zipOf("foo.txt" to "hello".toByteArray())
        val e = assertThrows(DexEngine.EngineException::class.java)  {
            DexEngine.convert(zip, tmp.resolve("o.jar"), opts())
        }
        assertTrue(e.message.orEmpty().contains("classes*.dex"), "error should explain the problem: ${e.message}")
    }

    @Test
    fun honoursCancellation(@TempDir tmp: Path) {
        val cancelled = Cancellation.Token { true }
        val e = assertThrows(DexEngine.EngineException::class.java)  {
            DexEngine.convert(sampleDex, tmp.resolve("o.jar"), ConversionOptions(cancellation = cancelled))
        }
        assertTrue(!Files.exists(tmp.resolve("o.jar")))
        assertTrue(e.message.orEmpty().contains("cancel"))
    }

    @Test
    fun honoursInputSizeLimit(@TempDir tmp: Path) {
        val e = assertThrows(DexEngine.EngineException::class.java)  {
            DexEngine.convert(sampleDex, tmp.resolve("o.jar"), ConversionOptions(maxInputBytes = 16))
        }
        assertEquals(EnginePhase.DISCOVERY, e.phase)
    }

    @Test
    fun deterministicOutputBytes(@TempDir tmp: Path) {
        val a = tmp.resolve("a.jar")
        val b = tmp.resolve("b.jar")
        DexEngine.convert(sampleDex, a, opts())
        DexEngine.convert(sampleDex, b, opts())
        assertTrue(
            Files.readAllBytes(a).contentEquals(Files.readAllBytes(b)),
            "identical inputs should produce byte-identical outputs",
        )
    }

    @Test
    fun progressListenerSeesAllPhases(@TempDir tmp: Path) {
        val seen = mutableListOf<EnginePhase>()
        val listener = object : ProgressListener {
            override fun onPhase(phase: EnginePhase, detail: String) {
                seen.add(phase)
            }
        }
        DexEngine.convert(sampleDex, tmp.resolve("o.jar"), ConversionOptions(listener = listener))
        assertEquals(
            listOf(
                EnginePhase.DISCOVERY,
                EnginePhase.VALIDATION,
                EnginePhase.TRANSLATE,
                EnginePhase.WRITE,
                EnginePhase.PUBLISH,
            ),
            seen.distinct().sortedBy { it.ordinal },
        )
    }

    @Test
    fun preservesPreviousOutputOnFailure(@TempDir tmp: Path) {
        val out = tmp.resolve("o.jar")
        DexEngine.convert(sampleDex, out, opts())
        val good = Files.readAllBytes(out)
        assertThrows(DexEngine.EngineException::class.java)  {
            DexEngine.convert("garbage".toByteArray(), out, opts())
        }
        assertTrue(Files.readAllBytes(out).contentEquals(good), "previous output must survive a failed run")
    }

    @Test
    fun jarValidationRejectsBadClassMagic() {
        assertThrows(IllegalStateException::class.java)  {
            SafeOutput.validateJar(zipOf("B.class" to "not a class".toByteArray()), emptyList())
        }
    }

    @Test
    fun jarEntryNameValidation() {
        val bad = listOf("/abs", "a/../b", "x\\y")
        for (b in bad) {
            try {
                SafeOutput.validateEntryName(b)
                throw AssertionError("entry '$b' must be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        SafeOutput.validateEntryName("com/example/Foo.class")
    }
}
