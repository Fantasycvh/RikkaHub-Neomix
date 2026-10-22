package app.androidrik.rikkahub.data.plugin.rules

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleEngine] 纯逻辑单测:条件树、操作符、动作。
 *
 * 只覆盖引擎语义;插件是否被管线调用由宿主装配点与真机验证负责。
 */
class RuleEngineTest {

    private fun value(raw: String) = Json.parseToJsonElement(raw)

    private fun simple(field: String, op: RuleOp, raw: String) =
        RuleCondition(field = field, op = op, value = value(raw))

    private val text = mapOf("text" to "广告内容：点击领取", "role" to "assistant")

    @Test
    fun `contains matches and non-matching field is false`() {
        assertTrue(RuleEngine.matches(simple("text", RuleOp.CONTAINS, "\"广告\""), text))
        assertFalse(RuleEngine.matches(simple("text", RuleOp.CONTAINS, "\"优惠券\""), text))
        assertFalse(RuleEngine.matches(simple("missing", RuleOp.CONTAINS, "\"x\""), text))
    }

    @Test
    fun `nested and-or conditions`() {
        val condition = RuleCondition(
            and = listOf(
                simple("text", RuleOp.CONTAINS, "\"广告\""),
                RuleCondition(
                    or = listOf(
                        simple("role", RuleOp.EQ, "\"assistant\""),
                        simple("role", RuleOp.EQ, "\"user\""),
                    ),
                ),
            ),
        )
        assertTrue(RuleEngine.matches(condition, text))
        assertFalse(
            RuleEngine.matches(condition, text + ("role" to "tool")),
        )
    }

    @Test
    fun `numeric comparison works for string and number values`() {
        val values = mapOf("count" to "150000")
        assertTrue(RuleEngine.matches(simple("count", RuleOp.GT, "100000"), values))
        assertTrue(RuleEngine.matches(simple("count", RuleOp.GT, "\"100000\""), values))
        assertFalse(RuleEngine.matches(simple("count", RuleOp.LT, "100000"), values))
        // 非数字两侧不能比较大小
        assertFalse(RuleEngine.matches(simple("role", RuleOp.GT, "1"), text))
    }

    @Test
    fun `regex and in operators`() {
        assertTrue(RuleEngine.matches(simple("text", RuleOp.REGEX, "\"点击.*领取\""), text))
        assertFalse(RuleEngine.matches(simple("text", RuleOp.REGEX, "\"^广告$\""), text))
        assertTrue(RuleEngine.matches(simple("role", RuleOp.IN, "[\"user\",\"assistant\"]"), text))
        assertFalse(RuleEngine.matches(simple("role", RuleOp.IN, "[\"user\"]"), text))
    }

    @Test
    fun `replace action supports group references`() {
        val action = RuleAction(
            type = RuleActionType.REPLACE,
            pattern = "广告(.*)",
            replacement = "[已过滤]$1",
        )
        assertEquals("[已过滤]内容：点击领取", RuleEngine.apply(action, "广告内容：点击领取"))
    }

    @Test
    fun `append and prepend actions`() {
        assertEquals(
            "hello\n-- 来自助手",
            RuleEngine.apply(RuleAction(type = RuleActionType.APPEND, text = "-- 来自助手"), "hello"),
        )
        assertEquals(
            "[注意] hello",
            RuleEngine.apply(RuleAction(type = RuleActionType.PREPEND, text = "[注意] "), "hello"),
        )
    }

    @Test
    fun `invalid regex falls back to original text`() {
        val action = RuleAction(type = RuleActionType.REPLACE, pattern = "([", replacement = "x")
        assertEquals("原文", RuleEngine.apply(action, "原文"))
    }

    @Test
    fun `definition validation rejects bad ids and empty rules`() {
        val badId = JsonRuleDefinition(id = "Bad Id", name = "x", rules = emptyList())
        assertTrue(badId.validate().isNotEmpty())

        val noRules = JsonRuleDefinition(id = "ok.id", name = "x", rules = emptyList())
        assertTrue(noRules.validate().any { it.contains("rules") })

        val valid = JsonRuleDefinition(
            id = "ok.id",
            name = "x",
            rules = listOf(
                JsonRule(
                    condition = simple("text", RuleOp.CONTAINS, "\"a\""),
                    action = RuleAction(type = RuleActionType.REPLACE, pattern = "a", replacement = "b"),
                ),
            ),
        )
        assertTrue(valid.validate().isEmpty())
        assertNull(valid.rules.single().condition.validate())
    }

    @Test
    fun `target derives required capability`() {
        val input = JsonRuleDefinition(id = "a", name = "a", target = RuleTarget.INPUT)
        val output = JsonRuleDefinition(id = "b", name = "b", target = RuleTarget.OUTPUT)
        assertEquals(
            app.androidrik.plugin.api.PluginCapability.PROMPT_TRANSFORM,
            input.requiredCapability,
        )
        assertEquals(
            app.androidrik.plugin.api.PluginCapability.OUTPUT_TRANSFORM,
            output.requiredCapability,
        )
    }

    @Test
    fun `scope last only touches latest target message`() {
        val messages = listOf(
            userMessage("first  "),
            assistantMessage("reply  "),
            userMessage("second  "),
        )
        val rule = JsonRule(
            condition = simple("text", RuleOp.CONTAINS, "\"\""),
            action = RuleAction(type = RuleActionType.APPEND, text = "[x]"),
        )

        val (last, lastMatched) = applyRulesToMessages(
            messages = messages,
            role = MessageRole.USER,
            scope = RuleScope.LAST,
            rules = listOf(rule),
            assistantName = "assistant",
            assistantId = "id",
            modelName = "model",
        )
        assertEquals(1, lastMatched)
        assertEquals("first  ", (last[0].parts.single() as UIMessagePart.Text).text)
        assertEquals("second  \n[x]", (last[2].parts.single() as UIMessagePart.Text).text)

        val (all, allMatched) = applyRulesToMessages(
            messages = messages,
            role = MessageRole.USER,
            scope = RuleScope.ALL,
            rules = listOf(rule),
            assistantName = "assistant",
            assistantId = "id",
            modelName = "model",
        )
        assertEquals(2, allMatched)
        assertEquals("first  \n[x]", (all[0].parts.single() as UIMessagePart.Text).text)
        assertEquals("second  \n[x]", (all[2].parts.single() as UIMessagePart.Text).text)
    }

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessage(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
}
