package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.canResumeToolExecution
import app.androidrik.ai.ui.finishPendingTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「插队发送」的打断语义（用户 2026-10-13 拍板）：
 * **两条路径都写 `Denied`，只是理由不同**（用户取消 / 插队发送）。
 *
 * ## 这条修正是有意偏离历史行为的
 *
 * `cancelToolByUser` 原先**不**写 `approvalState`，注释理由是「cancel 后 output 非空
 * ⇒ 不会误标成拒绝待处理」。那确实避免了误标，代价是**审批卡片静默消失**——
 * 用户看不出这个工具是被拒绝、还是从未被请求。插队这条新路径上「我这个工具到底怎么了」
 * 必须能回答，所以现在明确写 `Denied` 并带上**区分来源**的理由。
 *
 * ## 本测试能证明什么、不能证明什么
 *
 * - **能**：`InterruptReason` 的两个取值理由互不相同且非空；把「收尾一个 pending 工具」
 *   这套变换（`finishPendingTools` + 写 `output` + 写 `Denied`）作用到真实 `UIMessagePart.Tool`
 *   上之后的**状态契约**——闸门放开（`canResumeToolExecution` 为真、不再 pending），
 *   但 `canResumeExecution` 仍为假（`output` 已填，没有「再批准一次去执行」的余地）。
 * - **不能**：真正调 `ChatService.cancelToolByUser`（private，且构造 `ChatService`
 *   需要 Android Context + Koin 图）。那部分由下面的源码守卫补：钉住「它确实写 Denied、
 *   理由确实取自参数」。
 */
class InterruptSemanticsTest {

    private fun pendingTool() = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "write_file",
        input = "{}",
        approvalState = ToolApprovalState.Pending,
    )

    @Test
    fun `both interrupt reasons are present and tell the user which one happened`() {
        val reasons = InterruptReason.entries

        assertEquals("打断来源应当只有这两种", 2, reasons.size)
        reasons.forEach { reason ->
            assertTrue(
                "理由 ($reason) 不能为空——它直接显示在审批卡片上",
                reason.approvalReason.isNotBlank(),
            )
            assertTrue(
                "给模型的 error 串 ($reason) 不能为空——它会写进工具结果",
                reason.toolOutputError.isNotBlank(),
            )
        }
        assertNotEquals(
            "两种来源的理由必须能区分：用户取消 vs 插队发送，否则卡片上说的不是实情",
            InterruptReason.UserCancelled.approvalReason,
            InterruptReason.QueueJumped.approvalReason,
        )
    }

    @Test
    fun `interrupted pending tool is marked denied with the interrupting reason`() {
        // 复刻 ChatService.cancelToolByUser 的变换（output 填取消结果 + 写 Denied(reason)）
        val reason = InterruptReason.QueueJumped
        val denied = pendingTool().let { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("""{"status":"cancelled","error":"${reason.toolOutputError}"}""")),
                approvalState = ToolApprovalState.Denied(reason = reason.approvalReason),
            )
        }

        val deniedState = denied.approvalState
        assertTrue("必须是 Denied（不是 Pending）", deniedState is ToolApprovalState.Denied)
        assertEquals(
            "理由要落到卡片上，且是这条路径的理由",
            reason.approvalReason,
            (deniedState as ToolApprovalState.Denied).reason,
        )
        assertFalse("已收尾的工具不再算 pending", denied.isPending)
        assertTrue(
            "审批闸门必须放开，否则队列派发会被这一项挡住",
            denied.approvalState.canResumeToolExecution(),
        )
        assertFalse(
            "但不能再「批准一次去执行」——output 已填，工具不再有执行余地",
            denied.canResumeExecution,
        )
    }

    @Test
    fun `denied reason survives the finishPendingTools transform applied by the service`() {
        // 服务里真正的调用形态是 finishPendingTools { cancelToolByUser(it, reason) }
        val reason = InterruptReason.UserCancelled
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool()),
        )

        val updated = message.finishPendingTools { tool ->
            tool.copy(approvalState = ToolApprovalState.Denied(reason = reason.approvalReason))
        }

        val tool = updated.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(
            reason.approvalReason,
            (tool.approvalState as ToolApprovalState.Denied).reason,
        )
    }

    /**
     * 源码守卫：`cancelToolByUser` 必须**同时**写 `Denied` 与取自参数的 reason，
     * 且两条打断路径确实传了不同的 reason。
     *
     * 这是**防回退**的钉子：把 `approvalState = ...` 那行删掉，编译与上面那些行为测试
     * 都不会报（它们复刻的是变换本身，不是服务的调用），只有这条会。
     */
    @Test
    fun `service wires the reason through to both interrupt paths`() {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val source = root.resolve("app/androidrik/rikkahub/service/ChatService.kt").readText()

        assertTrue(
            "cancelToolByUser 必须写 approvalState = ToolApprovalState.Denied(...)，" +
                "否则审批卡片静默消失、用户看不出工具怎么了",
            source.contains("approvalState = ToolApprovalState.Denied(reason = reason.approvalReason)"),
        )
        assertTrue(
            "stopGeneration 必须能接收打断来源（默认 UserCancelled 保持「点停止」语义）",
            source.contains("reason: InterruptReason = InterruptReason.UserCancelled"),
        )
        assertTrue(
            "插队发送必须标记成 QueueJumped，否则审批卡片会把插队说成「用户取消」",
            source.contains("reason = InterruptReason.QueueJumped"),
        )
        assertTrue(
            "插队路径必须复用 stopGeneration（而不是自己另写一套取消），" +
                "两条路径共用同一套半截落库/待审批收尾",
            source.contains("fun sendInterrupting("),
        )
    }
}
