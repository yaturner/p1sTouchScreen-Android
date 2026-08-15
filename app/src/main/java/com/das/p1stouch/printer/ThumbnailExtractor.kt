package com.das.p1stouch.printer

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts a 3MF's embedded plate preview PNG. Port of the Python app's
 * ThumbnailLoader._extract_thumbnail: scans for PNG entries under Metadata/,
 * preferring one with "plate_1" in its name (Bambu Studio/OrcaSlicer's name
 * for the main plate preview) over auxiliary thumbnails (top view, no-light
 * variant, etc.) when more than one is present.
 */
object ThumbnailExtractor {
    fun extractPlatePng(zipBytes: ByteArray): ByteArray? {
        val candidates = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("Metadata/") && name.lowercase().endsWith(".png")) {
                    candidates[name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (candidates.isEmpty()) return null
        val preferredKey = candidates.keys.firstOrNull { "plate_1" in it } ?: candidates.keys.first()
        return candidates[preferredKey]
    }
}
