package com.das.p1stouch.printer

/**
 * Curated filament presets for the AMS slot "Edit" dialog. Port of the
 * Python app's filament_presets.py -- see that file's docstring for why
 * this only distinguishes Bambu Lab, PolyLite, and PolyTerra by brand,
 * with everything else (Overature/Generic/eSUN) sharing the same
 * "Generic" (GFxx99) preset list: the AMS RFID/preset system has no
 * separate index for other third-party brands, so the manufacturer
 * choice for those three is informational only.
 */
data class FilamentPreset(
    val label: String,
    val trayInfoIdx: String,
    val nozzleTempMin: Int,
    val nozzleTempMax: Int,
    val trayType: String,
)

object FilamentPresets {
    val MANUFACTURERS = listOf("Bambu Lab", "PolyLite", "Overature", "Generic", "PolyTerra", "eSUN")

    private val BAMBU_PRESETS = listOf(
        FilamentPreset("PLA Basic", "GFA00", 190, 230, "PLA"),
        FilamentPreset("PLA Matte", "GFA01", 190, 230, "PLA"),
        FilamentPreset("PLA Silk", "GFA05", 210, 230, "PLA"),
        FilamentPreset("PLA-CF", "GFA50", 210, 240, "PLA"),
        FilamentPreset("PETG HF", "GFG02", 230, 260, "PETG"),
        FilamentPreset("PETG-CF", "GFG50", 240, 270, "PETG"),
        FilamentPreset("ABS", "GFB00", 240, 270, "ABS"),
        FilamentPreset("ASA", "GFB01", 240, 270, "ASA"),
        FilamentPreset("PC", "GFC00", 260, 280, "PC"),
        FilamentPreset("PA-CF", "GFN03", 270, 300, "PA-CF"),
        FilamentPreset("TPU for AMS", "GFU02", 230, 230, "TPU"),
        FilamentPreset("Support (PLA/PETG)", "GFS05", 190, 220, "Support"),
    )

    // The GFxx99 "Generic" family -- what any non-Bambu/PolyLite/PolyTerra
    // spool reads as to the printer, regardless of its actual brand.
    private val GENERIC_PRESETS = listOf(
        FilamentPreset("PLA", "GFL99", 190, 250, "PLA"),
        FilamentPreset("PLA-CF", "GFL98", 190, 250, "PLA"),
        FilamentPreset("PETG", "GFG99", 220, 260, "PETG"),
        FilamentPreset("ABS", "GFB99", 240, 270, "ABS"),
        FilamentPreset("ASA", "GFB98", 240, 270, "ASA"),
        FilamentPreset("PA", "GFN99", 270, 300, "PA"),
        FilamentPreset("PA-CF", "GFN98", 270, 300, "PA"),
        FilamentPreset("PC", "GFC99", 260, 280, "PC"),
        FilamentPreset("TPU", "GFU99", 200, 250, "TPU"),
        FilamentPreset("PVA", "GFS99", 190, 250, "PVA"),
    )

    val PRESETS_BY_MANUFACTURER: Map<String, List<FilamentPreset>> = mapOf(
        "Bambu Lab" to BAMBU_PRESETS,
        "PolyLite" to listOf(FilamentPreset("PLA", "GFL00", 190, 250, "PLA")),
        "PolyTerra" to listOf(FilamentPreset("PLA", "GFL01", 190, 250, "PLA")),
        "Overature" to GENERIC_PRESETS,
        "Generic" to GENERIC_PRESETS,
        "eSUN" to GENERIC_PRESETS,
    )

    fun presetKey(manufacturer: String, label: String): String = "$manufacturer|$label"

    // Flat lookup by presetKey(), for the backend command layer -- it only
    // needs the resolved preset, not which manufacturer picked it.
    val BY_KEY: Map<String, FilamentPreset> = PRESETS_BY_MANUFACTURER
        .flatMap { (manufacturer, presets) -> presets.map { presetKey(manufacturer, it.label) to it } }
        .toMap()
}
