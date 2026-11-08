package app.androidrik.rikkahub.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RouteActivity
import app.androidrik.rikkahub.SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.task.TaskState
import app.androidrik.rikkahub.data.task.TaskStateMappings
import app.androidrik.rikkahub.data.task.TaskVisibility
import app.androidrik.rikkahub.utils.NotificationUtil
import app.androidrik.rikkahub.utils.cancelNotification
import app.androidrik.rikkahub.utils.sendNotification

const val AGENT_RUN_COMPLETED_CHANNEL_ID = "agent_run_completed"

fun notificationRouteAvailable(context: Context, channelId: String): Boolean {
    if (!NotificationUtil.hasNotificationPermission(context)) return false
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannel(channelId) ?: return false
    if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    if (Build.VERSION.SDK_INT >= 28 && channel.group != null) {
        if (context.getSystemService(NotificationManager::class.java)
                .getNotificationChannelGroup(channel.group)?.isBlocked == true) return false
    }
    return true
}

fun shouldNotifyRunCompletion(run: AgentRunEntity, foregroundAndVisible: Boolean): Boolean =
    !foregroundAndVisible && run.finishedAt != null &&
        (run.entryPoint == AgentRunEntity.ENTRY_POINT_SUBAGENT || run.deliveryMode in setOf(
            AgentRunEntity.DELIVERY_MODE_NOTICE, AgentRunEntity.DELIVERY_MODE_HANDOFF, AgentRunEntity.DELIVERY_MODE_RESUME)) &&
        // 定时 headless 的取消是用户自己按的「停止」：与定时注入路径一致，不再推一条
        // 「已取消」通知；失败/完成仍通知（结果摘要只有这里能看到）。
        !(run.entryPoint == AgentRunEntity.ENTRY_POINT_SCHEDULED && run.status == AgentRunEntity.STATUS_CANCELLED) &&
        TaskStateMappings.fromAgentRun(run.status, run.error) in setOf(
            TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.TIMED_OUT, TaskState.INTERRUPTED)

class RunNotifications(private val context: Context) {
    fun approvalRouteAvailable() = notificationRouteAvailable(context, SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID)

    fun completed(run: AgentRunEntity) {
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            TaskVisibility.isVisible(run.id, run.parentConversationId)
        if (!shouldNotifyRunCompletion(run, visible)) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannelCompat.Builder(
            AGENT_RUN_COMPLETED_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.agent_run_completed_channel)).build())
        if (!notificationRouteAvailable(context, AGENT_RUN_COMPLETED_CHANNEL_ID)) return
        val state = TaskStateMappings.fromAgentRun(run.status, run.error)
        val label = context.getString(when (state) {
            TaskState.COMPLETED -> R.string.agent_run_status_completed
            TaskState.CANCELLED -> R.string.agent_run_status_cancelled
            TaskState.TIMED_OUT -> R.string.agent_run_status_timeout
            TaskState.INTERRUPTED -> R.string.agent_run_status_interrupted
            else -> R.string.agent_run_status_failed
        })
        val intent = Intent(context, RouteActivity::class.java).putExtra("runId", run.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.sendNotification(AGENT_RUN_COMPLETED_CHANNEL_ID, NotificationIds.runCompleted(run.id)) {
            title = "${run.profileName.ifBlank { "Agent" }} · $label"
            content = (run.resultSummary ?: run.error ?: run.cancelReason ?: run.goal).take(240)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = PendingIntent.getActivity(context, NotificationIds.runCompletedDetail(run.id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    fun cancel(runId: String) = context.cancelNotification(NotificationIds.runCompleted(runId))
}
