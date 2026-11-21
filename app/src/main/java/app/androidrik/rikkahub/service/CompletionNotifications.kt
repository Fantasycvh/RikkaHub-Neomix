package app.androidrik.rikkahub.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.androidrik.rikkahub.utils.sendNotification

/**
 * 完成类通知的公共骨架（评估稿 ③，design-interface-convergence-round2.md）。
 *
 * **统一的是骨架，不是策略**：四个完成类通知（聊天生成完成 / 定时任务终态 / 媒体创作终态 /
 * agent run 终态）的前台抑制口径是各域的产品决策——聊天=前台静默+用户开关双闸、
 * 媒体=前台静默、定时=总是通知、agent run=可见性参与判定——策略留在各调用方；
 * 这里只收编三件真正重复的事：
 *
 * 1. **路由可用性预检**：无通知权限 / 总开关关 / 频道被屏蔽 / 频道不存在时不发。
 *    此前只有 agent run 做了这层检查——媒体与定时在路由不可用时曾白发通知。
 * 2. **应用前台判定**：媒体与 agent run 用同一段 ProcessLifecycleOwner 逻辑。
 * 3. **通知 id**：全部经 [NotificationIds]（聊天固定 id=1 的覆盖语义保留并登记在册）。
 */
internal object CompletionNotifications {
    /** 应用是否在前台（STARTED 及以上）。媒体与 agent run 的抑制判定共用。 */
    fun isAppForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * 完成类通知的统一发送入口：路由可用性预检通过才发，否则静默跳过。
 * 频道必须已创建（[notificationRouteAvailable] 对不存在的频道返回 false）——
 * 各域在调用前保证频道就绪（RikkaHubApp 启动清单或调用点自建）。
 */
internal fun Context.notifyCompletion(
    channelId: String,
    notificationId: Int,
    config: app.androidrik.rikkahub.utils.NotificationConfig.() -> Unit,
): Boolean {
    if (!notificationRouteAvailable(this, channelId)) return false
    return sendNotification(channelId, notificationId, config)
}
