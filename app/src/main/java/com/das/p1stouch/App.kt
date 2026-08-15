package com.das.p1stouch

import android.app.Application
import com.das.p1stouch.data.PrinterConfigRepository
import com.das.p1stouch.printer.MockBackend
import com.das.p1stouch.printer.PrinterBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the single app-wide [PrinterBackend] instance and the coroutine scope
 * it runs on, analogous to how the Python app's main.py builds one backend
 * and hands it to [MainActivity]/every screen's ViewModel. Manual DI (no
 * Hilt) -- simple enough for this app's single-backend-instance shape.
 */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob())

    val configRepository: PrinterConfigRepository by lazy { PrinterConfigRepository(this) }

    // TODO(M3): pick MockBackend vs RealBackend based on configRepository's
    // saved backend choice, same as the Python app's build_backend(). Mock-only
    // for now (M1/M2).
    val backend: PrinterBackend by lazy { MockBackend(appScope) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { backend.connect() }
    }
}
