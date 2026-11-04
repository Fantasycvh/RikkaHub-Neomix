package app.androidrik.rikkahub.data.repository

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.runner.AndroidJUnitRunner

/**
 * Opt-in runner for database and storage-protocol tests. Do not start RikkaHubApp, Koin, production DB migration,
 * background consolidation, or file cleanup against an installed debug profile.
 * Never use this runner for UI/DI integration tests: they need the real application.
 */
class MemoryIsolationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        // Some OEMs restore the last launcher task when the screen wakes during Doze tests.
        // Its production UI requires RikkaHubApp; this runner deliberately uses plain Application.
        // Keep that unsolicited launch inside the existing empty debug-only test host.
        val isolated = if (className == "app.androidrik.rikkahub.RouteActivity")
            "app.androidrik.rikkahub.SketchTestHostActivity" else className
        return super.newActivity(cl, isolated, intent)
    }
}
