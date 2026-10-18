package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送前护栏 [balanceToolCallResults] 的不变量测试。
 *
 * 每条用例都对准一类真实故障形态（见 `ToolResultBalancingTransformer` 的类注释：
 * Gemini 报 `the number of function response parts is equal to the number of function call parts`）。
 * 「原样通过」用 `assertSame` 钉住——不仅内容相同，**同一个实例**才算没改动。
 */
class ToolResultBalancingTest {

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun call(id: String, name: String = "workspace_shell") =
        UIMessagePart.Tool(toolCallId = id, toolName = name, input = """{"command":"python3 x.py"}""")

    private fun callWithResult(id: String, text: String = "ok", name: String = "workspace_shell") =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = name,
            input = """{"command":"ls"}""",
            output = listOf(UIMessagePart.Text(text)),
        )

    @Suppress("DEPRECATION")
    private fun legacyResult(toolCallId: String) = UIMessagePart.ToolResult(
        toolCallId = toolCallId,
        toolName = "workspace_shell",
        content = JsonPrimitive("legacy result"),
        arguments = JsonPrimitive("{}"),
    )

    private fun List<UIMessage>.toolCalls() = flatMap { it.getTools() }

    // ---- 1. call 有、result 缺 ⇒ 补出一条合成结果 ----

    @Test
    fun `missing tool result is synthesized`() {
        val messages = listOf(user("run it"), assistant(call("call-1")))

        val balancing = balanceToolCallResults(messages)

        assertEquals("应补 1 条", 1, balancing.synthesized)
        assertEquals("没有孤儿结果可丢", 0, balancing.dropped)
        assertTrue("有修正就该报告 changed", balancing.changed)

        val tool = balancing.messages.toolCalls().single()
        assertEquals("call 本身不能被剔除", "call-1", tool.toolCallId)
        assertEquals("工具名不能被改写", "workspace_shell", tool.toolName)
        assertEquals("现在 call 与 result 数量相等", 1, tool.output.size)
    }

    @Test
    fun `synthesized result is valid json that self-identifies as synthesized`() {
        val messages = listOf(user("run it"), assistant(call("call-1")))

        val text = balanceToolCallResults(messages).messages.toolCalls().single()
            .output.filterIsInstance<UIMessagePart.Text>().single().text
        val json = Json.parseToJsonElement(text).jsonObject

        assertEquals(
            "正文要说明「结果拿不到了、等用户决定」",
            "unavailable — the app was interrupted before saving this tool's output; " +
                "do not re-run it automatically, ask the user first",
            json["result"]?.jsonPrimitive?.content,
        )
        assertTrue(
            "必须能看出是系统补的：$text",
            json["_meta"]?.jsonObject?.get("synthesized")?.jsonPrimitive?.boolean == true,
        )
    }

    /**
     * ② 的**反重试**契约（2026-10-18 核查项）：合成结果里不能出现任何「工具执行失败」的暗示。
     *
     * 现场：模型把 `{"error":"tool result missing"}` 读成「这次调用失败了」⇒ 用同样的参数
     * 重发带副作用的调用（已在修「空输出重跑」的证伪里实际观察到）。
     * 所以这里逐字扫**整个合成正文**（不只 `result` 字段），任何失败语义的词都算回归。
     */
    @Test
    fun `synthesized result never suggests the tool failed`() {
        val messages = listOf(user("run it"), assistant(call("call-1")))
        val text = balanceToolCallResults(messages).messages.toolCalls().single()
            .output.filterIsInstance<UIMessagePart.Text>().single().text
        val lower = text.lowercase()

        listOf("error", "failed", "failure", "missing", "exception", "retry").forEach { word ->
            assertFalse("合成文案不得出现「$word」（会诱发模型自动重发同一调用）：$text", word in lower)
        }
        // 明确要求「不要自动重跑、先问用户」
        assertTrue("必须显式劝阻自动重试：$text", "do not re-run it automatically" in lower)
        assertTrue("必须要求先问用户：$text", "ask the user first" in lower)
        // 机器可读标记保留（下游/日志据此识别合成结果）
        assertTrue("必须保留 synthesized 标记：$text", "\"synthesized\":true" in text.replace(" ", ""))
    }

    // ---- 2. call 与 result 都有且配对 ⇒ 原样通过 ----

    @Test
    fun `paired call and result pass through untouched`() {
        val messages = listOf(user("list files"), assistant(callWithResult("call-1")))

        val balancing = balanceToolCallResults(messages)

        assertEquals(0, balancing.synthesized)
        assertEquals(0, balancing.dropped)
        assertFalse("没有任何修正时不应报告 changed", balancing.changed)
        assertSame("必须原样返回同一个列表实例", messages, balancing.messages)
        assertSame("part 实例也不得重建", messages[1].parts[0], balancing.messages[1].parts[0])
        // 便捷入口（带日志的那层）在无修正时同样原样返回
        assertSame(messages, messages.balancedForRequest())
    }

    // ---- 3. 孤儿 result（无 call）⇒ 丢弃 ----

    @Suppress("DEPRECATION")
    @Test
    fun `orphan tool result is dropped`() {
        val messages = listOf(
            user("hi"),
            assistant(UIMessagePart.Text("done"), legacyResult("ghost-call")),
        )

        val balancing = balanceToolCallResults(messages)

        assertEquals("一条孤儿结果被丢", 1, balancing.dropped)
        assertEquals("没有 call 缺结果", 0, balancing.synthesized)
        assertTrue(
            "孤儿结果不该出现在修正后的列表里",
            balancing.messages.none { it.parts.any { part -> part is UIMessagePart.ToolResult } },
        )
        assertEquals("同一条消息里的其它 part 必须保留", UIMessagePart.Text("done"), balancing.messages[1].parts.single())
    }

    // ---- 4. 多个 call、只有部分 result ⇒ 逐个补齐 ----

    @Test
    fun `partially answered parallel calls are filled one by one`() {
        val messages = listOf(
            user("run three things"),
            assistant(callWithResult("call-1"), call("call-2"), call("call-3")),
        )

        val balancing = balanceToolCallResults(messages)

        assertEquals("缺两条就补两条", 2, balancing.synthesized)
        assertEquals(0, balancing.dropped)
        val tools = balancing.messages.toolCalls()
        assertEquals(listOf("call-1", "call-2", "call-3"), tools.map { it.toolCallId })
        assertTrue("每个 call 都必须有结果", tools.all { it.output.isNotEmpty() })
        assertEquals("已存在的结果不能被覆盖", "ok", tools[0].output.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    // ---- 5. 无工具的消息 ⇒ 不受影响 ----

    @Test
    fun `messages without tools are untouched`() {
        val messages = listOf(
            user("hello"),
            assistant(UIMessagePart.Text("hi")),
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("plain tool text"))),
        )

        val balancing = balanceToolCallResults(messages)

        assertEquals(0, balancing.synthesized)
        assertEquals(0, balancing.dropped)
        assertSame(messages, balancing.messages)
    }

    // ---- 6. 顺序：call/result 交错但可配对 ⇒ 不被重排 ----

    @Suppress("DEPRECATION")
    @Test
    fun `interleaved calls and results keep their original order`() {
        val messages = listOf(
            user("go"),
            // c1 缺结果；杂项 part 顺序为 [Text, Tool]
            assistant(UIMessagePart.Text("first step"), call("call-1")),
            // 两个 assistant 消息之间夹着一条旧式结果（配对存在 ⇒ 不算孤儿）
            assistant(legacyResult("call-2"), callWithResult("call-2")),
            user("next"),
            assistant(UIMessagePart.Text("final")),
        )
        val originalIds = messages.map { it.id }

        val balancing = balanceToolCallResults(messages)

        assertEquals(1, balancing.synthesized)
        assertEquals("能配对上的旧式结果不算孤儿", 0, balancing.dropped)
        assertEquals("消息顺序不得改变", originalIds, balancing.messages.map { it.id })
        assertEquals(
            "后两条消息的内容也不能被搬动",
            originalIds.subList(2, 5),
            balancing.messages.subList(2, 5).map { it.id },
        )
        assertTrue("Text 仍排在补过结果的 call 之前", balancing.messages[1].parts[0] is UIMessagePart.Text)
        assertTrue("旧式结果仍在原位置（part 0）", balancing.messages[2].parts[0] is UIMessagePart.ToolResult)
        assertTrue("配对的 call 仍在 part 1", balancing.messages[2].parts[1] is UIMessagePart.Tool)
    }

    // ---- 传输层：修正只发生在派生列表上，输入不被就地修改 ----

    @Test
    fun `balancing is purely derived - input list is not mutated`() {
        val missing = call("call-1")
        val messages = listOf(user("run it"), assistant(missing))

        val balanced = messages.balancedForRequest()

        assertTrue("派生列表里的 call 补到了结果", balanced.toolCalls().single().output.isNotEmpty())
        assertTrue("原对象必须保持「未执行」——绝不改历史/数据库", missing.output.isEmpty())
        assertTrue("原列表里的 part 也没被动过", messages[1].parts.single() === missing)
    }
}
