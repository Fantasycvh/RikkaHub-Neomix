package app.androidrik.rikkahub.phoneuse

import android.app.ActivityManager

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal const val PHONE_USE_DISPLAY_WIDTH = 720
internal const val PHONE_USE_DISPLAY_HEIGHT = 1280
private const val PHONE_USE_DISPLAY_DPI = 280
private const val SHELL_PACKAGE = "com.android.shell"
private const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
private const val MIN_DISPLAY_LIFETIME_MS = 500L
internal const val SHELL_UID = 2000
internal const val ROOT_UID = 0

// The SDK display flags below are hidden APIs in Android 15. The shell process can set them.
private const val SUPPORTS_TOUCH = 1 shl 6
private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
private const val TRUSTED = 1 shl 10
private const val OWN_DISPLAY_GROUP = 1 shl 11
private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
private const val OWN_FOCUS = 1 shl 14
private const val DEVICE_DISPLAY_GROUP = 1 shl 15
private const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

/** Runs in a Shizuku UserService. Its binder exposes only operations for its own display. */
@RequiresApi(35)
class PhoneUseDisplayService(private val context: Context) : IPhoneUseDisplayService.Stub() {
    private var display: VirtualDisplay? = null
    private var displayCreatedAt = 0L
    private var owner: IBinder? = null
    private val ownerDied = IBinder.DeathRecipient { destroy() }

    @Synchronized
    override fun create(surface: Surface, owner: IBinder): Int {
        val uid = Process.myUid()
        check(uid == SHELL_UID || uid == ROOT_UID) { "Unsupported Shizuku process UID." }
        check(display == null)
        try {
            owner.linkToDeath(ownerDied, 0)
            this.owner = owner
            val ownerPackage = if (uid == SHELL_UID) SHELL_PACKAGE else context.packageName
            val shellContext = object : ContextWrapper(context) {
                override fun getPackageName() = ownerPackage
                override fun getOpPackageName() = ownerPackage
                override fun getAttributionSource(): AttributionSource =
                    AttributionSource.Builder(uid).setPackageName(ownerPackage).build()
            }
            val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(shellContext)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or TRUSTED or OWN_DISPLAY_GROUP or
                TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
            val created = checkNotNull(manager.createVirtualDisplay(
                "Neomix Phone Use",
                PHONE_USE_DISPLAY_WIDTH,
                PHONE_USE_DISPLAY_HEIGHT,
                PHONE_USE_DISPLAY_DPI,
                surface,
                flags,
            )) { "Android rejected the virtual display configuration." }
            display = created
            displayCreatedAt = SystemClock.elapsedRealtime()
            return created.display.displayId
        } catch (error: Exception) {
            release()
            Log.e("PhoneUseDisplay", "Could not create virtual display", error)
            throw IllegalStateException(error.cause?.message ?: error.message)
        } finally {
            surface.release()
        }
    }

    @Synchronized
    override fun launch(packageName: String): Boolean {
        val id = display?.display?.displayId ?: return false
        val activity = context.packageManager.queryIntentActivities(launcherIntent(packageName), 0)
            .firstOrNull()?.activityInfo ?: return false
        val component = ComponentName(activity.packageName, activity.name)
        return runCommand(
            "/system/bin/am", "start", "--display", id.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-f", LAUNCH_FLAGS.toString(), "-n", component.flattenToString(),
        )
    }

    @Synchronized
    override fun openUrl(packageName: String, url: String): Boolean {
        val id = display?.display?.displayId ?: return false
        val link = phoneUseHttpUrl(url) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(packageName)
        val handler = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return false
        if (handler.packageName != packageName || !handler.exported) return false
        // A browser's URL receiver can hand the request to an existing task and move it here.
        check(!hasTaskOnAnotherDisplay(packageName, id)) {
            "This browser already has a task on another display. Choose a separate browser for the virtual display or close its existing task."
        }
        return runCommand(
            "/system/bin/am", "start", "--display", id.toString(),
            "-a", Intent.ACTION_VIEW, "-c", Intent.CATEGORY_BROWSABLE,
            "-f", LAUNCH_FLAGS.toString(), "-p", packageName, "-d", link,
            logDetails = false,
        )
    }

    @Suppress("DEPRECATION")
    private fun hasTaskOnAnotherDisplay(packageName: String, displayId: Int): Boolean {
        val displayField = Class.forName("android.app.TaskInfo").getField("displayId")
        return context.getSystemService(ActivityManager::class.java).getRunningTasks(256).any { task ->
            (task.baseActivity?.packageName == packageName || task.topActivity?.packageName == packageName) &&
                displayField.getInt(task) != displayId
        }
    }

    override fun listLaunchers(): List<Bundle> {
        val pm = context.packageManager
        return pm.queryIntentActivities(launcherIntent(), 0)
            .distinctBy { it.activityInfo.packageName }
            .map { activity ->
                Bundle().apply {
                    putString("package", activity.activityInfo.packageName)
                    putString("label", activity.loadLabel(pm).toString())
                }
            }
    }

    private fun launcherIntent(packageName: String? = null): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)

    @Synchronized
    override fun back(): Boolean {
        val id = display?.display?.displayId ?: return false
        return runCommand("/system/bin/input", "-d", id.toString(), "keyevent", "KEYCODE_BACK")
    }

    private fun runCommand(vararg args: String, logDetails: Boolean = true): Boolean {
        val command = ProcessBuilder(*args).redirectErrorStream(true).start()
        return try {
            if (!command.waitFor(3, TimeUnit.SECONDS)) {
                Log.w("PhoneUseDisplay", "${args[0]} timed out")
                false
            } else {
                val output = command.inputStream.bufferedReader().use { it.readText() }
                val succeeded = command.exitValue() == 0 && output.lineSequence().none {
                    it.startsWith("Error:", ignoreCase = true) || it.startsWith("Security exception:", ignoreCase = true)
                }
                if (!succeeded) Log.w("PhoneUseDisplay", if (logDetails) "${args[0]} failed: ${output.trim()}" else "${args[0]} failed")
                succeeded
            }
        } finally {
            command.destroy()
        }
    }

    @Synchronized
    override fun release() {
        display?.let {
            // ColorOS can still be attaching SystemUI windows when a display is stopped immediately.
            val remaining = MIN_DISPLAY_LIFETIME_MS - (SystemClock.elapsedRealtime() - displayCreatedAt)
            if (remaining > 0) SystemClock.sleep(remaining)
            it.release()
        }
        display = null
        owner?.let { runCatching { it.unlinkToDeath(ownerDied, 0) } }
        owner = null
    }

    override fun destroy() {
        try {
            release()
        } finally {
            exitProcess(0)
        }
    }
}
