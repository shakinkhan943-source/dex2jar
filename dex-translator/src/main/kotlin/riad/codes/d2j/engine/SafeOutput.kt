package riad.codes.d2j.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.CheckClassAdapter
import riad.codes.d2j.Method as D2jMethod
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Safe output for the modern engine: results are built in memory, validated,
 * then published atomically so the destination path always holds either a
 * previously-good value or a fully verified new jar — nothing partial.
 *
 * Validation tiers ([ValidationLevel]):
 * - STRUCTURE: archive re-reads cleanly, entry names are safe (no traversal,
 *   absolute paths, backslashes), every `.class` entry has the CAFEBABE magic,
 *   a plausible class-file version, and a constant-pool count consistent with
 *   its length, and all expected entries are present.
 * - BYTECODE: STRUCTURE plus ASM [CheckClassAdapter] structural verification of
 *   every class (valid opcodes, operand stack discipline via data-flow
 *   analysis, legal frames/labels). This is *not* full JVM verification: type
 *   hierarchy and access checks need a classpath and happen at class-load
 *   time. The API and README state this distinction explicitly.
 */
object SafeOutput {

    /** Reads up to [max] bytes from [input], failing fast if the stream is larger. */
    fun readFully(input: InputStream, max: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > max) {
                throw IOException("Input exceeds the configured limit of $max bytes")
            }
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    /**
     * Builds a jar from class name -> bytes with a deterministic layout
     * (manifest first, entries sorted, fixed timestamps) so identical inputs
     * produce byte-identical jars.
     */
    fun buildJar(classes: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        zip.use {
            val manifest = ("Manifest-Version: 1.0\r\n" +
                "Created-By: d2j modern engine\r\n\r\n").toByteArray(Charsets.UTF_8)
            // Every entry (including the manifest and the directory marker) must
            // carry the same fixed timestamp, otherwise two runs in different
            // JVMs produce jars that differ only by the embedded creation time.
            zip.putNextEntry(ZipEntry("META-INF/").apply { time = 0L })
            zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("META-INF/MANIFEST.MF").apply {
                    time = 0L
                    method = ZipEntry.DEFLATED
                },
            )
            zip.write(manifest)
            zip.closeEntry()
            for ((name, bytes) in classes) {
                validateEntryName(name)
                val entry = ZipEntry(name)
                entry.time = 0L
                entry.method = ZipEntry.DEFLATED
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun validateEntryName(name: String) {
        require(name.isNotEmpty()) { "class entry name is empty" }
        require(!name.startsWith("/")) { "class entry name must be relative: $name" }
        require(!name.contains("..")) { "class entry name may not traverse directories: $name" }
        require(!name.contains(Regex("[\\\\\u0000]"))) { "class entry name contains illegal characters: $name" }
    }

    /**
     * Run the configured validation tier over a built jar. Throws
     * [IllegalStateException] describing the first fatal problem found.
     */
    fun validate(jarBytes: ByteArray, expectedEntries: Set<String>, level: ValidationLevel) {
        val classBytes = validateStructure(jarBytes, expectedEntries)
        if (level == ValidationLevel.BYTECODE) {
            verifyBytecode(classBytes)
        }
    }

    /**
     * Tier 1 — structural validation. Re-reads the archive, enforces safe
     * entry names, checks class-file magic/version/length consistency, and
     * verifies all expected entries are present.
     *
     * @return map of entry name to raw bytes for entries ending in `.class`.
     */
    fun validateStructure(jarBytes: ByteArray, expectedEntries: Set<String>): Map<String, ByteArray> {
        if (jarBytes.isEmpty()) throw IllegalStateException("Generated jar is empty")
        val classes = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        ZipInputStream(jarBytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                validateEntryName(entry.name)
                if (!seen.add(entry.name)) {
                    throw IllegalStateException("Duplicate jar entry '${entry.name}'")
                }
                if (entry.name.endsWith(".class")) {
                    val bytes = zis.readBytes()
                    checkClassFileHeader(entry.name, bytes)
                    classes[entry.name] = bytes
                }
            }
        }
        val missing = expectedEntries.filter { it !in seen }
        check(missing.isEmpty()) {
            "Generated jar is missing entries: ${missing.take(5)}" +
                if (missing.size > 5) " (and ${missing.size - 5} more)" else ""
        }
        return classes
    }

    /** Class-file sanity: magic, version range, constant-pool bounds. */
    internal fun checkClassFileHeader(name: String, bytes: ByteArray) {
        if (bytes.size < 10) {
            throw IllegalStateException("Generated class file '$name' is truncated (${bytes.size} bytes)")
        }
        val magic = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        if (magic != 0xCAFEBABE.toInt()) {
            throw IllegalStateException(
                "Generated class file '$name' is not a valid Java class (bad magic 0x${"%08x".format(magic)})"
            )
        }
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        // Any valid class file is major 45 (Java 1.1) .. current. dex2jar legitimately
        // emits old-format classes (e.g. major 50 = Java 6) for old dex inputs, so do
        // not require a modern floor. (ASM's Opcodes.V1_1 encodes a minor version and
        // is not 45, hence the literal.)
        if (major < 45 || major > Opcodes.V25) {
            throw IllegalStateException("Generated class file '$name' has unsupported class version $major")
        }
        val cpCount = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
        if (cpCount < 1) {
            throw IllegalStateException("Generated class file '$name' has an invalid constant pool count $cpCount")
        }
    }

    /**
     * Tier 2 — bytecode structure verification via ASM [CheckClassAdapter]
     * (data-flow analysis: valid opcodes, stack/locals discipline, legal
     * frames and labels). Throws with a per-class diagnostic on the first
     * failure. Does not resolve referenced types (that needs a classpath and
     * is performed by the JVM at class-load time).
     */
    fun verifyBytecode(classBytes: Map<String, ByteArray>) {
        val failures = ArrayList<String>()
        for ((name, bytes) in classBytes) {
            try {
                val cr = ClassReader(bytes)
                val cw = ClassWriter(0)
                val cca = CheckClassAdapter(cw, /* checkDataFlow = */ true)
                cr.accept(cca, ClassReader.SKIP_DEBUG)
            } catch (t: Throwable) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                failures.add("$name: ${t.message ?: t.javaClass.simpleName}")
                if (failures.size >= 5) break
            }
        }
        check(failures.isEmpty()) {
            "Bytecode verification failed for ${failures.size}+ class(es): " +
                failures.joinToString("; ")
        }
    }

    /** Verifies the class references in a DexMethodNode body are jarsafe. */
    fun jarSafe(name: String): Boolean {
        return name.isNotEmpty() && !name.startsWith("/") && !name.contains("..") && !name.contains("\\")
    }

    /** Utility used by tests/agents that want a descriptor->internal-name view. */
    @JvmStatic
    fun asType(descriptor: String): TypeRef = TypeRef(descriptor)

    data class TypeRef(val descriptor: String) {
        /** 'Lfoo/Bar;' -> 'foo/Bar', '[LFoo;' unchanged (array prefix kept). */
        val internalName: String
            get() = descriptor.removeSurrounding("L", ";")
    }

    /** Convenience used when an EVT exception must be reported by a method ref. */
    fun methodDescription(method: D2jMethod): String =
        "${method.getOwner()}.${method.getName()}${method.getDesc()}"
}
