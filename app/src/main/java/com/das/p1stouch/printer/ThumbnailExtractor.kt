package com.das.p1stouch.printer

import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts a 3MF's embedded plate preview PNG. Port of the Python app's
 * ThumbnailLoader._extract_thumbnail: scans for PNG entries under Metadata/,
 * preferring one with "plate_1" in its name (Bambu Studio/OrcaSlicer's name
 * for the main plate preview) over auxiliary thumbnails (top view, no-light
 * variant, etc.) when more than one is present.
 *
 * Uses random-access ZipFile, not streaming ZipInputStream -- confirmed live
 * that Bambu Studio's 3MF writer produces STORED entries with a trailing
 * data descriptor, a spec-tolerated pattern that most tools (unzip, Python's
 * zipfile) handle fine but that java.util.zip.ZipInputStream's sequential
 * reader doesn't: on the desktop JVM it throws ZipException("only DEFLATED
 * entries can have EXT descriptor"), and on Android it silently produced a
 * matched entry with 0 bytes read instead of throwing -- every thumbnail
 * "succeeded" with empty content. ZipFile reads the central directory at the
 * end of the archive for authoritative offsets/sizes instead, sidestepping
 * the ambiguity entirely (verified against a real downloaded 3MF).
 */
object ThumbnailExtractor {
    fun extractPlatePng(zipBytes: ByteArray, tempDir: File): ByteArray? {
        val tempFile = File.createTempFile("thumb_extract_", ".3mf", tempDir)
        try {
            tempFile.writeBytes(zipBytes)
            val candidates = LinkedHashMap<String, ByteArray>()
            ZipFile(tempFile).use { zf ->
                for (entry in zf.entries()) {
                    val name = entry.name
                    if (!entry.isDirectory && name.startsWith("Metadata/") && name.lowercase().endsWith(".png")) {
                        candidates[name] = zf.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }
            if (candidates.isEmpty()) return null
            val preferredKey = candidates.keys.firstOrNull { "plate_1" in it } ?: candidates.keys.first()
            return candidates[preferredKey]
        } finally {
            tempFile.delete()
        }
    }
}
