package com.das.p1stouch.printer.hms

import android.content.res.AssetManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bambu HMS (Health Management System) error code lookup. Port of
 * printer/hms_codes.py: the same vendored 4998-entry English code->message
 * table (`hms_codes_en.json.gz` in the Python project, flat {16-hex-digit
 * code: message}), copied verbatim into assets/ rather than re-fetched,
 * and loaded lazily since it's only needed once an HMS entry actually
 * shows up. See [load] for why this reads it as plain JSON, not gzip.
 */
class HmsCodes(private val assetManager: AssetManager) {
    private val codes: Map<String, String> by lazy { load() }

    // The source file (resources/data/hms_codes_en.json.gz in the Python
    // project) is gzip -- but AGP's asset packaging silently gunzips any
    // .gz-suffixed asset at build time and strips the extension (confirmed
    // live: the built APK contains a plain "hms_codes_en.json" entry, not
    // "hms_codes_en.json.gz"), so this reads it as plain JSON rather than
    // gunzipping again.
    private fun load(): Map<String, String> = try {
        assetManager.open(ASSET_NAME).use { raw ->
            val text = raw.readBytes().toString(Charsets.UTF_8)
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
        }.also { Log.d(TAG, "loaded ${it.size} HMS codes") }
    } catch (e: Exception) {
        Log.w(TAG, "failed to load HMS code table", e)
        emptyMap()
    }

    /** Human-readable message for an HMS entry, or a generic fallback with
     * the wiki.bambulab.com-style code if this table doesn't cover it. */
    fun describe(attr: Int, code: Int): String {
        val attrHex = String.format("%08x", attr)
        val codeHex = String.format("%08x", code)
        val codeString = "${attrHex.substring(0, 4)}_${attrHex.substring(4)}_${codeHex.substring(0, 4)}_${codeHex.substring(4)}"
        val message = codes[attrHex + codeHex]
        return if (message != null) "$message (HMS_$codeString)" else "Unknown HMS error (HMS_$codeString)"
    }

    companion object {
        private const val TAG = "HmsCodes"
        private const val ASSET_NAME = "hms_codes_en.json"
    }
}
