package com.das.p1stouch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "p1s_config")

/** Port of the Python app's config.py: printer connection details + which
 * backend to run. DataStore-backed (replaces the Python app's config.yaml). */
data class PrinterConfig(
    val ip: String = "",
    val accessCode: String = "",
    val serial: String = "",
    val backend: String = "mock", // "mock" | "real"
    // This printer's real-world FTPS throughput is slow (~85 KB/s observed
    // live) -- a first-time Print Files load can take several minutes to
    // download+cache every file's thumbnail. Lets a user with a large/slow
    // library skip that entirely.
    val skipThumbnails: Boolean = false,
) {
    val isPrinterComplete: Boolean
        get() = ip.isNotBlank() && accessCode.isNotBlank() && serial.isNotBlank()

    /** True if enough info is present to skip the first-run setup screen. */
    val isReady: Boolean
        get() = backend == "mock" || isPrinterComplete
}

class PrinterConfigRepository(private val context: Context) {
    private object Keys {
        val IP = stringPreferencesKey("printer_ip")
        val ACCESS_CODE = stringPreferencesKey("printer_access_code")
        val SERIAL = stringPreferencesKey("printer_serial")
        val BACKEND = stringPreferencesKey("app_backend")
        val SKIP_THUMBNAILS = booleanPreferencesKey("skip_thumbnails")
    }

    val config: Flow<PrinterConfig> = context.dataStore.data.map { prefs ->
        PrinterConfig(
            ip = prefs[Keys.IP] ?: "",
            accessCode = prefs[Keys.ACCESS_CODE] ?: "",
            serial = prefs[Keys.SERIAL] ?: "",
            backend = prefs[Keys.BACKEND] ?: "mock",
            skipThumbnails = prefs[Keys.SKIP_THUMBNAILS] ?: false,
        )
    }

    suspend fun save(config: PrinterConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IP] = config.ip
            prefs[Keys.ACCESS_CODE] = config.accessCode
            prefs[Keys.SERIAL] = config.serial
            prefs[Keys.BACKEND] = config.backend
            prefs[Keys.SKIP_THUMBNAILS] = config.skipThumbnails
        }
    }
}
