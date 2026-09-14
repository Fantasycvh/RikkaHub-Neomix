package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 工具调用去重指纹（[toolCallSignature]）。
 *
 * 背景：部分提供商（实测 Gemini 系）在一次响应里下发**多条内容相同、toolCallId 不同**
 * 的并行 tool_call，逐条执行会重复产生副作用（实测 memory_tool create 重复建记忆）。
 * 指纹必须：①忽略 toolCallId；②对等价 JSON（键序/空白不同）归一。
 */
class ToolCallSignatureTest {

    private val json = JsonInstant

    @Test
    fun `identical tool name and args produce the same signature`() {
        val input = """{"action":"create","content":"User's favorite color is blue."}"""
        assertEquals(
            toolCallSignature("memory_tool", input, json),
            toolCallSignature("memory_tool", input, json),
        )
    }

    @Test
    fun `equivalent json with different key order or whitespace normalizes to same signature`() {
        val a = """{"action":"create","content":"hello"}"""
        val b = """{ "content" : "hello" , "action" : "create" }"""

        assertEquals(
            toolCallSignature("memory_tool", a, json),
            toolCallSignature("memory_tool", b, json),
        )
    }

    @Test
    fun `different args produce different signatures`() {
        assertNotEquals(
            toolCallSignature("memory_tool", """{"action":"create","content":"a"}""", json),
            toolCallSignature("memory_tool", """{"action":"create","content":"b"}""", json),
        )
    }

    @Test
    fun `different tool name with same args produce different signatures`() {
        val args = """{"x":1}"""
        assertNotEquals(
            toolCallSignature("search_web", args, json),
            toolCallSignature("scrape_web", args, json),
        )
    }

    @Test
    fun `same positional content but different action is not deduplicated`() {
        // create 与 edit 携带相同 content 也不应视为重复（副作用完全不同）
        assertNotEquals(
            toolCallSignature("memory_tool", """{"action":"create","content":"x"}""", json),
            toolCallSignature("memory_tool", """{"action":"edit","id":"abc","content":"x"}""", json),
        )
    }

    @Test
    fun `blank and malformed input fall back without throwing`() {
        // 空 input 等价于 "{}"（与执行侧 parse 口径一致）
        assertEquals(
            toolCallSignature("memory_tool", "", json),
            toolCallSignature("memory_tool", "{}", json),
        )
        // 残缺 JSON：不抛，退化为原文（同原文则同指纹）
        assertEquals(
            toolCallSignature("t", "{broken", json),
            toolCallSignature("t", "{broken", json),
        )
    }
}
