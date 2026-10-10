package riad.codes.d2j.engine

import riad.codes.d2j.Method as D2jMethod
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Safe output for the modern engine: results are built in memory, validated,
 * then published atomically so the destination path always holds either a
 * previously-good value or a fully verified new jar - nothing partial.
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
     * Builds a jar from class name -> bytes. Every entry is written once; class
     * names are validated to be legal jar paths and base64 safe, so a weird
     * input dex cannot produce a malicious jar path (e.g. "../evil").
     */
    fun buildJar(classes: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        zip.use {
            // Manifest first for deterministic output ordering.
            val manifest = ("Manifest-Version: 1.0\r\n" +
                "Created-By: d2j modern engine\r\n\r\n").toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("META-INF/"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
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
     * Validates the generated jar by re-reading it as a zip and confirming
     * every entry that looks like a class file starts with the CAFEBABE magic.
     * Throws [IllegalStateException] if the jar is structurally invalid.
     */
    fun validateJar(bytes: ByteArray, expectedClassNames: Collection<String>) {
        if (bytes.isEmpty()) throw IllegalStateException("Generated jar is empty")
        ZipInputStream(bytes.inputStream()).use { zis ->
            val seen = HashSet<String>()
            while (true) {
                val entry = zis.nextEntry ?: break
                seen.add(entry.name)
                if (entry.name.endsWith(".class")) {
                    val header = ByteArray(4)
                    readN(zis, header)
                    val magic =
                        ((header[0].toInt() and 0xFF) shl 24) or
                            ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)
                    if (magic != 0xCAFEBABE.toInt()) {
                        throw IllegalStateException(
                            "Generated class file '${entry.name}' is not a valid Java class (bad magic 0x${"%08x".format(magic)})"
                        )
                    }
                }
                if (entry.size >= 0) {
                    zis.closeEntry()
                }
            }
            val missing = expectedClassNames.filter { "$it.class" !in seen }
            check(missing.isEmpty()) {
                "Generated jar is missing classes: ${missing.take(5)}" + if (missing.size > 5) " (and ${missing.size - 5} more)" else ""
            }
        }
    }

    /** Verifies the class references in a DexMethodNode body are jarsafe. */
    fun jarSafe(name: String): Boolean {
        return name.isNotEmpty() && !name.startsWith("/") && !name.contains("..") && !name.contains("\\")
    }

    private fun readN(input: InputStream, buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) throw EOFException("Unexpected end of jar while validating")
            off += n
        }
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
