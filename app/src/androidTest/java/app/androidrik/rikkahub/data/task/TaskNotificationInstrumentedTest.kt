package app.androidrik.rikkahub.data.task

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.service.AGENT_RUN_COMPLETED_CHANNEL_ID
import app.androidrik.rikkahub.service.NotificationIds
import app.androidrik.rikkahub.service.RunNotifications
import app.androidrik.rikkahub.service.notificationRouteAvailable
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class TaskNotificationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefix = "runtime-test-notification-"
    private var hadCompletionChannel = false

    @Before fun prepare() {
        check(context.packageName.endsWith(".debug"))
        hadCompletionChannel = manager.getNotificationChannel(AGENT_RUN_COMPLETED_CHANNEL_ID) != null
    }

    @After fun restore() {
        listOf("a", "b").forEach { manager.cancel(NotificationIds.runCompleted(prefix + it)) }
        manager.deleteNotificationChannel(prefix + "available")
        manager.deleteNotificationChannel(prefix + "blocked")
        if (!hadCompletionChannel) manager.deleteNotificationChannel(AGENT_RUN_COMPLETED_CHANNEL_ID)
    }

    @Test fun disabledChannelIsNotAnApprovalRoute() {
        val available = prefix + "available"
        val blocked = prefix + "blocked"
        manager.createNotificationChannel(NotificationChannel(available, "test", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(blocked, "blocked test", NotificationManager.IMPORTANCE_NONE))
        assertTrue(notificationRouteAvailable(context, available))
        assertFalse(notificationRouteAvailable(context, blocked))
        assertFalse(notificationRouteAvailable(context, prefix + "missing"))

    }

    @Test fun deniedPermissionIsNotAnApprovalRoute() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("expectNotificationsDenied") == "true")
        check(Build.VERSION.SDK_INT >= 33)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val channel = prefix + "available"
        manager.createNotificationChannel(NotificationChannel(channel, "test", NotificationManager.IMPORTANCE_DEFAULT))
        assertFalse(notificationRouteAvailable(context, channel))
    }

    private fun run(id: String, status: String) = AgentRunEntity(prefix + id, "chat", status, "profile", "Test run",
        null, null, "test goal", "[]", "[]", null, "test outcome", "parent", 1, 2, 3, deliveryMode = "notice")

    @Test fun parallelCompletionNotificationsHaveIndependentPendingIntentsAndCancellation() {
        val notifications = RunNotifications(context)
        notifications.completed(run("a", "failed"))
        notifications.completed(run("b", "cancelled"))
        val ids = setOf(NotificationIds.runCompleted(prefix + "a"), NotificationIds.runCompleted(prefix + "b"))
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (manager.activeNotifications.count { it.id in ids } != 2 && android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(20)
        val posted = manager.activeNotifications.filter { it.id in ids }
        assertEquals(2, posted.size)
        assertNotEquals(posted[0].notification.contentIntent, posted[1].notification.contentIntent)
        notifications.cancel(prefix + "a")
        // cancel() 走 Binder，activeNotifications 快照可能晚一拍：轮询而不是立刻断言。
        val cancelDeadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (manager.activeNotifications.any { it.id == NotificationIds.runCompleted(prefix + "a") } &&
            android.os.SystemClock.elapsedRealtime() < cancelDeadline
        ) Thread.sleep(20)
        assertTrue(manager.activeNotifications.any { it.id == NotificationIds.runCompleted(prefix + "b") })
        assertFalse(manager.activeNotifications.any { it.id == NotificationIds.runCompleted(prefix + "a") })
    }
}
