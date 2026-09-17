package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色卡**槽位**视图的行为测试（顺序 / 开关 / 重建 / 漂移判定）。
 *
 * 动机（`docs/peer-apps-benchmark.md` §6.7 ⑤）：ST 的 `prompt_order` 把
 * charDescription/scenario/… 当作**可单独开关、可重排的槽位**；fork 导入时曾把它们
 * 压成一条不可分的字符串，槽位信息当场丢失。本文件锁住「现在可以开关与重排」。
 *
 * ⚠️ 其中「默认顺序逐字节等价」由 `AssistantImporterTest` 的特征测试另外锁着——
 * 本文件只补**非默认**行为。
 */
class CardSlotTest {

    private fun assistant(
        name: String = "Alice",
        systemPrompt: String? = null,
        description: String? = "A cheerful girl",
        personality: String? = "cheerful",
        scenario: String? = "At the park",
        example: String? = "Alice: Hi there!",
        order: List<CardSlot> = emptyList(),
        disabled: Set<CardSlot> = emptySet(),
    ) = Assistant(
        name = name,
        cardSystemPrompt = systemPrompt,
        cardDescription = description,
        cardPersonality = personality,
        cardScenario = scenario,
        cardExampleDialogue = example,
        cardSlotOrder = order,
        disabledCardSlots = disabled,
    )

    private fun render(a: Assistant) = buildCardSystemPrompt(name = a.name, assistant = a)

    // region 默认顺序 = 历史行为

    @Test
    fun `default order renders all six slots in legacy order`() {
        val text = render(assistant(systemPrompt = "You are a maid."))
        val greeting = text.indexOf("You are roleplaying as Alice.")
        val sys = text.indexOf("You are a maid.")
        val desc = text.indexOf("## Description of the character")
        val pers = text.indexOf("## Personality of the character")
        val scen = text.indexOf("## Scenario")
        val exam = text.indexOf("## Example of the dialogue")
        assertTrue(greeting in 0..<sys)
        assertTrue(sys < desc)
        assertTrue(desc < pers)
        assertTrue(pers < scen)
        assertTrue(scen < exam)
    }

    @Test
    fun `empty order falls back to default order`() {
        val a = assistant(order = emptyList())
        assertEquals(render(a.copy(cardSlotOrder = CardSlot.defaultOrder)), render(a))
    }

    // endregion

    // region 开关生效

    @Test
    fun `disabled slot disappears from the prompt`() {
        val text = render(assistant(disabled = setOf(CardSlot.SCENARIO)))
        assertFalse("scenario must be gone", text.contains("## Scenario"))
        assertFalse("its content must be gone too", text.contains("At the park"))
        // 其它槽位不受影响
        assertTrue(text.contains("## Description of the character"))
        assertTrue(text.contains("## Example of the dialogue"))
    }

    @Test
    fun `null content vs disabled slot are different things`() {
        // null 内容 → 渲染 Empty 占位符（历史行为，保留标题）
        val nullContent = render(assistant(scenario = null))
        assertTrue("null content keeps the heading", nullContent.contains("## Scenario"))
        assertTrue(nullContent.contains("Empty"))

        // 关掉 → 整段不出现
        val disabled = render(assistant(disabled = setOf(CardSlot.SCENARIO)))
        assertFalse(disabled.contains("## Scenario"))
    }

    @Test
    fun `greeting can be disabled`() {
        val text = render(assistant(disabled = setOf(CardSlot.GREETING)))
        assertFalse(text.contains("You are roleplaying as"))
    }

    @Test
    fun `disabling system prompt and example is harmless when already blank`() {
        val a = assistant(systemPrompt = null, example = "")
        val withDisabled = render(a.copy(disabledCardSlots = setOf(CardSlot.SYSTEM_PROMPT, CardSlot.EXAMPLE)))
        assertEquals(render(a), withDisabled)
    }

    // endregion

    // region 重排生效

    @Test
    fun `custom order changes block sequence`() {
        val reordered = listOf(
            CardSlot.SCENARIO,
            CardSlot.DESCRIPTION,
            CardSlot.PERSONALITY,
            CardSlot.GREETING,
        )
        val text = render(assistant(order = reordered))
        val scen = text.indexOf("## Scenario")
        val desc = text.indexOf("## Description of the character")
        val greeting = text.indexOf("You are roleplaying as")
        assertTrue("scenario now first", scen < desc)
        assertTrue("description before personality", desc < text.indexOf("## Personality"))
        assertTrue("greeting moved to the end", desc < greeting)
    }

    @Test
    fun `slots omitted from a custom order do not appear`() {
        // order 是「显示哪些 + 什么顺序」的完整声明，漏掉的槽位不应出现
        val text = render(assistant(order = listOf(CardSlot.DESCRIPTION)))
        assertTrue(text.contains("## Description of the character"))
        assertFalse(text.contains("## Scenario"))
        assertFalse(text.contains("You are roleplaying as"))
    }

    @Test
    fun `separator between blocks is a blank line`() {
        val text = render(assistant(order = listOf(CardSlot.DESCRIPTION, CardSlot.SCENARIO)))
        assertEquals(
            "## Description of the character\nA cheerful girl\n\n## Scenario\nAt the park",
            text,
        )
    }

    // endregion

    // region 重建与漂移

    @Test
    fun `rebuild respects custom order and disabled slots`() {
        val a = assistant(order = listOf(CardSlot.SCENARIO, CardSlot.DESCRIPTION), disabled = emptySet())
            .copy(systemPrompt = "手改过的")
        val rebuilt = a.rebuildSystemPromptFromCardParts()
        assertEquals(render(a), rebuilt.systemPrompt)
        // 顺序真的生效了（scenario 在前）
        assertTrue(
            rebuilt.systemPrompt.indexOf("## Scenario") <
                rebuilt.systemPrompt.indexOf("## Description")
        )
    }

    @Test
    fun `no card parts means rebuild is a no-op`() {
        val manual = Assistant(name = "M", systemPrompt = "keep")
        assertEquals("keep", manual.rebuildSystemPromptFromCardParts().systemPrompt)
        assertFalse(manual.hasCardParts)
    }

    @Test
    fun `divergence is detected after the system prompt is edited by hand`() {
        val a = assistant()
        val inSync = a.copy(systemPrompt = render(a))
        assertFalse("freshly rebuilt must not be reported as diverged", inSync.cardPartsDivergeFromSystemPrompt())

        val diverged = inSync.copy(systemPrompt = "手改过的内容")
        assertTrue("hand-edited prompt must be reported as diverged", diverged.cardPartsDivergeFromSystemPrompt())
    }

    @Test
    fun `divergence is reported after changing a slot while prompt stays`() {
        val a = assistant().let { it.copy(systemPrompt = render(it)) }
        // 改了字段但没重建 → 应报告偏离（否则用户以为改了没生效）
        val edited = a.copy(cardScenario = "Somewhere else")
        assertTrue(edited.cardPartsDivergeFromSystemPrompt())
    }

    @Test
    fun `manual assistant is never reported as diverged`() {
        assertFalse(Assistant(name = "M", systemPrompt = "x").cardPartsDivergeFromSystemPrompt())
    }

    // endregion

    // region 拖拽（纯函数）

    @Test
    fun `effective order falls back to default when unset`() {
        assertEquals(CardSlot.defaultOrder, assistant().effectiveCardSlotOrder)
    }

    @Test
    fun `moveCardSlot moves a slot forward`() {
        // 把最后的 EXAMPLE 移到最前
        val moved = assistant().moveCardSlot(fromIndex = 5, toIndex = 0)
        assertEquals(CardSlot.EXAMPLE, moved.effectiveCardSlotOrder.first())
        assertEquals(CardSlot.defaultOrder.size, moved.effectiveCardSlotOrder.size)
    }

    @Test
    fun `moveCardSlot moves a slot backward`() {
        val moved = assistant().moveCardSlot(fromIndex = 0, toIndex = 3)
        assertEquals(CardSlot.GREETING, moved.effectiveCardSlotOrder[3])
    }

    @Test
    fun `moveCardSlot on unset order materializes the default first`() {
        // 空 order 时拖拽必须从「默认顺序」开始，否则会丢掉其它槽位
        val moved = assistant(order = emptyList()).moveCardSlot(0, 2)
        assertEquals(CardSlot.defaultOrder.size, moved.effectiveCardSlotOrder.size)
        assertTrue(moved.cardSlotOrder.isNotEmpty())
    }

    @Test
    fun `moveCardSlot keeps every slot exactly once`() {
        val moved = assistant().moveCardSlot(1, 4)
        assertEquals(
            CardSlot.defaultOrder.toSet(),
            moved.effectiveCardSlotOrder.toSet(),
        )
        assertEquals(CardSlot.defaultOrder.size, moved.effectiveCardSlotOrder.size)
    }

    @Test
    fun `out of range indices leave the assistant untouched`() {
        val a = assistant()
        assertEquals(a, a.moveCardSlot(fromIndex = 99, toIndex = 0))
        assertEquals(a, a.moveCardSlot(fromIndex = 0, toIndex = -1))
        assertEquals(a, a.moveCardSlot(fromIndex = 2, toIndex = 2))
    }

    // endregion
}
