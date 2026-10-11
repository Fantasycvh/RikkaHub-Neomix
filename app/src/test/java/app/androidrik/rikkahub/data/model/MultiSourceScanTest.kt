package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关键词多源扫描（ST 的 6 个 `match*` 开关）测试。
 *
 * 动机（私有仓记录·前期调研 §6.2 ②）：fork 此前**只扫最近 N 条消息**，
 * 而 ST 允许条目声明额外从角色描述/性格/场景等非对话内容里找关键词
 * ⇒ 关键词只出现在角色设定里的条目在 fork 里**永不触发**（漏注入）。
 * 与批次 A 的过度触发合起来，说明导入 ST 世界书此前**行为不等价**。
 */
class MultiSourceScanTest {

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun entry(
        keywords: List<String>,
        scanDepth: Int = 4,
        matchAssistantPrompt: Boolean = false,
    ) = PromptInjection.RegexInjection(
        keywords = keywords,
        scanDepth = scanDepth,
        matchAssistantPrompt = matchAssistantPrompt,
    )

    // region 零回归：默认只扫消息

    @Test
    fun `without extra sources only message text is scanned`() {
        val messages = listOf(userMessage("hello world"))
        val context = extractContextForMatching(messages, scanDepth = 4)
        assertEquals("hello world", context)
    }

    @Test
    fun `empty extra texts behave like no extras`() {
        val messages = listOf(userMessage("hello"))
        val withEmpty = extractContextForMatching(messages, 4, listOf("", "  "))
        val without = extractContextForMatching(messages, 4)
        assertEquals(without, withEmpty)
    }

    @Test
    fun `scan depth still limits messages when extras are present`() {
        val messages = (1..10).map { userMessage("m$it") }
        val context = extractContextForMatching(messages, scanDepth = 2, extraTexts = listOf("EXTRA"))
        assertTrue("kept tail", context.contains("m10") && context.contains("m9"))
        assertFalse("dropped older", context.contains("m8"))
        assertTrue("extra text included", context.contains("EXTRA"))
    }

    // endregion

    // region 多源：人设文本纳入扫描

    @Test
    fun `assistant prompt is scanned when the flag is on`() {
        val messages = listOf(userMessage("just chatting"))
        val assistantPrompt = "This character is a dragon named Smaug."

        // 默认（不开）：关键词只在人设里 → 不命中
        val off = entry(keywords = listOf("Smaug"), matchAssistantPrompt = false)
        assertFalse(off.isTriggered(extractContextForMatching(messages, off.scanDepth)))

        // 开启：人设纳入扫描 → 命中
        val on = entry(keywords = listOf("Smaug"), matchAssistantPrompt = true)
        assertTrue(
            on.isTriggered(
                extractContextForMatching(messages, on.scanDepth, listOf(assistantPrompt))
            )
        )
    }

    @Test
    fun `keyword in messages still matches when the flag is on`() {
        // 多源是「追加」而非「替换」——消息里的关键词照旧命中
        val messages = listOf(userMessage("I saw Smaug today"))
        val on = entry(keywords = listOf("Smaug"), matchAssistantPrompt = true)
        assertTrue(
            on.isTriggered(
                extractContextForMatching(messages, on.scanDepth, listOf("a knight"))
            )
        )
    }

    @Test
    fun `keyword in neither source does not trigger`() {
        val messages = listOf(userMessage("hello"))
        val on = entry(keywords = listOf("Smaug"), matchAssistantPrompt = true)
        assertFalse(
            on.isTriggered(
                extractContextForMatching(messages, on.scanDepth, listOf("a knight"))
            )
        )
    }

    @Test
    fun `secondary keys also see the extra sources`() {
        // 主键在消息里、次键在人设里 —— 两者共用同一个 context，故应能触发（AND_ANY）
        val messages = listOf(userMessage("the dragon appears"))
        val context = extractContextForMatching(
            messages = messages,
            scanDepth = 4,
            extraTexts = listOf("Smaug is a dragon"),
        )
        val e = PromptInjection.RegexInjection(
            keywords = listOf("dragon"),
            secondaryKeywords = listOf("Smaug"),
            selectiveLogic = SelectiveLogic.AND_ANY,
            matchAssistantPrompt = true,
        )
        assertTrue(e.isTriggered(context))
    }

    @Test
    fun `blank assistant prompt does not accidentally match empty keywords`() {
        // 空人设 + 空文本不应让「空关键词」条目变得可触发（keys 为空本就恒 false）
        val context = extractContextForMatching(listOf(userMessage("x")), 4, listOf(""))
        val e = PromptInjection.RegexInjection(keywords = emptyList(), matchAssistantPrompt = true)
        assertFalse(e.isTriggered(context))
    }

    // endregion
}
