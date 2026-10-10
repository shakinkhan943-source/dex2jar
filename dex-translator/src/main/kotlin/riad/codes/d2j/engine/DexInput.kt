package riad.codes.d2j.engine

import riad.codes.d2j.DexConstants
import riad.codes.d2j.reader.BaseDexFileReader
import riad.codes.d2j.reader.DexFileReader
import riad.codes.d2j.reader.MultiDexFileReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Deterministic, limits-aware input discovery for the modern engine.
 *
 * Handles raw DEX files and ZIP/APK containers:
 * - multidex entries are ordered Android-style (classes.dex, classes2.dex, ...,
 *   classes10.dex — numeric, not lexicographic);
 * - every classes*.dex entry is decompressed through bounded readers that
 *   enforce per-entry and total decompression limits (zip-bomb guard);
 * - DEX headers are validated (magic + version gate) before translation;
 * - duplicate class names across DEX files are detected and reported, never
 *   silently dropped.
 */
object DexInput {

    data class Discovered(
        val reader: BaseDexFileReader,
        val dexCount: Int,
        /** Deterministic, de-duplicated list of class names that will be translated. */
        val classNames: List<String>,
        /** Class names found in more than one DEX file; only the first occurrence is translated. */
        val duplicates: List<String>,
        /** Highest DEX file version seen across all discovered DEX files. */
        val dexVersion: Int,
        /** Container entry names in processing order (single-dex inputs report ["-"]). */
        val entryNames: List<String>,
        /**
         * The individual per-dex readers in multidex order. Length 1 for a raw
         * DEX; one entry per `classes*.dex` for a container. The engine can
         * translate these concurrently and merge deterministically.
         */
        val readers: List<BaseDexFileReader>,
    )

    /** Matches root-level multidex entries only: classes.dex, classes2.dex, ... */
    private val MULTIDEX_ENTRY = Regex("^classes(\\d*)\\.dex$")

    fun discover(data: ByteArray, options: ConversionOptions): Discovered {
        if (data.size < 3) {
            throw IOException(
                "Input is too small (${data.size} bytes) to be a DEX or ZIP file; " +
                    "a valid DEX file starts with the magic bytes 'dex\\n' or a container with 'PK'."
            )
        }
        val magic3 = String(data, 0, 3, StandardCharsets.ISO_8859_1)
        val magic2 = String(data, 0, 2, StandardCharsets.ISO_8859_1)
        return when {
            magic3 == "dex" -> discoverSingleDex(data, options)
            magic2 == "PK" -> discoverZip(data, options)
            else -> throw IOException(
                "Input is not a DEX or ZIP file (file magic '${magic3.replace("\n", "\\n")}'). " +
                    "Expected a raw .dex file or a .zip/.apk container containing classes*.dex."
            )
        }
    }

    /** Read at most [max] bytes; fail fast if the stream holds more. */
    internal fun readBounded(input: InputStream, max: Long, what: String): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024L).toInt())
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) {
                throw IOException(
                    "$what exceeds the configured limit of $max bytes; " +
                        "raise the corresponding ConversionOptions limit if this input is expected."
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun discoverSingleDex(data: ByteArray, options: ConversionOptions): Discovered {
        val reader = openDexReader(data, "input")
        return Discovered(
            reader = reader,
            dexCount = 1,
            classNames = reader.classNames,
            duplicates = emptyList(),
            dexVersion = reader.dexVersion,
            entryNames = listOf("-"),
            readers = listOf(reader),
        )
    }

    /** Validate magic/version before handing bytes to the reader. */
    private fun openDexReader(data: ByteArray, name: String): DexFileReader {
        if (data.size < 8) {
            throw IOException("$name: truncated DEX header (${data.size} bytes)")
        }
        val versionDigits = String(data, 4, 3, StandardCharsets.ISO_8859_1)
        if (!versionDigits.all { it in '0'..'9' }) {
            throw IOException(
                "$name: invalid DEX magic/version " +
                    "'${String(data, 0, 8, StandardCharsets.ISO_8859_1).replace("\n", "\\n")}'"
            )
        }
        // The reader derives the version word as big-endian 'NNN' >> 8 from the
        // 8-byte magic: e.g. "040" -> ('0' << 16) | ('4' << 8) | '0'.
        val version = ((data[4].toInt() and 0xFF) shl 16) or
            ((data[5].toInt() and 0xFF) shl 8) or
            (data[6].toInt() and 0xFF)
        if (version > DexConstants.MAX_SUPPORTED_VERSION) {
            throw IOException(
                "$name: DEX version $versionDigits is not supported. DEX 041+ uses the container " +
                    "format (header 0x78, shared physical-file offsets) which this engine does not " +
                    "implement; supported versions: 035, 037-040."
            )
        }
        return DexFileReader(data)
    }

    private fun discoverZip(data: ByteArray, options: ConversionOptions): Discovered {
        // name -> decompressed bytes, in Android multidex order.
        val entries = ArrayList<Pair<String, ByteArray>>()
        var totalEntries = 0
        var totalDecompressed = 0L

        java.util.zip.ZipInputStream(data.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                totalEntries++
                if (totalEntries > options.maxZipEntries) {
                    throw IOException(
                        "Container holds more than ${options.maxZipEntries} entries; refusing to scan further. " +
                            "Increase ConversionOptions.maxZipEntries if this input is expected."
                    )
                }
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val m = MULTIDEX_ENTRY.matchEntire(entry.name)
                if (m != null) {
                    val remaining = options.maxTotalDecompressedBytes - totalDecompressed
                    if (remaining <= 0) {
                        throw IOException(
                            "Total decompressed classes*.dex bytes exceed the configured limit of " +
                                "${options.maxTotalDecompressedBytes}."
                        )
                    }
                    val bytes = readBounded(zis, minOf(options.maxDexEntryBytes, remaining), "entry '${entry.name}'")
                    totalDecompressed += bytes.size
                    entries.add(entry.name to bytes)
                    if (entries.size > options.maxDexCount) {
                        throw IOException(
                            "Container declares more than ${options.maxDexCount} classes*.dex entries " +
                                "(limit reached at '${entry.name}'). Increase ConversionOptions.maxDexCount if expected."
                        )
                    }
                }
                zis.closeEntry()
            }
        }

        if (entries.isEmpty()) {
            throw IOException(
                "Input is a ZIP/APK container but no root-level 'classes*.dex' entries were found. " +
                    "Make sure the file is a valid Android APK or a DEX container."
            )
        }

        // Android multidex order: classes.dex = 1, classes2.dex = 2, ..., classes10.dex = 10.
        // (Lexicographic sorting would order classes10.dex before classes2.dex.)
        val ordered = entries.sortedBy { multidexIndex(it.first) }

        val readers = ordered.map { (name, bytes) -> openDexReader(bytes, "entry '$name'") }

        // Duplicate class detection across DEX files - report, never silently drop.
        val seen = HashSet<String>()
        val duplicates = LinkedHashSet<String>()
        for (r in readers) {
            for (cn in r.classNames) {
                if (!seen.add(cn)) {
                    duplicates.add(cn)
                }
            }
        }

        val merged = MultiDexFileReader(readers)
        return Discovered(
            reader = merged,
            dexCount = readers.size,
            classNames = merged.classNames,
            duplicates = duplicates.toList(),
            dexVersion = readers.maxOf { it.dexVersion },
            entryNames = ordered.map { it.first },
            readers = readers,
        )
    }

    /** classes.dex -> 1, classes2.dex -> 2, classes10.dex -> 10. */
    internal fun multidexIndex(entryName: String): Int {
        val m = MULTIDEX_ENTRY.matchEntire(entryName)
            ?: throw IOException("not a multidex entry: $entryName")
        val digits = m.groupValues[1]
        return if (digits.isEmpty()) 1 else digits.toInt()
    }
}
