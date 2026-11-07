package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.ai.approvalRequestSummary
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.NotificationAction
import app.androidrik.rikkahub.utils.cancelNotification
import app.androidrik.rikkahub.utils.sendNotification

private const val TAG = "SubagentRunner"

/**
 * 旧固定通知 id/requestCode（`namespacedNotifications` 关闭时回退用）。
 *
 * 默认走 [NotificationIds] 的按 runId 命名空间；保留这组常量只为 P0 回滚开关：
 * 回退后并行 run 会重新互相覆盖，行为与升级前一致。
 */
private const val LEGACY_APPROVAL_NOTIFICATION_ID = 4242
private const val LEGACY_APPROVAL_APPROVE_REQUEST_CODE = 4243
private const val LEGACY_APPROVAL_DENY_REQUEST_CODE = 4244
private const val LEGACY_APPROVAL_DETAIL_REQUEST_CODE = 4242

/**
 * 子代理 run 的**审批通知**（弹出 / 撤销 / PendingIntent 装配），从 [SubagentRunner] 拆出的
 * 纯 Android 通知块——只依赖 Context + 设置 + [RunNotifications]，与 run 执行体零耦合。
 */
internal class SubagentApprovalNotifications(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val runNotifications: RunNotifications,
) {
    private fun approvalNotificationId(runId: String): Int =
        if (settingsStore.settingsFlow.value.namespacedNotifications) {
            NotificationIds.subagentApproval(runId)
        } else {
            LEGACY_APPROVAL_NOTIFICATION_ID
        }

    /** 审批动作的 PendingIntent requestCode；回滚时回旧固定 4242/4243/4244。 */
    private fun approvalRequestCode(
        runId: String,
        action: NotificationIds.ApprovalAction,
    ): Int = if (settingsStore.settingsFlow.value.namespacedNotifications) {
        NotificationIds.subagentApprovalRequestCode(runId, action)
    } else {
        when (action) {
            NotificationIds.ApprovalAction.APPROVE -> LEGACY_APPROVAL_APPROVE_REQUEST_CODE
            NotificationIds.ApprovalAction.DENY -> LEGACY_APPROVAL_DENY_REQUEST_CODE
            NotificationIds.ApprovalAction.OPEN_DETAIL -> LEGACY_APPROVAL_DETAIL_REQUEST_CODE
        }
    }

    fun postApprovalNotification(
        runId: String,
        label: String,
        pending: List<UIMessagePart.Tool>,
    ): Boolean {
        if (!runNotifications.approvalRouteAvailable()) return false
        return runCatching {
            context.sendNotification(
                channelId = SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID,
                // 按 runId 命名空间化：并行 run 各有各的通知条，不会互相覆盖；
                // 同一 run 的重复投递仍是同一条（原地更新）
                notificationId = approvalNotificationId(runId),
            ) {
                title = context.getString(
                    R.string.subagent_approval_title,
                    label.ifBlank { context.getString(R.string.subagent_page_title) },
                )
                content = pending.joinToString("\n") { approvalRequestSummary(it) }
                useBigTextStyle = true
                autoCancel = true
                useDefaults = true
                category = NotificationCompat.CATEGORY_REMINDER
                contentIntent = runDetailPendingIntent(runId)
                actions += NotificationAction(
                    icon = R.drawable.ic_stat_rikkahub,
                    title = context.getString(R.string.subagent_approval_approve),
                    intent = approvalDecisionIntent(runId, pending, approved = true),
                )
                actions += NotificationAction(
                    icon = R.drawable.ic_stat_rikkahub,
                    title = context.getString(R.string.subagent_approval_deny),
                    intent = approvalDecisionIntent(runId, pending, approved = false),
                )
            }
        }.onFailure { Logging.log(TAG, "approval notification failed: $it") }.getOrDefault(false)
    }

    /**
     * 撤掉某个 run 的待批通知。
     *
     * **必须按 runId 撤**：固定 id 时代任一 run 结束（含超时、取消）都会撤掉并行 run 的待批
     * 通知——用户那边看起来"审批消失了"，而 run 其实还停在 `awaiting_approval` 上等 5 分钟。
     */
    fun cancelApprovalNotification(runId: String) {
        runCatching { context.cancelNotification(approvalNotificationId(runId)) }
    }

    /** 点通知主体 → 运行详情页（在那里能看到入参全文与轨迹）。 */
    private fun runDetailPendingIntent(runId: String): PendingIntent {
        val intent = Intent(context, app.androidrik.rikkahub.RouteActivity::class.java)
            .putExtra("runId", runId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            // 每个 run 一个 requestCode：并行 run 详情页的 PendingIntent 互不覆盖
            // （FLAG_UPDATE_CURRENT 会按 requestCode 覆写 extras）
            approvalRequestCode(
                runId,
                NotificationIds.ApprovalAction.OPEN_DETAIL,
            ),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知按钮的 PendingIntent：**显式组件 + exported=false 的 Receiver**。
     *
     * requestCode 必须区分（批准所有 / 拒绝所有），否则 `FLAG_UPDATE_CURRENT` 会让后者覆盖前者
     * （两个按钮变成同一个动作——这是 PendingIntent 的经典坑）。**同时还要按 runId 区分**：
     * 固定 requestCode 下，后弹通知的 run 会把先弹的那个 run 的按钮动作整个覆写掉——
     * 用户点的是 A run 的通知，批的却是 B run 的工具。
     */
    private fun approvalDecisionIntent(
        runId: String,
        pending: List<UIMessagePart.Tool>,
        approved: Boolean,
    ): PendingIntent {
        val intent = Intent(context, SubagentApprovalReceiver::class.java)
            .putStringArrayListExtra(
                SubagentApprovalReceiver.EXTRA_TOOL_CALL_IDS,
                ArrayList(pending.map { it.toolCallId }),
            )
            .putExtra(SubagentApprovalReceiver.EXTRA_APPROVED, approved)
        return PendingIntent.getBroadcast(
            context,
            approvalRequestCode(
                runId,
                if (approved) {
                    NotificationIds.ApprovalAction.APPROVE
                } else {
                    NotificationIds.ApprovalAction.DENY
                },
            ),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
