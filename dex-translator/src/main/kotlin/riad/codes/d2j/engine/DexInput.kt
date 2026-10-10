package riad.codes.d2j.engine

import riad.codes.d2j.reader.BaseDexFileReader
import riad.codes.d2j.DexConstants
import riad.codes.d2j.reader.DexFileReader
import riad.codes.d2j.reader.MultiDexFileReader
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Deterministic, limits-aware input discovery for the modern engine.
 *
 * Handles raw DEX files and ZIP/APK containers. DEX entries inside a container are
 * discovered in sorted (deterministic) order regardless of their on-disk entry order,
 * and duplicate class names across DEX files are detected and reported instead of
 * being silently dropped (the underlying [MultiDexFileReader] still dedups, but the
 * engine reports what was dropped so nothing is lost silently).
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
    )

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
            magic3 == "dex" -> discoverSingleDex(data)
            magic2 == "PK" -> discoverZip(data, options)
            else -> throw IOException(
                "Input is not a DEX or ZIP file (file magic '${magic3.replace("\n", "\\n")}'). " +
                    "Expected a raw .dex file or a .zip/.apk container containing classes*.dex."
            )
        }
    }

    private fun discoverSingleDex(data: ByteArray): Discovered {
        val reader = DexFileReader(data)
        val classNames = reader.classNames
        return Discovered(
            reader = reader,
            dexCount = 1,
            classNames = classNames,
            duplicates = emptyList(),
            dexVersion = reader.dexVersion,
        )
    }

    private fun discoverZip(data: ByteArray, options: ConversionOptions): Discovered {
        val readers = ArrayList<DexFileReader>()
        val entryNames = ArrayList<String>()

        java.util.zip.ZipInputStream(data.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    val bytes = zis.readBytes()
                    readers.add(DexFileReader(bytes))
                    entryNames.add(name)
                    if (readers.size > options.maxDexCount) {
                        throw IOException(
                            "Input container declares more than ${options.maxDexCount} classes*.dex entries " +
                                "(limit reached at '$name'). Increase ConversionOptions.maxDexCount if this is expected."
                        )
                    }
                }
                zis.closeEntry()
            }
        }

        if (readers.isEmpty()) {
            throw IOException(
                "Input is a ZIP/APK container but no 'classes*.dex' entries were found. " +
                    "Make sure the file is a valid Android APK or a DEX container."
            )
        }

        // Deterministic ordering by entry name (classes.dex, classes2.dex, ...).
        val sorted = entryNames.indices.sortedBy { entryNames[it] }
        val orderedReaders = sorted.map { readers[it] }

        // Duplicate class detection across DEX files - report, never silently drop.
        val seen = HashSet<String>()
        val duplicates = LinkedHashSet<String>()
        for (r in orderedReaders) {
            for (cn in r.classNames) {
                if (!seen.add(cn)) {
                    duplicates.add(cn)
                }
            }
        }

        val merged = MultiDexFileReader(orderedReaders)
        return Discovered(
            reader = merged,
            dexCount = orderedReaders.size,
            classNames = merged.classNames,
            duplicates = duplicates.toList(),
            dexVersion = orderedReaders.maxOf { it.dexVersion },
        )
    }
}
