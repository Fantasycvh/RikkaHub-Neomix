package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理的**人的审批**（设计 §十一）纯逻辑：
 * 待批队列、待批工具筛选、决定回填、通知摘要。
 *
 * 重点钉住四组契约（都是"错了会静默出问题"的地方）：
 * ① 队列必须能对上号（`resolve` 的返回值就是"没人等"的唯一信号，UI 据此提示而不是假装成功）；
 * ② **待批工具要扫全部消息**（只看最后一条会漏 → run 卡到整体超时，真机表现为"卡住"）；
 * ③ 回填只碰匹配到的 part，其余消息**原对象返回**（续跑靠"入参即协议"，动多了会破坏它）；
 * ④ 摘要要能一行放下（通知栏），入参里的换行必须先压平。
 */
class SubagentApprovalTest {

    private fun tool(
        id: String,
        name: String = "workspace_shell",
        input: String = """{"command":"echo hi"}""",
        state: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
        approvalState = state,
    )

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    // ---- 待批队列 ----

    @Test
    fun `resolve delivers the decision to the waiter`() = runBlocking {
        val waiter = SubagentApprovalQueue.register("call-1")

        assertTrue(SubagentApprovalQueue.resolve("call-1", approved = true))
        assertTrue(waiter.await())
        SubagentApprovalQueue.forget("call-1")
        // 队列是**进程级单例**（run 与通知按钮同进程），所以断言"这一个 id 已撤销"而不是全局计数：
        // 全局计数会被同 JVM 里的其它用例影响（这里刻意不写那样脆弱的断言）
        assertFalse(SubagentApprovalQueue.resolve("call-1", approved = true))
    }

    @Test
    fun `resolve on an unknown tool reports nobody is waiting`() {
        // 超时后点通知、或进程重启后点旧通知——都必须得到 false，UI 才能如实提示
        assertFalse(SubagentApprovalQueue.resolve("never-registered", approved = true))
    }

    @Test
    fun `forget stops accepting decisions`() {
        SubagentApprovalQueue.register("call-2")
        SubagentApprovalQueue.forget("call-2")

        assertFalse(SubagentApprovalQueue.resolve("call-2", approved = true))
    }

    @Test
    fun `re-registering a tool does not strand the old waiter`() = runBlocking {
        // 同一 toolCallId 被登记两次（不该发生，但真发生也必须让旧等待者醒过来）：
        // 否则旧的 await 永远挂着 = run 永远不返回
        val stale = SubagentApprovalQueue.register("call-3")
        val fresh = SubagentApprovalQueue.register("call-3")

        assertFalse("stale waiter must be released as denied", stale.await())
        assertTrue(SubagentApprovalQueue.resolve("call-3", approved = true))
        assertTrue(fresh.await())
        SubagentApprovalQueue.forget("call-3")
    }

    // ---- 待批工具筛选 ----

    @Test
    fun `pending tools are picked from every message`() {
        val messages = listOf(
            assistant(tool("a", state = ToolApprovalState.Approved)),
            assistant(tool("b", state = ToolApprovalState.Pending)),
            assistant(tool("c", state = ToolApprovalState.Pending)),
        )

        assertEquals(listOf("b", "c"), pendingApprovalTools(messages).map { it.toolCallId })
    }

    @Test
    fun `auto and answered tools are not pending`() {
        val messages = listOf(
            assistant(
                tool("auto", state = ToolApprovalState.Auto),
                tool("denied", state = ToolApprovalState.Denied("nope")),
                tool("answered", state = ToolApprovalState.Answered("42")),
            )
        )

        assertTrue(pendingApprovalTools(messages).isEmpty())
    }

    @Test
    fun `executed tools are not pending even if the state says so`() {
        // 已执行（有输出）的工具不该再问一遍——协议上它的结果已经回填过了
        val executed = tool("done", state = ToolApprovalState.Pending)
            .copy(output = listOf(UIMessagePart.Text("result")))
        val messages = listOf(assistant(executed))

        assertTrue(pendingApprovalTools(messages).isEmpty())
    }

    // ---- 决定回填 ----

    @Test
    fun `decisions replace only the matching tool parts`() {
        val messages = listOf(
            UIMessage.user("do it"),
            assistant(tool("a"), tool("b")),
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("older result"))),
        )
        val updated = applyApprovalDecisions(
            messages,
            mapOf("b" to ToolApprovalState.Approved),
        )

        val tools = updated[1].parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(ToolApprovalState.Auto, tools[0].approvalState)
        assertEquals(ToolApprovalState.Approved, tools[1].approvalState)
        // 没被碰到的消息必须**原对象**返回（续跑把整个列表当协议用，动多了会改变语义）
        assertSame(messages[0], updated[0])
        assertSame(messages[2], updated[2])
    }

    @Test
    fun `a denial carries the reason the model will read`() {
        val messages = listOf(assistant(tool("a")))
        val updated = applyApprovalDecisions(
            messages,
            mapOf("a" to ToolApprovalState.Denied(USER_DENIED_REASON)),
        )

        val state = updated[0].parts.filterIsInstance<UIMessagePart.Tool>().single().approvalState
        assertEquals(ToolApprovalState.Denied(USER_DENIED_REASON), state)
        // 拒绝文本必须是"策略终态"口径（§9.2.1）：明说别重试、别绕路
        assertTrue(USER_DENIED_REASON.contains("Do not retry"))
        assertTrue(APPROVAL_TIMEOUT_REASON.contains("Do not retry"))
        // 两条措辞必须不同：手拒 = 针对这个工具，超时 = 只是没人应答
        assertFalse(USER_DENIED_REASON == APPROVAL_TIMEOUT_REASON)
    }

    @Test
    fun `empty decisions return the very same list`() {
        val messages = listOf(assistant(tool("a", state = ToolApprovalState.Pending)))

        assertSame(messages, applyApprovalDecisions(messages, emptyMap()))
    }

    // ---- 通知摘要 ----

    @Test
    fun `summary collapses whitespace and truncates long input`() {
        val long = tool("a", name = "workspace_write_file", input = "line1\n\tline2   " + "x".repeat(400))
        val summary = approvalRequestSummary(long, maxChars = 40)

        assertTrue(summary.startsWith("workspace_write_file line1 line2"))
        assertFalse("no raw newlines in a notification line", summary.contains("\n"))
        assertTrue(summary.length <= "workspace_write_file ".length + 41)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `summary falls back to the tool name when there is no input`() {
        assertEquals("workspace_shell", approvalRequestSummary(tool("a", input = "")))
    }
}
