package app.androidrik.rikkahub.data.repository

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Opt-in runner for database-only tests. Do not start RikkaHubApp, Koin, production DB migration,
 * background consolidation, or file cleanup against an installed debug profile.
 * Never use this runner for UI/DI integration tests: they need the real application.
 */
class MemoryIsolationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
