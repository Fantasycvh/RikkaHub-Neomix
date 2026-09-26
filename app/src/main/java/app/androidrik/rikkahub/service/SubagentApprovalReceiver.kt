package app.androidrik.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue

private const val TAG = "SubagentApproval"

/**
 * 通知栏「批准 / 拒绝」按钮的落点（设计 §十一）。
 *
 * **为什么用广播而不是打开 Activity**：等批的 run 就在本进程里（run 不跨进程存活），
 * 所以一次广播就能把决定填进去、用户不必离开通知栏。若进程已被杀，队列为空 ⇒
 * `resolve` 返回 false ⇒ 什么都不做（那次 run 其实也已经不存在了），这是正常降级。
 *
 * 安全边界：`exported=false` + 显式组件 Intent ⇒ 只有本应用能触发（审批是**权限动作**，
 * 不能给外部留入口）。Intent 里只带 toolCallId 与布尔，不带任何工具参数。
 */
class SubagentApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayListExtra(EXTRA_TOOL_CALL_IDS).orEmpty()
        if (ids.isEmpty()) return
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)

        var delivered = 0
        ids.forEach { id ->
            if (SubagentApprovalQueue.resolve(id, approved)) delivered += 1
        }
        Logging.log(
            TAG,
            "notification decision approved=$approved tools=${ids.size} delivered=$delivered",
        )
        // 刻意不在这里 cancel 通知：run 侧等待结束时统一撤（本批次可能还有别的工具没答，
        // 提前撤会让人看不到剩下的按钮）。
    }

    companion object {
        const val EXTRA_TOOL_CALL_IDS = "tool_call_ids"
        const val EXTRA_APPROVED = "approved"
    }
}
