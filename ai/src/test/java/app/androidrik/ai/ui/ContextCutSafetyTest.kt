package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")

/**
 * 共享安全切点判定（design-context-boundaries-and-overflow-recovery §2.1）：
 * R1 原子配对（toolCallId 精确配对）/ R2 轮语义（半截调用不开场）/ R3 轮边界偏好（D143）。
 *
 * limitContext 集成路径的行为（含既有契约回归）见 `MessageTest`。
 */
class ContextCutSafetyTest {

    private fun user(text: String = "u") = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun text(value: String) = UIMessagePart.Text(value)

    private fun pendingTool(id: String) = UIMessagePart.Tool(
        toolCallId = id, toolName = "t", input = "{}", output = emptyList(),
    )

    private fun executedTool(id: String) = UIMessagePart.Tool(
        toolCallId = id, toolName = "t", input = "{}", output = listOf(UIMessagePart.Text("result")),
    )

    private fun legacyCall(id: String) =
        UIMessagePart.ToolCall(toolCallId = id, toolName = "t", arguments = "{}")

    private fun legacyResult(id: String) = UIMessagePart.ToolResult(
        toolCallId = id, toolName = "t", content = JsonPrimitive("ok"), arguments = JsonPrimitive("{}"),
    )

    // ==================== R3 轮边界偏好（D143） ====================

    @Test
    fun `candidate inside an assistant turn aligns back to the nearest user turn start`() {
        val messages = listOf(
            user("q1"), assistant(text("a1")), assistant(text("a2")), assistant(text("a3")),
            user("q2"), assistant(text("a4")),
        )
        // 候选 3 落在 q1 轮的助手区 ⇒ 回退到 q1 轮起点 0（轮对齐只向前找，4 > 候选够不着）
        assertEquals(0, messages.alignCutIndex(3))
        // 候选 4 本身就是 USER 轮起点 ⇒ 维持
        assertEquals(4, messages.alignCutIndex(4))
    }

    @Test
    fun `candidate with no user turn before it stays as is`() {
        val messages = listOf(assistant(text("a1")), assistant(text("a2")), assistant(text("a3")))
        assertEquals(2, messages.alignCutIndex(2))
    }

    @Test
    fun `external notice and imported checkpoint do not count as user turn starts`() {
        val notice = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Notice(eventId = "n1", text = "workspace result")),
        )
        val checkpoint = UIMessage(
            role = MessageRole.USER,
            parts = listOf(text("imported summary")),
            isContextCheckpoint = true,
        )
        val messages = listOf(assistant(text("a1")), notice, checkpoint, user("q"), assistant(text("a2")))
        // 候选 4：最近的合法轮起点是 3（真实 USER），通知 1 与检查点 2 都不算
        assertEquals(3, messages.alignCutIndex(4))
    }

    // ==================== R1 原子配对（toolCallId 精确配对） ====================

    @Test
    fun `straddle without turn preference snaps to the call message`() {
        val messages = listOf(
            user("q0"),
            assistant(text("a0")),
            assistant(legacyCall("r1")),
            assistant(legacyResult("r1")),
            assistant(text("a")),
        )
        // 候选 3 会把 call(2) 留在丢弃侧、result(3) 留在保留区 ⇒ 骑线，回退到调用消息 2
        assertEquals(2, messages.alignCutIndex(3, preferUserTurn = false))
        // 轮对齐版本：候选 3 先落到 USER(0)，R1 区域扫描把整段带回 0（call(2) 必须与 result(3) 同侧）
        assertEquals(0, messages.alignCutIndex(3))
    }

    @Test
    fun `legacy result whose call is dropped forces the cut back past the user turn`() {
        val messages = listOf(
            assistant(legacyCall("r1")),
            user("q"),
            assistant(legacyResult("r1")),
            assistant(text("a")),
        )
        // 候选 2：轮对齐先落到 USER(1)，区域扫描发现 result(2) 的 call(0) 在丢弃侧 ⇒ 回退到 0
        //（call 在 0 位 ⇒ 唯一合法切点是不截）
        assertEquals(0, messages.alignCutIndex(2))
    }

    @Test
    fun `executed tool with a duplicate call earlier still counts as a straddle`() {
        // call/result 分处两条消息的形态（MessageTest 既有契约）
        val messages = listOf(
            user("q"),
            assistant(pendingTool("call1")),
            assistant(executedTool("call1")),
            assistant(text("final")),
        )
        // 无轮对齐：候选 2 保留已执行结果，其 call 残留在丢弃侧 1 ⇒ 回退到 1
        assertEquals(1, messages.alignCutIndex(2, preferUserTurn = false))
        // 轮对齐：一路回退到 USER 轮起点 0（与 MessageTest 的 limitContext 契约一致）
        assertEquals(0, messages.alignCutIndex(2))
    }

    @Test
    fun `self contained executed tool needs no straddle walk back`() {
        val messages = listOf(user("q"), assistant(executedTool("call1")), assistant(text("a")))
        // 自含 part 不构成跨消息引用：无轮对齐时候选 2 直接合法
        assertEquals(2, messages.alignCutIndex(2, preferUserTurn = false))
        // 轮对齐仍会把候选带回 USER 轮起点（D143，与配对无关）
        assertEquals(0, messages.alignCutIndex(2))
    }

    @Test
    fun `alignment that pulls results into the kept region is caught by the region scan`() {
        val messages = listOf(
            assistant(legacyCall("r1")),
            user("q"),
            assistant(legacyResult("r1")),
            assistant(text("a")),
        )
        // 候选 3：R3 把切点拉到 USER(1)，result(2) 由此进入保留区而 call(0) 留在丢弃侧
        // ⇒ 对齐引入的骑线，区域扫描兜住，整体回退到 0
        assertEquals(0, messages.alignCutIndex(3))
    }

    @Test
    fun `both dropped sides of a pair never straddle`() {
        val messages = listOf(
            assistant(legacyCall("r1")),
            user("q"),
            assistant(legacyResult("r1")),
            user("q2"),
            assistant(text("a")),
        )
        // 候选 3：call(0) 与 result(2) 同在丢弃侧（一起进摘要）⇒ 无骑线，轮起点 3 直接合法
        assertEquals(3, messages.alignCutIndex(3))
    }

    // ==================== R2 轮语义（半截调用不开场） ====================

    @Test
    fun `user turn start holding a pending call walks back to the earlier user turn`() {
        // USER 消息带旧版未收束调用（病态但合法的遗留形态）：轮起点 1 自身不合法 ⇒ 回退到 0
        val messages = listOf(
            user("q0"),
            UIMessage(role = MessageRole.USER, parts = listOf(legacyCall("call1"))),
            assistant(text("a")),
        )
        assertEquals(0, messages.alignCutIndex(2))
    }

    @Test
    fun `boundary with pending call and no user turn before it stays`() {
        // 旧版行为等价：无 USER 可退时维持候选（出口配平给挂起调用合成结果，协议不破）
        val messages = listOf(assistant(text("a1")), assistant(pendingTool("call1")), assistant(text("a2")))
        assertEquals(2, messages.alignCutIndex(2))
    }

    // ==================== 合约边界 ====================

    @Test
    fun `degenerate candidates are returned unchanged`() {
        val messages = listOf(user("q"), assistant(text("a")))
        assertEquals(0, messages.alignCutIndex(0))
        assertEquals(0, messages.alignCutIndex(-1))
        assertEquals(2, messages.alignCutIndex(2))
        assertEquals(2, messages.alignCutIndex(5))
    }

    @Test
    fun `same input always yields the same cut`() {
        val messages = listOf(
            user("q1"), assistant(executedTool("call1")), assistant(text("a")),
            user("q2"), assistant(text("a2")),
        )
        assertEquals(messages.alignCutIndex(3), messages.alignCutIndex(3))
        assertEquals(messages.alignCutIndex(3, preferUserTurn = false), messages.alignCutIndex(3, preferUserTurn = false))
    }

    // ==================== hasUnfinishedToolCalls ====================

    @Test
    fun `unfinished tool call detection covers modern and legacy shapes`() {
        assertFalse(user("q").hasUnfinishedToolCalls())
        assertFalse(assistant(executedTool("call1")).hasUnfinishedToolCalls())
        assertTrue(assistant(pendingTool("call1")).hasUnfinishedToolCalls())
        @Suppress("DEPRECATION")
        assertTrue(assistant(legacyCall("call1")).hasUnfinishedToolCalls())
    }
}
