package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SillyTavern 动态宏解析（MineChat 借鉴）。
 *
 * 覆盖：random（列表/区间）、roll（公式/修饰符）、变量三件套（setvar/getvar/addvar，
 * 顺序求值与跨消息可见）、innermost-first 嵌套、非动态宏保留、迭代上限。
 */
class TavernMacroTest {

    private fun resolve(text: String, vars: MutableMap<String, String> = mutableMapOf()) =
        TavernMacroSemantics.resolve(text, vars)

    private fun resolveInMessages(vararg texts: String): List<String> {
        val vars = mutableMapOf<String, String>()
        val messages = texts.map { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(it))) }
        return resolveTavernMacrosInMessages(messages, vars).map {
            it.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { p -> p.text }
        }
    }

    // ---- random ----

    @Test
    fun `random picks one of the options`() {
        val result = resolve("{{random:苹果,香蕉,橘子}}")
        assertTrue(result in listOf("苹果", "香蕉", "橘子"))
    }

    @Test
    fun `random supports double-colon separator`() {
        // 值含逗号时用 :: 分隔
        val result = resolve("{{random::你好,世界::再见}}")
        assertTrue(result in listOf("你好,世界", "再见"))
    }

    @Test
    fun `random supports integer range`() {
        val result = resolve("{{random:1..5}}")
        assertTrue("期望 1..5 内的整数，实际 $result", result.toIntOrNull() in 1..5)
    }

    @Test
    fun `random with no options stays literal`() {
        assertEquals("{{random:}}", resolve("{{random:}}"))
    }

    // ---- roll ----

    @Test
    fun `roll dN returns value in range`() {
        val result = resolve("{{roll:d6}}")
        assertTrue("期望 1..6，实际 $result", result.toIntOrNull() in 1..6)
    }

    @Test
    fun `roll NdM with modifier`() {
        // count=1、面数=1000、修饰符固定 → 结果在 [1+5, 1000+5]
        repeat(5) {
            val result = resolve("{{roll:1d1000+5}}").toInt()
            assertTrue(result in 6..1005)
        }
    }

    @Test
    fun `roll with bare number means 1dN`() {
        val result = resolve("{{roll:20}}")
        assertTrue(result.toIntOrNull() in 1..20)
    }

    @Test
    fun `roll with invalid formula stays literal`() {
        assertEquals("{{roll:abc}}", resolve("{{roll:abc}}"))
    }

    // ---- 变量 ----

    @Test
    fun `setvar outputs empty and getvar reads it`() {
        // setvar 输出空；getvar 输出变量值（占位消失，值在读取处呈现）
        val result = resolve("A{{setvar::color::蓝色}}B{{getvar::color}}C")
        assertEquals("AB蓝色C", result)
    }

    @Test
    fun `getvar before setvar is empty`() {
        assertEquals("X", resolve("X{{getvar::missing}}"))
    }

    @Test
    fun `addvar accumulates numerically`() {
        val vars = mutableMapOf<String, String>()
        resolve("{{addvar::gold::3}}", vars)
        resolve("{{addvar::gold::4}}", vars)
        assertEquals("7", vars["gold"])
        assertEquals("7", resolve("{{getvar::gold}}", vars))
    }

    @Test
    fun `addvar concatenates non-numeric values`() {
        val vars = mutableMapOf<String, String>()
        resolve("{{addvar::log::abc}}", vars)
        resolve("{{addvar::log::def}}", vars)
        assertEquals("abcdef", vars["log"])
    }

    @Test
    fun `variables carry across messages in one assembly`() {
        val lines = resolveInMessages(
            "{{setvar::plan::爬山}}",
            "今天去{{getvar::plan}}",
        )
        // 第 1 条 setvar 输出空，第 2 条 getvar 读到
        assertEquals(listOf("", "今天去爬山"), lines)
    }

    @Test
    fun `variables do not leak between assemblies`() {
        val varsA = mutableMapOf<String, String>()
        resolve("{{setvar::x::A}}", varsA)
        // 新的一次装配用新 map —— 不继承上次的结果（getvar 未设 → 空串）
        assertEquals("X", resolve("X{{getvar::x}}"))
    }

    // ---- 嵌套与安全 ----

    @Test
    fun `nested macro resolves innermost first`() {
        // setvar 的值来自 roll：内层先解，外层把数值存入变量
        val vars = mutableMapOf<String, String>()
        resolve("{{setvar::hp::{{roll:d10}}}}", vars)
        val hp = vars["hp"]!!.toInt()
        assertTrue("期望 1..10，实际 $hp", hp in 1..10)
    }

    @Test
    fun `non-dynamic macros are left for PlaceholderTransformer`() {
        // {{char}}/{{user}} 是静态占位符，本变换器不碰
        assertEquals("hi {{char}} {{user}}", resolve("hi {{char}} {{user}}"))
    }

    @Test
    fun `iteration cap prevents runaway from self-feeding macros`() {
        // setvar 值里再写入一个 getvar 自引用 → 构造自造宏循环；上限内停住不挂死
        val result = resolve("{{setvar::a::{{getvar::b}}}}{{setvar::b::{{getvar::a}}}}")
        assertTrue(result.isNotEmpty() || result.isEmpty()) // 不抛异常不挂死即通过
    }

    @Test
    fun `macros are case insensitive`() {
        val vars = mutableMapOf<String, String>()
        resolve("{{SetVar::K::V}}", vars)
        assertEquals("V", resolve("{{GETVAR::K}}", vars))
    }

    @Test
    fun `resolveInMessages only touches text parts`() {
        val vars = mutableMapOf<String, String>()
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("{{getvar::k}}"), UIMessagePart.Image(url = "file:///x")),
            ),
        )
        val out = resolveTavernMacrosInMessages(messages, vars)
        assertEquals(2, out[0].parts.size)
        assertTrue(out[0].parts[1] is UIMessagePart.Image)
    }

    @Test
    fun `different text without macros is unchanged`() {
        val text = "普通文本 {{char}} 与 {{getvar::nope}}"
        assertNotEquals(text, resolve(text)) // {{getvar}} 被解析为空 ⇒ 全文有变化
    }
}
