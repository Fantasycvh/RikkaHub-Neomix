package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书次要关键词（ST `keysecondary` + `selectiveLogic`）的触发真值表。
 *
 * 动机（`docs/peer-apps-benchmark.md` §6.2 ①）：fork 此前**没有**次要键概念，而 ST 的
 * `selective` 默认 true、大量条目靠次要键抑制误触发 ⇒ 导入 ST 世界书后会**过度注入**。
 * 本文件把四种逻辑的语义钉死，并守住「次要键为空 = 老行为」这条零回归底线。
 */
class SelectiveLogicTest {

    private fun entry(
        keywords: List<String> = listOf("apple"),
        secondaryKeywords: List<String> = emptyList(),
        selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        constantActive: Boolean = false,
        enabled: Boolean = true,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
    ) = PromptInjection.RegexInjection(
        keywords = keywords,
        secondaryKeywords = secondaryKeywords,
        selectiveLogic = selectiveLogic,
        constantActive = constantActive,
        enabled = enabled,
        useRegex = useRegex,
        caseSensitive = caseSensitive,
    )

    // region 零回归：次要键为空时行为与旧版一致

    @Test
    fun `no secondary keys keeps legacy behavior - primary match triggers`() {
        assertTrue(entry().isTriggered("I like apple"))
    }

    @Test
    fun `no secondary keys keeps legacy behavior - no primary match does not trigger`() {
        assertFalse(entry().isTriggered("I like banana"))
    }

    @Test
    fun `constant active bypasses both primary and secondary`() {
        val e = entry(
            keywords = listOf("never-present"),
            secondaryKeywords = listOf("also-never"),
            constantActive = true,
        )
        assertTrue(e.isTriggered("anything"))
    }

    @Test
    fun `disabled entry never triggers even with secondary satisfied`() {
        val e = entry(secondaryKeywords = listOf("banana"), enabled = false)
        assertFalse(e.isTriggered("apple and banana"))
    }

    @Test
    fun `empty primary keys never trigger even if secondary matches`() {
        val e = entry(keywords = emptyList(), secondaryKeywords = listOf("banana"))
        assertFalse(e.isTriggered("banana"))
    }

    @Test
    fun `secondary is not evaluated when primary misses`() {
        // 主键不命中 → 直接 false，与次键是否命中无关（四种逻辑都不该翻转结果）
        SelectiveLogic.entries.forEach { logic ->
            val e = entry(
                keywords = listOf("apple"),
                secondaryKeywords = listOf("banana"),
                selectiveLogic = logic,
            )
            assertFalse("logic=$logic must not trigger without primary match", e.isTriggered("banana only"))
        }
    }

    // endregion

    // region 四种逻辑的真值表（主键已命中）

    @Test
    fun `AND_ANY triggers when any secondary matches`() {
        val e = entry(secondaryKeywords = listOf("secA", "secB"), selectiveLogic = SelectiveLogic.AND_ANY)
        assertTrue("one of two matched", e.isTriggered("apple secB"))
        assertTrue("both matched", e.isTriggered("apple secA secB"))
        assertFalse("none matched", e.isTriggered("apple only"))
    }

    @Test
    fun `NOT_ALL triggers when any secondary does not match`() {
        val e = entry(secondaryKeywords = listOf("secA", "secB"), selectiveLogic = SelectiveLogic.NOT_ALL)
        assertTrue("one missing", e.isTriggered("apple secA"))
        assertFalse("all present", e.isTriggered("apple secA secB"))
    }

    @Test
    fun `NOT_ANY triggers only when no secondary matches`() {
        val e = entry(secondaryKeywords = listOf("secA", "secB"), selectiveLogic = SelectiveLogic.NOT_ANY)
        assertTrue("none matched", e.isTriggered("apple only"))
        assertFalse("one matched", e.isTriggered("apple secA"))
        assertFalse("both matched", e.isTriggered("apple secA secB"))
    }

    @Test
    fun `AND_ALL triggers only when all secondary match`() {
        val e = entry(secondaryKeywords = listOf("secA", "secB"), selectiveLogic = SelectiveLogic.AND_ALL)
        assertTrue("both matched", e.isTriggered("apple secA secB"))
        assertFalse("one matched", e.isTriggered("apple secA"))
        assertFalse("none matched", e.isTriggered("apple only"))
    }

    @Test
    fun `single secondary key makes AND_ALL and AND_ANY agree`() {
        val any = entry(secondaryKeywords = listOf("secA"), selectiveLogic = SelectiveLogic.AND_ANY)
        val all = entry(secondaryKeywords = listOf("secA"), selectiveLogic = SelectiveLogic.AND_ALL)
        listOf("apple secA", "apple only").forEach { ctx ->
            assertTrue("mismatch on '$ctx'", any.isTriggered(ctx) == all.isTriggered(ctx))
        }
    }

    // endregion

    // region 与主键共享的匹配语义（大小写 / 正则）

    @Test
    fun `secondary matching respects case sensitivity`() {
        val insensitive = entry(secondaryKeywords = listOf("Banana"))
        assertTrue("default is case-insensitive", insensitive.isTriggered("apple banana"))

        val sensitive = entry(secondaryKeywords = listOf("Banana"), caseSensitive = true)
        assertTrue(sensitive.isTriggered("apple Banana"))
        assertFalse(sensitive.isTriggered("apple banana"))
    }

    @Test
    fun `secondary keywords support regex the same way as primary`() {
        val e = entry(
            keywords = listOf("apple"),
            secondaryKeywords = listOf("""\bsword\b"""),
            selectiveLogic = SelectiveLogic.AND_ANY,
            useRegex = true,
        )
        assertTrue(e.isTriggered("apple a sword here"))
        assertFalse(e.isTriggered("apple swordfish"))
    }

    @Test
    fun `invalid regex in secondary does not trigger instead of crashing`() {
        // 坏正则按「不命中」处理（与主键一致），故 AND_ANY 下应为 false 而非抛异常
        val e = entry(
            keywords = listOf("apple"),
            secondaryKeywords = listOf("[unclosed"),
            selectiveLogic = SelectiveLogic.AND_ANY,
            useRegex = true,
        )
        assertFalse(e.isTriggered("apple [unclosed"))
    }

    // endregion
}
