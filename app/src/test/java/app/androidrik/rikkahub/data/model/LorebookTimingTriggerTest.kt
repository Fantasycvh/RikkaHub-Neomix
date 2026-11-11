package app.androidrik.rikkahub.data.model

import app.androidrik.ai.ui.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书触发时序四字段（matchWholeWords / sticky / cooldown / delay，NMoi D307-D308）。
 *
 * 判定全部按消息条数无状态推出；default 0/false = 关（存量条目零回归）。
 */
class LorebookTimingTriggerTest {

    private fun entry(
        keywords: List<String> = listOf("dragon"),
        matchWholeWords: Boolean = false,
        sticky: Int = 0,
        cooldown: Int = 0,
        delay: Int = 0,
        constantActive: Boolean = false,
        scanDepth: Int = 4,
        caseSensitive: Boolean = false,
    ) = PromptInjection.RegexInjection(
        name = "entry",
        content = "content",
        keywords = keywords,
        matchWholeWords = matchWholeWords,
        sticky = sticky,
        cooldown = cooldown,
        delay = delay,
        constantActive = constantActive,
        scanDepth = scanDepth,
        caseSensitive = caseSensitive,
    )

    private fun history(vararg texts: String) = texts.map { UIMessage.user(it) }

    @Test
    fun `delay suppresses activation until history is long enough`() {
        val e = entry(delay = 3)
        assertFalse(e.isTriggered(history("dragon mentions", "filler")))
        assertTrue(e.isTriggered(history("dragon mentions", "filler", "filler")))
    }

    @Test
    fun `delay applies to constant entries too`() {
        val e = entry(constantActive = true, delay = 2)
        assertFalse(e.isTriggered(history("filler")))
        assertTrue(e.isTriggered(history("filler", "filler")))
    }

    @Test
    fun `sticky extends the scan window beyond scanDepth`() {
        // 关键词在第 6 条（自末尾数 index 5），scanDepth=4 常规窗口够不到
        val messages = history("old dragon", "1", "2", "3", "4", "5")
        assertFalse(entry(scanDepth = 4).isTriggered(messages))
        assertTrue(entry(scanDepth = 4, sticky = 2).isTriggered(messages))
    }

    @Test
    fun `cooldown suppresses when keyword last mentioned within window`() {
        // 最近一次提到在倒数第 2 条（index 1）：< cooldown=2 ⇒ 抑制
        assertFalse(entry(cooldown = 2).isTriggered(history("dragon", "mentions dragon", "filler")))
        // 最近一次提到在倒数第 3 条（index 2）：>= cooldown ⇒ 放行
        assertTrue(entry(cooldown = 2).isTriggered(history("mentions dragon", "filler", "filler")))
        // 没提到过（常驻由 constantActive 放行）的场景见 constant 用例
    }

    @Test
    fun `cooldown ignores constant entries without keywords`() {
        assertTrue(entry(constantActive = true, keywords = emptyList(), cooldown = 5).isTriggered(history("x", "x")))
    }

    @Test
    fun `whole word matching rejects substrings`() {
        val e = entry(keywords = listOf("cat"), matchWholeWords = true)
        assertFalse(e.isTriggered(history("category theory")))
        assertTrue(e.isTriggered(history("a cat here")))
    }

    @Test
    fun `whole word matching mostly misses continuous chinese`() {
        val e = entry(keywords = listOf("龙"), matchWholeWords = true)
        assertFalse("连续中文没有词边界，\\b 落不到汉字之间", e.isTriggered(history("这条龙盘在山上")))
    }

    @Test
    fun `whole word matching keeps case sensitivity semantics`() {
        val sensitive = entry(keywords = listOf("Cat"), matchWholeWords = true, caseSensitive = true)
        assertFalse(sensitive.isTriggered(history("a cat here")))
        assertTrue(sensitive.isTriggered(history("a Cat here")))
    }

    @Test
    fun `timing fields default off keeps legacy behavior`() {
        val e = entry()
        assertTrue(e.isTriggered(history("category theory with dragon inside")))
    }
}
