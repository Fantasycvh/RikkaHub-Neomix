
package app.androidrik.rikkahub.data.plugin.revision

import app.androidrik.rikkahub.data.plugin.rules.JsonRule
import app.androidrik.rikkahub.data.plugin.rules.JsonRuleDefinition
import app.androidrik.rikkahub.data.plugin.rules.RuleAction
import app.androidrik.rikkahub.data.plugin.rules.RuleActionType
import app.androidrik.rikkahub.data.plugin.rules.RuleCondition
import app.androidrik.rikkahub.data.plugin.rules.RuleOp
import app.androidrik.rikkahub.data.plugin.rules.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePrecheckTest {

    private fun definition(pattern: String) = JsonRuleDefinition(
        id = "sample.rule_test",
        name = "规则预检",
        target = RuleTarget.OUTPUT,
        rules = listOf(
            JsonRule(
                condition = RuleCondition(field = "text", op = RuleOp.CONTAINS, value = kotlinx.serialization.json.JsonPrimitive("x")),
                action = RuleAction(type = RuleActionType.REPLACE, pattern = pattern, replacement = "y"),
            ),
        ),
    )

    @Test
    fun `healthy rule passes`() {
        assertTrue(precheckRule(definition("[a-z]+")).passed)
    }

    @Test
    fun `invalid regex fails the regex item`() {
        val report = precheckRule(definition("[unclosed"))
        assertFalse(report.passed)
        assertFalse(report.items.first { it.name == "regex" }.passed)
        assertTrue(report.failureText().contains("regex"))
    }
}
