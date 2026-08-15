package com.das.p1stouch

import android.content.Context
import android.content.Intent

/**
 * Relaunches the app fresh, killing this process. Needed after changing
 * which backend to use (mock vs real) -- [App.backend] is decided once via
 * `by lazy` at process start (matching the Python app's config.yaml being
 * read once at launch too), so a saved config change only takes effect
 * after a real restart. Nicer than the Python app's "please restart
 * manually" Settings message -- Android supports doing this cleanly.
 */
object AppRestart {
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
