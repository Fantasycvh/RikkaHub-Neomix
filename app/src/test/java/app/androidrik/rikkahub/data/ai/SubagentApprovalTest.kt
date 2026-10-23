package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun `foreground requests disappear when answered from any entry point`() = runBlocking {
        val tools = listOf(tool("foreground-a"), tool("foreground-b"))
        val waiters = SubagentApprovalQueue.registerBatch("run", "Agent", tools)
        try {
            val requests = SubagentApprovalQueue.requests.value.filter { it.runId == "run" }
            assertEquals(tools, requests.map { it.tool })
            assertEquals("Agent", requests.first().label)
            assertTrue(SubagentApprovalQueue.resolve("foreground-a", true))
            assertTrue(waiters.getValue("foreground-a").await())
            assertFalse(SubagentApprovalQueue.resolve("foreground-a", false))
            assertEquals(
                listOf("foreground-b"),
                SubagentApprovalQueue.requests.value.filter { it.runId == "run" }.map { it.tool.toolCallId },
            )
        } finally {
            tools.forEach { SubagentApprovalQueue.forget(it.toolCallId) }
        }
        assertTrue(SubagentApprovalQueue.requests.value.none { it.runId == "run" })
    }

    @Test
    fun `timeout cleanup removes unanswered foreground requests`() {
        SubagentApprovalQueue.registerBatch("expired-run", "Agent", listOf(tool("expired-tool")))
        SubagentApprovalQueue.forget("expired-tool")
        assertTrue(SubagentApprovalQueue.requests.value.none { it.runId == "expired-run" })
        assertFalse(SubagentApprovalQueue.resolve("expired-tool", true))
    }

    @Test
    fun `batch publishes parent conversation id for every tool`() {
        val runId = UUID.randomUUID().toString()
        val parentId = UUID.randomUUID().toString()
        val tools = listOf(tool("$runId-a"), tool("$runId-b"))
        try {
            SubagentApprovalQueue.registerBatch(runId, "Child", tools, parentConversationId = parentId)
            val requests = SubagentApprovalQueue.requests.value.filter { it.runId == runId }
            assertEquals(2, requests.size)
            assertEquals(listOf(parentId, parentId), requests.map { it.parentConversationId })
            assertEquals(tools, requests.map { it.tool })
        } finally {
            tools.forEach { SubagentApprovalQueue.forget(it.toolCallId) }
        }
    }

    @Test
    fun `two parents select only their requests and exclude manual runs`() {
        val prefix = UUID.randomUUID().toString()
        val parentA = "$prefix-parent-a"
        val parentB = "$prefix-parent-b"
        val tools = listOf(tool("$prefix-a"), tool("$prefix-b"), tool("$prefix-manual"))
        try {
            SubagentApprovalQueue.registerBatch("$prefix-run-a", "A", listOf(tools[0]), parentA)
            SubagentApprovalQueue.registerBatch("$prefix-run-b", "B", listOf(tools[1]), parentB)
            // Manual runs deliberately omit the optional parent association.
            SubagentApprovalQueue.registerBatch("$prefix-run-manual", "Manual", listOf(tools[2]))
            val requests = SubagentApprovalQueue.requests.value
            // Same exact-match selection as ChatFloatingWindows, never substring matching.
            assertEquals(listOf(tools[0]), requests.filter { it.parentConversationId == parentA }.map { it.tool })
            assertEquals(listOf(tools[1]), requests.filter { it.parentConversationId == parentB }.map { it.tool })
            assertTrue(requests.none { it.parentConversationId == "$parentA-other" })
            assertEquals(null, requests.single { it.tool.toolCallId == tools[2].toolCallId }.parentConversationId)
            assertEquals(null, SubagentApprovalQueue.Request("manual", "Manual", tools[2]).parentConversationId)
        } finally {
            tools.forEach { SubagentApprovalQueue.forget(it.toolCallId) }
        }
    }

    @Test
    fun `resolving one parent leaves another parent request and waiter intact`() = runBlocking {
        val prefix = UUID.randomUUID().toString()
        val toolA = tool("$prefix-a")
        val toolB = tool("$prefix-b")
        try {
            val waiterA = SubagentApprovalQueue.registerBatch("$prefix-run-a", "A", listOf(toolA), "parent-a")
                .getValue(toolA.toolCallId)
            val waiterB = SubagentApprovalQueue.registerBatch("$prefix-run-b", "B", listOf(toolB), "parent-b")
                .getValue(toolB.toolCallId)
            assertTrue(SubagentApprovalQueue.resolve(toolA.toolCallId, true))
            assertTrue(waiterA.await())
            assertFalse(waiterB.isCompleted)
            assertEquals(
                listOf(toolB),
                SubagentApprovalQueue.requests.value.filter { it.runId == "$prefix-run-b" }.map { it.tool },
            )
            assertTrue(SubagentApprovalQueue.resolve(toolB.toolCallId, false))
            assertFalse(waiterB.await())
        } finally {
            SubagentApprovalQueue.forget(toolA.toolCallId)
            SubagentApprovalQueue.forget(toolB.toolCallId)
        }
    }

    @Test
    fun `competing overlay and notification decisions deliver exactly once`() = runBlocking {
        val id = UUID.randomUUID().toString()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val waiter = SubagentApprovalQueue.registerBatch(id, "Agent", listOf(tool(id)), "parent")
                .getValue(id)
            val attempts = listOf(true, false).map { decision ->
                executor.submit<Pair<Boolean, Boolean>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "decision start timed out" }
                    decision to SubagentApprovalQueue.resolve(id, decision)
                }
            }
            assertTrue("both decision entry points must be ready", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
            val winner = results.single { it.second }
            assertEquals(1, results.count { !it.second })
            assertTrue(waiter.isCompleted)
            assertEquals(winner.first, waiter.await())
            assertTrue(SubagentApprovalQueue.requests.value.none { it.tool.toolCallId == id })
            assertFalse(SubagentApprovalQueue.resolve(id, !winner.first))
        } finally {
            start.countDown()
            executor.shutdownNow()
            SubagentApprovalQueue.forget(id)
        }
    }

    @Test
    fun `stale snapshot cannot approve reused tool id belonging to another parent run`() = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            val staleWaiter = SubagentApprovalQueue.registerBatch("old-$id", "Old", listOf(tool(id)), "parent-a")
                .getValue(id)
            val stale = SubagentApprovalQueue.requests.value.single { it.tool.toolCallId == id }
            val freshWaiter = SubagentApprovalQueue.registerBatch("new-$id", "New", listOf(tool(id)), "parent-b")
                .getValue(id)
            val fresh = SubagentApprovalQueue.requests.value.single { it.tool.toolCallId == id }
            assertTrue(staleWaiter.isCompleted)
            assertFalse(staleWaiter.await())
            assertEquals("parent-b", fresh.parentConversationId)
            assertFalse(SubagentApprovalQueue.resolve(stale, approved = true))
            assertFalse(SubagentApprovalQueue.resolve(stale, approved = false))
            assertFalse(freshWaiter.isCompleted)
            assertSame(fresh, SubagentApprovalQueue.requests.value.single { it.tool.toolCallId == id })
            assertTrue(SubagentApprovalQueue.resolve(fresh, approved = true))
            assertTrue(freshWaiter.await())
            assertFalse(SubagentApprovalQueue.resolve(fresh, approved = false))
        } finally {
            SubagentApprovalQueue.forget(id)
        }
    }

    @Test
    fun `equal request fields do not let an old or copied snapshot resolve a new registration`() = runBlocking {
        val id = UUID.randomUUID().toString()
        val tools = listOf(tool(id))
        try {
            val staleWaiter = SubagentApprovalQueue.registerBatch(id, "Agent", tools, "parent").getValue(id)
            val stale = SubagentApprovalQueue.requests.value.single { it.tool.toolCallId == id }
            val freshWaiter = SubagentApprovalQueue.registerBatch(id, "Agent", tools, "parent").getValue(id)
            val fresh = SubagentApprovalQueue.requests.value.single { it.tool.toolCallId == id }
            assertEquals("payload fields intentionally equal", stale.copy(registrationId = fresh.registrationId), fresh)
            assertFalse("fresh registration must not be conflated by StateFlow", stale.registrationId == fresh.registrationId)
            assertFalse("registration identity must still differ", stale === fresh)
            assertTrue(staleWaiter.isCompleted)
            assertFalse(staleWaiter.await())
            assertFalse(SubagentApprovalQueue.resolve(stale, approved = true))
            assertFalse(SubagentApprovalQueue.resolve(fresh.copy(), approved = true))
            assertFalse(freshWaiter.isCompleted)
            assertSame(fresh, SubagentApprovalQueue.requests.value.single { it.tool.toolCallId == id })
            assertTrue(SubagentApprovalQueue.resolve(fresh, approved = false))
            assertFalse(freshWaiter.await())
            assertTrue(SubagentApprovalQueue.requests.value.none { it.tool.toolCallId == id })
        } finally {
            SubagentApprovalQueue.forget(id)
        }
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
