package app.androidrik.rikkahub.ui.components.ui

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.service.KeepAliveService
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepAliveServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun foregroundPromotionCreatesNotificationWithExplicitLaunchTarget() {
        val enabled = KeepAliveService.isEnabled(context)
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} $permission"
                )
            ).use { it.readBytes() }
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            KeepAliveService.setEnabled(context, true)
            rule.runOnIdle { KeepAliveService.start(context) }
            rule.waitUntil(10_000) {
                notifications.activeNotifications.any { it.id == KeepAliveService.NOTIFICATION_ID }
            }
            assertNotNull(notifications.activeNotifications.first {
                it.id == KeepAliveService.NOTIFICATION_ID
            }.notification.contentIntent)
        } finally {
            KeepAliveService.setEnabled(context, enabled)
            if (!enabled) rule.runOnIdle { KeepAliveService.stop(context) }
        }
    }
}
