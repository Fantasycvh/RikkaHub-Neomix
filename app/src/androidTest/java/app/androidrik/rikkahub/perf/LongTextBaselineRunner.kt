package app.androidrik.rikkahub.perf

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.runner.AndroidJUnitRunner

/** Prevent production startup/DB/background jobs even when OEM runner visibility requires the normal package. */
class LongTextBaselineRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity =
        super.newActivity(cl, if (className == "app.androidrik.rikkahub.RouteActivity")
            "app.androidrik.rikkahub.SketchTestHostActivity" else className, intent)
}
