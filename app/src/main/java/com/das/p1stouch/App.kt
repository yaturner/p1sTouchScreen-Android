package com.das.p1stouch

import android.app.Application
import com.das.p1stouch.data.PrinterConfigRepository
import com.das.p1stouch.printer.MockBackend
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.printer.RealBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns the single app-wide [PrinterBackend] instance and the coroutine scope
 * it runs on, analogous to how the Python app's main.py builds one backend
 * and hands it to [MainActivity]/every screen's ViewModel. Manual DI (no
 * Hilt) -- simple enough for this app's single-backend-instance shape.
 */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob())

    val configRepository: PrinterConfigRepository by lazy { PrinterConfigRepository(this) }

    // Same one-time synchronous local-disk read as MainActivity's
    // startDestination decision -- picks the backend once at process start,
    // matching the Python app's build_backend(). Port of that same
    // "mock" | "real" switch, one-shot rather than reactive: switching
    // backends at runtime isn't supported any more than the Python app's
    // config.yaml is hot-reloaded (Settings still directs the user to
    // restart after changing this).
    val backend: PrinterBackend by lazy {
        val config = runBlocking { configRepository.config.first() }
        if (config.backend == "real" && config.isPrinterComplete) {
            RealBackend(config.ip, config.accessCode, config.serial, cacheDir, config.skipThumbnails)
        } else {
            MockBackend(appScope)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { backend.connect() }
    }
}
