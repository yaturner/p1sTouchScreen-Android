package com.das.p1stouch.printer

import com.das.p1stouch.state.AMSTray
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One filament a 3MF actually uses, as recorded by the slicer -- see
 * [parseFilamentRequirements]. */
data class FilamentRequirement(val type: String?, val color: String?)

/** Result of [resolveAmsMapping]: the ams_mapping array to send, and an
 * optional user-facing warning if any filament couldn't be confidently
 * matched to a loaded AMS slot. */
data class AmsMappingResult(val mapping: List<Int>, val warning: String?)

/**
 * Port of the Python app's slice_info.config parsing + AMS slot matching
 * (printer/real_backend.py's _parse_filament_requirements/_match_ams_slot),
 * fixing the same real bug here: RealBackend used to always send
 * ams_mapping=[0] regardless of what a file actually needs or what's
 * physically loaded, which the printer's firmware can legitimately refuse
 * with HMS_0700_7000_0002_0008 ("failed to get AMS mapping table") whenever
 * slot 0 doesn't hold a matching filament -- confirmed live on the Python
 * side via a real user report on a single-color file (not a multi-material
 * file as first suspected; bambulabs_api's own start_print() defaults to
 * the identical hardcoded [0], so this was never a library gap either).
 */
object AmsMapping {
    /** (type, color) per filament a 3MF actually uses, in file order, or
     * null if unavailable (not a zip, no slice_info.config -- e.g. an
     * .stl -- or unparseable). Bambu Studio/OrcaSlicer write this at
     * Metadata/slice_info.config: a <plate> with one
     * <filament type="PLA" color="#RRGGBB".../> per filament actually
     * used -- confirmed live against a real downloaded 3MF. */
    fun parseFilamentRequirements(zipBytes: ByteArray, tempDir: File): List<FilamentRequirement>? {
        val tempFile = File.createTempFile("ams_check_", ".3mf", tempDir)
        try {
            tempFile.writeBytes(zipBytes)
            val xmlBytes = try {
                ZipFile(tempFile).use { zf ->
                    val entry = zf.getEntry("Metadata/slice_info.config") ?: return null
                    zf.getInputStream(entry).use { it.readBytes() }
                }
            } catch (e: Exception) {
                return null
            }
            val doc = try {
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(xmlBytes.inputStream())
            } catch (e: Exception) {
                return null
            }
            val plate = doc.getElementsByTagName("plate").item(0) as? Element ?: return null
            val filamentNodes = plate.getElementsByTagName("filament")
            val filaments = (0 until filamentNodes.length).map { i ->
                val el = filamentNodes.item(i) as Element
                FilamentRequirement(
                    type = el.getAttribute("type").ifBlank { null },
                    color = el.getAttribute("color").ifBlank { null },
                )
            }
            return filaments.ifEmpty { null }
        } finally {
            tempFile.delete()
        }
    }

    /** Best AMS slot for a required filament, and whether it's a confident
     * (exact type+color) match. Falls back to the first tray with just a
     * matching material type if no color match exists, then to slot 0 as a
     * last resort if nothing matches at all -- but only an exact
     * type+color match counts as confident. A type-only match still picks
     * a real slot (using the right material beats guessing slot 0 blind)
     * but is deliberately NOT marked confident: when every AMS slot
     * happens to hold the same material in different colors (e.g. four
     * spools of PLA), a naive "type matches" check would pick a slot by
     * list order alone and silently call that confident even though the
     * color is likely wrong -- confirmed against this exact scenario on a
     * real printer while building the Python version of this fix. */
    fun matchAmsSlot(requirement: FilamentRequirement, trays: List<AMSTray>): Pair<Int, Boolean> {
        val color = requirement.color?.uppercase() ?: ""
        val type = requirement.type?.uppercase() ?: ""
        val loaded = trays.filter { !it.isEmpty }
        loaded.firstOrNull { it.filamentType?.uppercase() == type && it.colorHex?.uppercase() == color }
            ?.let { return it.slotIndex to true }
        loaded.firstOrNull { it.filamentType?.uppercase() == type }
            ?.let { return it.slotIndex to false }
        return 0 to false
    }

    /** Resolves the full ams_mapping array for every filament a file
     * needs, plus a combined warning distinguishing "no such material
     * loaded at all" from "material loaded, but not confirmed to be the
     * right color" -- these need different wording since only the first
     * case genuinely can't succeed. */
    fun resolveAmsMapping(filaments: List<FilamentRequirement>, trays: List<AMSTray>): AmsMappingResult {
        val loadedTypes = trays.filter { !it.isEmpty }.map { it.filamentType?.uppercase() }.toSet()
        val mapping = mutableListOf<Int>()
        val noMaterial = mutableListOf<String>()
        val colorUnconfirmed = mutableListOf<String>()
        for (req in filaments) {
            val (slot, matched) = matchAmsSlot(req, trays)
            mapping.add(slot)
            if (matched) continue
            val type = req.type ?: "unknown"
            if (req.type?.uppercase() in loadedTypes) colorUnconfirmed.add(type) else noMaterial.add(type)
        }
        val messages = mutableListOf<String>()
        if (noMaterial.isNotEmpty()) {
            messages.add(
                "No loaded AMS spool matches this file's ${noMaterial.joinToString("/")} -- " +
                    "guessing a slot; the print will likely fail or use the wrong material.",
            )
        }
        if (colorUnconfirmed.isNotEmpty()) {
            messages.add(
                "Found ${colorUnconfirmed.joinToString("/")} loaded, but not in the exact color " +
                    "this file expects -- double check the AMS before printing.",
            )
        }
        return AmsMappingResult(mapping, messages.joinToString(" ").ifBlank { null })
    }
}
