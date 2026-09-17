package app.androidrik.rikkahub.data.export

import app.androidrik.rikkahub.data.model.SelectiveLogic
import app.androidrik.rikkahub.data.model.isTriggered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST 世界书 → fork 条目转换的**保真**测试（端到端：从 ST JSON 结构出发）。
 *
 * 核心动机（`docs/peer-apps-benchmark.md` §6.2 ①）：fork 此前**丢弃 `keysecondary`**，
 * 而 ST 的 `selective` 默认 true、大量条目靠次要键抑制误触发 ⇒ **导入后过度注入**
 * （同一世界书在 fork 里注入更多更乱的内容，且难排查——看起来「规则都在」）。
 *
 * 这些用例从 [SillyTavernEntry]（真实的 ST JSON 反序列化目标）出发，断言转换后
 * **触发行为**与 ST 一致，而不只是断言字段被赋值。
 */
class SillyTavernImportFidelityTest {

    /** 主键命中、次键也命中：ST 的 AND_ANY 应当触发（二键都有）。 */
    @Test
    fun `secondary keys are imported and gate triggering`() {
        val st = SillyTavernEntry(
            key = listOf("dragon"),
            keySecondary = listOf("cave", "mountain"),
            selectiveLogic = 0, // AND_ANY
            content = "Dragon lore",
        )
        val entry = st.toRegexInjection()

        assertEquals(listOf("cave", "mountain"), entry.secondaryKeywords)
        assertEquals(SelectiveLogic.AND_ANY, entry.selectiveLogic)

        // 主键命中 + 任一次键命中 → 触发
        assertTrue(entry.isTriggered("a dragon in a cave"))
        // 主键命中但次键都没命中 → **不**触发（这正是修复前会误触发的情形）
        assertFalse(entry.isTriggered("a dragon flying"))
    }

    /** 修复前的核心症状：次键全不命中却仍注入。 */
    @Test
    fun `entry with unmet secondary is suppressed - regression guard`() {
        val entry = SillyTavernEntry(
            key = listOf("sword"),
            keySecondary = listOf("legendary"),
            selectiveLogic = 0,
            content = "Legendary sword lore",
        ).toRegexInjection()

        assertFalse(
            "primary-only match must NOT inject when secondary is required",
            entry.isTriggered("I bought a sword today"),
        )
        assertTrue(entry.isTriggered("I bought a legendary sword"))
    }

    @Test
    fun `selective false discards secondary keys`() {
        // ST: selective=false ⇒ 即便有 keysecondary 也不做二次判定（world-info.js:4925）
        val entry = SillyTavernEntry(
            key = listOf("dragon"),
            keySecondary = listOf("cave"),
            selective = false,
            content = "x",
        ).toRegexInjection()

        assertTrue("secondary must be dropped", entry.secondaryKeywords.isEmpty())
        assertTrue("primary-only match should trigger", entry.isTriggered("a dragon"))
    }

    @Test
    fun `selective false with regex secondary does not poison primary`() {
        // 真 bug 防线：若在算 useRegex 之前没作废次要键，次要键的正则会抬起 useRegex，
        // 连累**主键字面量**也按正则语义匹配（如 "a.b" 会命中 "axb"）。
        val entry = SillyTavernEntry(
            key = listOf("a.b"),
            keySecondary = listOf("/\\d+/"),
            selective = false,
            content = "x",
        ).toRegexInjection()

        assertFalse("primary must stay literal", entry.useRegex)
        assertFalse("literal 'a.b' must not match 'axb'", entry.isTriggered("axb"))
        assertTrue(entry.isTriggered("a.b"))
    }

    @Test
    fun `all four selective logics map from ST integers`() {
        assertEquals(SelectiveLogic.AND_ANY, mapSillyTavernSelectiveLogic(0))
        assertEquals(SelectiveLogic.NOT_ALL, mapSillyTavernSelectiveLogic(1))
        assertEquals(SelectiveLogic.NOT_ANY, mapSillyTavernSelectiveLogic(2))
        assertEquals(SelectiveLogic.AND_ALL, mapSillyTavernSelectiveLogic(3))
        // 未知值回退默认（不让条目意外恒不触发）
        assertEquals(SelectiveLogic.AND_ANY, mapSillyTavernSelectiveLogic(99))
        assertEquals(SelectiveLogic.AND_ANY, mapSillyTavernSelectiveLogic(-1))
    }

    @Test
    fun `regex in secondary enables regex mode for the entry`() {
        val entry = SillyTavernEntry(
            key = listOf("magic"),
            keySecondary = listOf("/\\bwand\\b/i"),
            selectiveLogic = 0,
            content = "x",
        ).toRegexInjection()

        assertTrue("regex form in secondary must set useRegex", entry.useRegex)
        assertEquals(listOf("\\bwand\\b"), entry.secondaryKeywords)
        assertTrue(entry.isTriggered("magic with a wand"))
        assertFalse("word boundary", entry.isTriggered("magic with a wandfish"))
    }

    @Test
    fun `plain entry without secondary keeps legacy triggering`() {
        val entry = SillyTavernEntry(key = listOf("apple"), content = "x").toRegexInjection()
        assertTrue(entry.secondaryKeywords.isEmpty())
        assertTrue(entry.isTriggered("an apple"))
        assertFalse(entry.isTriggered("a pear"))
    }

    @Test
    fun `other st fields survive conversion`() {
        val entry = SillyTavernEntry(
            key = listOf("k"),
            content = "the content",
            comment = "my note",
            order = 42,
            disable = true,
            depth = 7,
            scanDepth = 9,
            caseSensitive = true,
            constant = true,
        ).toRegexInjection()

        assertEquals("my note", entry.name)
        assertEquals("the content", entry.content)
        assertEquals(42, entry.priority)
        assertFalse(entry.enabled)
        assertEquals(7, entry.injectDepth)
        assertEquals(9, entry.scanDepth)
        assertTrue(entry.caseSensitive)
        assertTrue(entry.constantActive)
    }

    @Test
    fun `name falls back to first primary key when comment is empty`() {
        val entry = SillyTavernEntry(key = listOf("first", "second"), comment = null).toRegexInjection()
        assertEquals("first", entry.name)
    }

    // region 多源扫描（ST 的 6 个 match* 开关）

    @Test
    fun `no match flags keeps assistant prompt out of scope`() {
        val entry = SillyTavernEntry(key = listOf("dragon"), content = "x").toRegexInjection()
        assertFalse("default must stay message-only", entry.matchAssistantPrompt)
    }

    @Test
    fun `any match flag enables assistant prompt scanning`() {
        // fork 无 6 个独立字段（内容已压进 systemPrompt）⇒ 任一 flag 打开即并成一个开关
        val flags = listOf(
            SillyTavernEntry(key = listOf("k"), matchPersonaDescription = true),
            SillyTavernEntry(key = listOf("k"), matchCharacterDescription = true),
            SillyTavernEntry(key = listOf("k"), matchCharacterPersonality = true),
            SillyTavernEntry(key = listOf("k"), matchCharacterDepthPrompt = true),
            SillyTavernEntry(key = listOf("k"), matchScenario = true),
            SillyTavernEntry(key = listOf("k"), matchCreatorNotes = true),
        )
        flags.forEach { st ->
            assertTrue(
                "flag on ${st} should enable assistant-prompt scanning",
                st.toRegexInjection().matchAssistantPrompt,
            )
        }
    }

    // endregion
}
