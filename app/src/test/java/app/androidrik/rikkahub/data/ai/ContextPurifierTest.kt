package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ClaudeReasoningMetadata
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextPurifier] 的规则与不变式测试（`design-context-purification.md` §10）。
 *
 * 重点三块：各规则命中/不命中；**合法内容零误杀**（复用护栏标定过的样本形态 + 补设计稿
 * 点名的 CSS fixture）；不变式（USER/合成/检查点/签名 part 不碰、幂等、同输入同输出、
 * 在途轮不看）。
 */
class ContextPurifierTest {

    // ---- 构造 helper ----

    private fun user(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun reasoning(text: String) = UIMessagePart.Reasoning(reasoning = text)

    private fun text(text: String) = UIMessagePart.Text(text)

    private fun tool(name: String, input: String, output: String) = UIMessagePart.Tool(
        toolCallId = "$name:$input",
        toolName = name,
        input = input,
        output = if (output.isEmpty()) emptyList() else listOf(UIMessagePart.Text(output)),
    )

    /** 唯一性文本（相邻 8-gram 互不相同，作为「干净内容」的基材） */
    private fun uniqueText(seed: String, length: Int): String = buildString {
        var i = 0
        while (this.length < length) append("$seed-${i++} ")
    }

    /** 恰好 512 字符的唯一块（C1b 对齐用） */
    private fun uniqueBlock(seed: String): String = uniqueText(seed, 512).substring(0, 512)

    private fun degenerateCycleTail() = "好的。执行。".repeat(120)

    // ---- C1a 命中与不命中 ----

    @Test
    fun `c1a truncates periodic reasoning tail to cycle start`() {
        val prefix = uniqueText("step", 300)
        val msg = assistant(reasoning(prefix + degenerateCycleTail()))
        val history = listOf(user("改一下"), msg, user("继续"))

        val result = ContextPurifier.purgeSendWindow(history)

        assertEquals(listOf(ContextPurifier.Rule.CYCLE_TAIL), result.decisions.map { it.rule })
        val newReasoning = result.messages[1].parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("截到周期起点 = 保留前缀", prefix, newReasoning.reasoning)
        assertTrue(result.savedChars >= degenerateCycleTail().length - 6)

        // 幂等：净化后的列表再净化应零决策
        val second = ContextPurifier.purgeSendWindow(result.messages)
        assertTrue("应幂等: ${second.decisions}", second.decisions.isEmpty())

        // 同输入同输出
        assertEquals(result, ContextPurifier.purgeSendWindow(history))
    }

    @Test
    fun `c1a leaves a single character separator line alone`() {
        // 长 ==== 分隔线：周期 1 / 首周期只有 1 个不同字符 ⇒ 不算循环（护栏同款判据）
        val msg = assistant(reasoning("分析结论如下：\n" + "=".repeat(600)))
        val result = ContextPurifier.purgeSendWindow(listOf(user("问"), msg, user("继续")))
        assertTrue(result.decisions.toString(), result.decisions.isEmpty())
        assertSame(msg, result.messages[1])
    }

    // ---- 合法内容零误杀（护栏同款样本形态 + 设计稿点名的 CSS fixture） ----

    @Test
    fun `markdown table reasoning is untouched`() = assertLegitimate(sampleTable())

    @Test
    fun `json dump reasoning is untouched`() = assertLegitimate(sampleJson())

    @Test
    fun `log listing reasoning is untouched`() = assertLegitimate(sampleLog())

    @Test
    fun `css fixture reasoning is untouched`() = assertLegitimate(sampleCss())

    private fun assertLegitimate(sample: String) {
        assertTrue("样本需 ≥2 个块才能测到块级判据", sample.length >= 1024)
        val msg = assistant(reasoning(sample), text("好的，我来处理。"))
        val result = ContextPurifier.purgeSendWindow(listOf(user("继续"), msg, user("再继续")))
        assertTrue("合法内容被误杀: ${result.decisions}", result.decisions.isEmpty())
        assertSame("未命中 ⇒ 共享原实例", msg, result.messages[1])
    }

    private fun sampleTable(): String = buildString {
        appendLine("| 项目 | 说明 | 值 |")
        appendLine("|---|---|---|")
        repeat(60) { appendLine("| 行$it | 说明文本 $it | ${it * 3} |") }
    }

    private fun sampleJson(): String = buildString {
        appendLine("{")
        repeat(60) { appendLine("""  "field$it": "value-$it",""") }
        appendLine("}")
    }

    private fun sampleLog(): String =
        (1..80).joinToString("\n") {
            "2026-10-09 12:00:%02d INFO  worker %d processed %d items".format(it % 60, it, it * 7)
        }

    /** 设计稿 §10 点名要补的 CSS fixture：真实样式表的形态（选择器/值逐条不同） */
    private fun sampleCss(): String = buildString {
        listOf("header", "nav", "main", "aside", "footer", "article", "section", "card", "panel", "dialog")
            .forEachIndexed { i, sel ->
                appendLine(".$sel {")
                appendLine("  display: flex;")
                appendLine("  flex-direction: ${if (i % 2 == 0) "row" else "column"};")
                appendLine("  padding: ${4 + i}px ${8 + i * 2}px;")
                appendLine("  margin: ${2 + i}px auto;")
                appendLine("  background-color: hsl(${i * 36}, 60%, ${35 + i * 3}%);")
                appendLine("  border-radius: ${4 + i}px;")
                appendLine("  transition: all ${100 + i * 25}ms ease;")
                appendLine("}")
            }
    }

    // ---- C1b ----

    @Test
    fun `c1b truncates at the first duplicated block`() {
        val a = uniqueBlock("a")
        val b = uniqueBlock("b")
        val msg = assistant(reasoning(a + b + a))

        val result = ContextPurifier.purgeSendWindow(listOf(user("改"), msg, user("继续")))

        val dup = result.decisions.filter { it.rule == ContextPurifier.Rule.BLOCK_DUP }
        assertEquals(1, dup.size)
        val purged = result.messages[1].parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("截到重复块起点 = 保留更早内容", a + b, purged.reasoning)
    }

    // ---- C1c ----

    @Test
    fun `c1c drops later reasoning part covered by earlier one`() {
        val first = uniqueText("r1", 1200)
        val second = first.dropLast(100) + "补充结论。"
        val msg = assistant(reasoning(first), text("中间的正文。"), reasoning(second))

        val result = ContextPurifier.purgeSendWindow(listOf(user("问"), msg, user("继续")))

        assertEquals(listOf(ContextPurifier.Rule.PART_DUP), result.decisions.map { it.rule })
        val parts = result.messages[1].parts
        assertEquals("后一个 Reasoning 被去掉，其余 part 原样", listOf(first, "中间的正文。"), listOf(
            (parts[0] as UIMessagePart.Reasoning).reasoning,
            (parts[1] as UIMessagePart.Text).text,
        ))
    }

    // ---- C2 ----

    @Test
    fun `c2 drops idle duplicate tools with equivalent output only`() {
        val msg = assistant(
            reasoning(uniqueText("t", 600)),
            tool("workspace_read_file", """{"path":"README.md"}""", "内容 ABC"),   // 保留（第一份）
            tool("workspace_read_file", """{"path":"README.md"}""", "内容 ABC"),   // 丢：同参同输出
            tool("workspace_read_file", """{"path":"README.md"}""", "内容变了"),    // 保留：输出不同
            tool("workspace_read_file", """{"path":"other.md"}""", ""),           // 保留（第一份）
            tool("workspace_read_file", """{"path":"other.md"}""", ""),           // 丢：同参同空
            text("好的，我来改。"),
        )

        val result = ContextPurifier.purgeSendWindow(listOf(user("改"), msg, user("继续")))

        assertEquals(
            listOf(ContextPurifier.Rule.TOOL_IDLE, ContextPurifier.Rule.TOOL_IDLE),
            result.decisions.map { it.rule },
        )
        val tools = result.messages[1].parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(3, tools.size)
        assertEquals("内容 ABC", (tools[0].output.single() as UIMessagePart.Text).text)
        assertEquals("内容变了", (tools[1].output.single() as UIMessagePart.Text).text)
        assertEquals("""{"path":"other.md"}""", tools[2].input)
    }

    // ---- 签名 part：只看不碰 ----

    @Test
    fun `signed reasoning is only reported never touched`() {
        val signed = UIMessagePart.Reasoning(
            reasoning = uniqueText("p", 300) + degenerateCycleTail(),
            metadata = ClaudeReasoningMetadata(signature = "sig-1").toMetadata(),
        )
        val msg = assistant(signed)

        val result = ContextPurifier.purgeSendWindow(listOf(user("问"), msg, user("继续")))

        assertEquals(listOf(ContextPurifier.Rule.SIGNED_SKIP), result.decisions.map { it.rule })
        assertSame("签名 part 不改动 ⇒ 消息共享原实例", msg, result.messages[1])
        assertEquals(0, result.savedChars)
    }

    // ---- 作用域（§4.1 revision 5） ----

    @Test
    fun `messages from the in-flight turn are not touched`() {
        val bad = assistant(reasoning(uniqueText("x", 100) + degenerateCycleTail()))
        val inflight = assistant(reasoning("继续。加油。".repeat(150))) // 也是周期文本，但在 u_last 之后
        val history = listOf(user("改一下"), bad, user("继续"), inflight)

        val result = ContextPurifier.purgeSendWindow(history)

        // 窗口 = [0, u_last) 不含在途轮的 user 消息本身 ⇒ [user, bad] 共 2 条
        assertEquals(2, result.windowCount)
        assertTrue(result.decisions.all { it.messageIndex == 1 })
        assertSame(inflight, result.messages[3])
    }

    @Test
    fun `checkpoints synthetic and user messages are skipped`() {
        val checkpoint = UIMessage(
            role = MessageRole.USER,
            parts = listOf(text("旧的压缩摘要内容" + uniqueText("cp", 200))),
            isContextCheckpoint = true,
        )
        val synthetic = UIMessage.user("注入的世界书内容").copy(isSynthetic = true)
        val bad = assistant(reasoning(uniqueText("y", 100) + degenerateCycleTail()))
        val history = listOf(checkpoint, synthetic, user("任务"), bad, user("继续"))

        val result = ContextPurifier.purgeSendWindow(history)

        assertEquals(4, result.windowCount)
        assertEquals(listOf(3), result.decisions.map { it.messageIndex })
        assertSame(checkpoint, result.messages[0])
        assertSame(synthetic, result.messages[1])
        assertSame(history[2], result.messages[2])
    }

    @Test
    fun `first message only yields an empty window`() {
        val history = listOf(user("你好"))
        val result = ContextPurifier.purgeSendWindow(history)
        assertEquals(0, result.windowCount)
        assertTrue(result.decisions.isEmpty())
        assertSame(history, result.messages)
    }
}
