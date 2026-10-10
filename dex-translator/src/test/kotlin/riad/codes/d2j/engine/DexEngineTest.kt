package riad.codes.d2j.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.io.TempDir
import riad.codes.d2j.DexConstants
import riad.codes.d2j.reader.DexFileReader
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
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

    private fun putIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /**
     * A minimal, structurally valid DEX 041 standalone file: 0x78-byte header with
     * container_size/header_offset, no code, and a one-entry map list. Exercises
     * the V41 header path (header_size == 0x78) without needing a full rewriter.
     */
    private fun minimalDex041(): ByteArray {
        val mapOff = 0x78
        val fileSize = 0x88
        val bytes = ByteArray(fileSize)
        "dex\n041\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0)
        putIntLE(bytes, 0x20, fileSize)   // file_size
        putIntLE(bytes, 0x24, 0x78)       // header_size
        putIntLE(bytes, 0x28, 0x12345678) // endian_tag
        putIntLE(bytes, 0x34, mapOff)     // map_off
        putIntLE(bytes, 0x68, 0x10)       // data_size
        putIntLE(bytes, 0x6c, mapOff)     // data_off
        putIntLE(bytes, 0x70, fileSize)   // container_size
        putIntLE(bytes, 0x74, 0)          // header_offset (standalone)
        putIntLE(bytes, mapOff, 1)        // map_size = 1
        putIntLE(bytes, mapOff + 8, 1)    // item size
        putIntLE(bytes, mapOff + 12, 0)   // item offset
        return bytes
    }

    // ---------------- happy paths ----------------

    @Test
    fun convertsRealDex(@TempDir tmp: Path) {
        val out = tmp.resolve("out.jar")
        val report = DexEngine.convert(sampleDex, out, opts())
        assertTrue(Files.exists(out), "output jar should exist")
        assertTrue(report.classCount > 0, "should have translated classes")
        assertEquals(1, report.inputDexCount)
        assertTrue(report.warnings.isEmpty(), "clean fixture should produce no warnings: ${report.warnings}")
        assertTrue(report.complete)
        assertEquals(listOf("-"), report.dexEntryNames)
        SafeOutput.validateStructure(Files.readAllBytes(out), emptySet())
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
    fun multidexOrderingIsNumeric(@TempDir tmp: Path) {
        // classes10.dex must sort AFTER classes2.dex (Android order), not before (lexicographic).
        val apk = zipOf(
            "classes10.dex" to sampleDex,
            "classes2.dex" to sampleDex,
            "classes.dex" to sampleDex,
        )
        val report = DexEngine.convert(apk, tmp.resolve("out.jar"), opts())
        assertEquals(listOf("classes.dex", "classes2.dex", "classes10.dex"), report.dexEntryNames)
        assertEquals(3, report.inputDexCount)
        // duplicates across dex files are reported, never silently dropped
        assertTrue(report.duplicateClasses.isNotEmpty(), "same class in 3 dexes must be reported")
    }

    @Test
    fun multidexIndexMath() {
        assertEquals(1, DexInput.multidexIndex("classes.dex"))
        assertEquals(2, DexInput.multidexIndex("classes2.dex"))
        assertEquals(10, DexInput.multidexIndex("classes10.dex"))
        assertEquals(89, DexInput.multidexIndex("classes89.dex"))
    }

    // ---------------- input rejection ----------------

    @Test
    fun refusesGarbageInput(@TempDir tmp: Path) {
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert("not a dex".toByteArray(), tmp.resolve("o.jar"), opts())
        }
        assertEquals(EnginePhase.DISCOVERY, e.phase)
    }

    @Test
    fun refusesEmptyInput(@TempDir tmp: Path) {
        assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(ByteArray(0), tmp.resolve("o.jar"), opts())
        }
    }

    @Test
    fun refusesZipWithoutDex(@TempDir tmp: Path) {
        val zip = zipOf("foo.txt" to "hello".toByteArray())
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(zip, tmp.resolve("o.jar"), opts())
        }
        assertTrue(e.message.orEmpty().contains("classes*.dex"), "error should explain the problem: ${e.message}")
    }

    @Test
    fun refusesUnsupportedDexVersion(@TempDir tmp: Path) {
        // 041 is now supported; a version beyond the highest supported (no DEX 042+ exists
        // in ART) must be rejected with a message naming it.
        val patched = sampleDex.copyOf()
        patched[4] = '0'.code.toByte()
        patched[5] = '4'.code.toByte()
        patched[6] = '2'.code.toByte()
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(patched, tmp.resolve("o.jar"), opts())
        }
        assertTrue(e.message.orEmpty().contains("042"), "error should name the version: ${e.message}")

        // CheckClassAdapter must fail
        val truncated = byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), // magic
            0x00, 0x00, 0x00, 0x34, // minor 0, major 52
            0x00, 0x0A, // constant pool count = 10 but no data follows
        )
        val t = assertThrows(IllegalStateException::class.java) {
            SafeOutput.validate(zipOf("B.class" to truncated), setOf("B.class"), ValidationLevel.BYTECODE)
        }
        assertTrue(t.message?.contains("verification failed") == true, "error should explain the problem: ${t.message}")
    }

    @Test
    fun refusesInvalidMagicVersionDigits(@TempDir tmp: Path) {
        val patched = sampleDex.copyOf()
        patched[4] = 'X'.code.toByte()
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(patched, tmp.resolve("o.jar"), opts())
        }
        assertTrue(e.message.orEmpty().contains("magic"), "error should explain the magic problem: ${e.message}")
    }

    // ---------------- DEX 041 (container header) ----------------

    @Test
    fun convertsDex041Standalone(@TempDir tmp: Path) {
        // A standalone DEX 041 file is a container with header_offset == 0, so its
        // container-relative offsets equal file-relative offsets. The pipeline must
        // accept version 041 and still translate classes.
        val patched = sampleDex.copyOf()
        patched[4] = '0'.code.toByte()
        patched[5] = '4'.code.toByte()
        patched[6] = '1'.code.toByte()
        val report = DexEngine.convert(patched, tmp.resolve("out.jar"), opts())
        assertEquals(DexConstants.DEX_041, report.dexVersion)
        assertTrue(report.classCount > 0, "041 input should still translate classes")
    }

    @Test
    fun dexReaderParsesDex041StandaloneHeader() {
        val reader = DexFileReader(minimalDex041())
        assertEquals(DexConstants.DEX_041, reader.dexVersion)
        assertEquals(0x88, reader.containerSize)
        assertEquals(0, reader.headerOffset)
        assertTrue(reader.classNames.isEmpty())
    }

    @Test
    fun rejectsDex041ContainerEntryWithNonZeroHeaderOffset(@TempDir tmp: Path) {
        val patched = sampleDex.copyOf()
        patched[4] = '0'.code.toByte()
        patched[5] = '4'.code.toByte()
        patched[6] = '1'.code.toByte()
        putIntLE(patched, 0x24, 0x78)                // header_size = 0x78
        putIntLE(patched, 0x70, patched.size + 0x78) // container_size big enough
        putIntLE(patched, 0x74, 0x78)                // header_offset != 0
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(patched, tmp.resolve("o.jar"), opts())
        }
        assertTrue(
            e.message.orEmpty().contains("header_offset"),
            "multi-entry container must be rejected with an actionable message: ${e.message}",
        )
    }

    // ---------------- limits ----------------

    @Test
    fun honoursCancellation(@TempDir tmp: Path) {
        val cancelled = Cancellation.Token { true }
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(sampleDex, tmp.resolve("o.jar"), ConversionOptions(cancellation = cancelled))
        }
        assertFalse(Files.exists(tmp.resolve("o.jar")))
        assertTrue(e.message.orEmpty().contains("cancel"))
    }

    @Test
    fun cancelsMidTranslation(@TempDir tmp: Path) {
        // cancel after the first class has been announced
        val seen = AtomicInteger()
        val cancel = Cancellation.Token { seen.get() >= 1 }
        val listener = object : ProgressListener {
            override fun onPhase(phase: EnginePhase, detail: String) = Unit
            override fun onClass(className: String, index: Int, total: Int) {
                seen.incrementAndGet()
            }
        }
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(sampleDex, tmp.resolve("o.jar"), ConversionOptions(cancellation = cancel, listener = listener))
        }
        assertFalse(Files.exists(tmp.resolve("o.jar")), "cancelled run must not publish")
        assertTrue(seen.get() < 10, "cancellation should stop early (saw ${seen.get()} classes)")
        // staging directory cleanup: no d2j-engine-* dirs left behind
        val leftovers = Files.list(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))
            .use { s -> s.filter { it.fileName.toString().startsWith("d2j-engine-") }.count() }
        assertTrue(leftovers == 0L, "staging dirs must be cleaned up (found $leftovers)")
    }

    @Test
    fun honoursInputSizeLimit(@TempDir tmp: Path) {
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(sampleDex, tmp.resolve("o.jar"), ConversionOptions(maxInputBytes = 16))
        }
        assertEquals(EnginePhase.DISCOVERY, e.phase)
    }

    @Test
    fun honoursDecompressionLimit(@TempDir tmp: Path) {
        // zip holding a valid dex but with a per-entry decompression cap of 16 bytes
        val apk = zipOf("classes.dex" to sampleDex)
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(apk, tmp.resolve("o.jar"), ConversionOptions(maxDexEntryBytes = 16))
        }
        assertTrue(e.message.orEmpty().contains("limit"), "error should name the limit: ${e.message}")
    }

    @Test
    fun honoursZipEntryCap(@TempDir tmp: Path) {
        val entries = (1..20).map { "file$it.txt" to byteArrayOf(1) }
        val apk = zipOf("classes.dex" to sampleDex, *entries.toTypedArray())
        val e = assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert(apk, tmp.resolve("o.jar"), ConversionOptions(maxZipEntries = 10))
        }
        assertTrue(e.message.orEmpty().contains("entries"), "error should mention entries: ${e.message}")
    }

    // ---------------- determinism / progress / safety ----------------

    @Test
    fun computeFramesProducesValidClasses(@TempDir tmp: Path) {
        // COMPUTE_FRAMES with the SafeClassWriter fallback must not fail the run and
        // must still pass the engine's default bytecode validation.
        val out = tmp.resolve("frames.jar")
        val report = DexEngine.convert(sampleDex, out, ConversionOptions(computeFrames = true))
        assertTrue(report.classCount > 0)
        SafeOutput.validateStructure(Files.readAllBytes(out), emptySet())
    }

    @Test
    fun parallelMultidexMatchesSequential(@TempDir tmp: Path) {
        val apk = zipOf(
            "classes.dex" to sampleDex,
            "classes2.dex" to sampleDex,
            "classes3.dex" to sampleDex,
        )
        val seq = tmp.resolve("seq.jar")
        val par = tmp.resolve("par.jar")
        DexEngine.convert(apk, seq, ConversionOptions(parallelism = 1))
        DexEngine.convert(apk, par, ConversionOptions(parallelism = 4))
        assertTrue(
            Files.readAllBytes(seq).contentEquals(Files.readAllBytes(par)),
            "parallel multidex translation must match sequential output byte-for-byte",
        )
    }

    @Test
    fun jarEntriesUseAFixedTimestamp() {
        // Regression: the manifest/directory entries used the current time while
        // class entries were pinned to epoch 0, so identical inputs produced jars
        // that differed across JVM runs. Every entry must share one timestamp.
        val built = SafeOutput.buildJar(listOf("A.class" to byteArrayOf(1, 2, 3)))
        val times = mutableSetOf<Long>()
        java.util.zip.ZipInputStream(built.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                times.add(entry.time)
                zis.closeEntry()
            }
        }
        assertEquals(1, times.size, "all jar entries must share one fixed timestamp (saw $times)")
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
        val classesSeen = AtomicInteger()
        val listener = object : ProgressListener {
            override fun onPhase(phase: EnginePhase, detail: String) {
                seen.add(phase)
            }

            override fun onClass(className: String, index: Int, total: Int) {
                classesSeen.incrementAndGet()
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
        assertTrue(classesSeen.get() > 0, "per-class progress must fire")
    }

    @Test
    fun preservesPreviousOutputOnFailure(@TempDir tmp: Path) {
        val out = tmp.resolve("o.jar")
        DexEngine.convert(sampleDex, out, opts())
        val good = Files.readAllBytes(out)
        assertThrows(DexEngine.EngineException::class.java) {
            DexEngine.convert("garbage".toByteArray(), out, opts())
        }
        assertTrue(Files.readAllBytes(out).contentEquals(good), "previous output must survive a failed run")
    }

    @Test
    fun structuralValidationRejectsBadClassMagic() {
        assertThrows(IllegalStateException::class.java) {
            SafeOutput.validateStructure(zipOf("B.class" to "not a class".toByteArray()), emptySet())
        }
    }

    @Test
    fun bytecodeValidationRejectsTruncatedClass() {
        // valid magic + version but truncated body: structure may pass magic check,
        // CheckClassAdapter must fail
        val truncated = byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), // magic
            0x00, 0x00, 0x00, 0x34, // minor 0, major 52
            0x00, 0x0A, // constant pool count = 10 but no data follows
        )
        val throwable = assertThrows(IllegalStateException::class.java) {
            SafeOutput.validate(zipOf("B.class" to truncated), setOf("B.class"), ValidationLevel.BYTECODE)
        }
        assertTrue(throwable.message?.contains("verification failed") == true, "error should explain the problem: ${throwable.message}")
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

    @Test
    fun concurrentConversionsAreIsolated(@TempDir tmp: Path) {
        val outputs = (1..4).map { tmp.resolve("out$it.jar") }
        val threads = outputs.mapIndexed { i, out ->
            Thread {
                DexEngine.convert(sampleDex, out, opts())
            }.also { it.name = "conv-$i" }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }
        val baseline = Files.readAllBytes(outputs[0])
        for (out in outputs) {
            assertTrue(Files.exists(out), "$out must exist")
            assertTrue(Files.readAllBytes(out).contentEquals(baseline), "$out must equal baseline")
        }
    }
}
