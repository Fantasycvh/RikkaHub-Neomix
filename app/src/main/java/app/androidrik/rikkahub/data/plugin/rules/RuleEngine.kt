package app.androidrik.rikkahub.data.plugin.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 规则求值引擎(纯函数,可 JVM 直测)。
 *
 * 设计要点(对应 BiliPai 缺陷"字段用 when 硬编码"):
 * - 字段通过**路径键**从调用方给的 `values: Map<String, String>` 取,
 *   引擎不认识任何具体字段名;宿主(见 [JsonRulePlugin])负责把上下文摊平成这张表。
 * - 数值比较:两侧都可解析为数字时按数值比较,否则按字符串比较。
 *   BiliPai 的 `lt/gt` 对数字字段才有效,这里同时兼容 `"60"` 与 `60`。
 */
object RuleEngine {

    /** 条件是否成立。 */
    fun matches(condition: RuleCondition, values: Map<String, String>): Boolean {
        // 复合条件优先:and 全部满足 / or 任一满足(可嵌套)
        if (condition.and.isNotEmpty()) return condition.and.all { matches(it, values) }
        if (condition.or.isNotEmpty()) return condition.or.any { matches(it, values) }

        val field = condition.field ?: return false
        val op = condition.op ?: return false
        val expected = condition.value ?: return false
        val actual = values[field] ?: return false

        return compare(actual, op, expected)
    }

    /** 对文本执行动作;动作非法(如坏正则)时原样返回,不让规则插件把消息弄丢。 */
    fun apply(action: RuleAction, text: String): String = when (action.type) {
        RuleActionType.REPLACE -> {
            if (action.pattern.isBlank()) {
                text
            } else {
                runCatching {
                    Regex(action.pattern).replace(text, action.replacement)
                }.getOrDefault(text)
            }
        }

        RuleActionType.APPEND -> if (action.text.isEmpty()) text else text + "\n" + action.text

        RuleActionType.PREPEND -> action.text + text
    }

    private fun compare(actual: String, op: RuleOp, expected: JsonElement): Boolean = when (op) {
        RuleOp.EQ -> equalsValue(actual, expected)
        RuleOp.NE -> !equalsValue(actual, expected)
        RuleOp.LT -> numericCompare(actual, expected) { it < 0 }
        RuleOp.LE -> numericCompare(actual, expected) { it <= 0 }
        RuleOp.GT -> numericCompare(actual, expected) { it > 0 }
        RuleOp.GE -> numericCompare(actual, expected) { it >= 0 }
        RuleOp.CONTAINS -> actual.contains(expected.asText())
        RuleOp.STARTS_WITH -> actual.startsWith(expected.asText())
        RuleOp.ENDS_WITH -> actual.endsWith(expected.asText())
        RuleOp.REGEX -> {
            val pattern = expected.asText()
            if (pattern.isEmpty()) {
                false
            } else {
                runCatching { Regex(pattern).containsMatchIn(actual) }.getOrDefault(false)
            }
        }

        RuleOp.IN -> (expected as? JsonArray)?.any { equalsValue(actual, it) } ?: false
    }

    private fun equalsValue(actual: String, expected: JsonElement): Boolean {
        val expectedText = expected.asText()
        val a = actual.toDoubleOrNull()
        val b = expectedText.toDoubleOrNull()
        return if (a != null && b != null) a == b else actual == expectedText
    }

    /** 两侧都能解析为数字才比较;否则视为 false(避免字符串被误判为大小关系)。 */
    private inline fun numericCompare(
        actual: String,
        expected: JsonElement,
        predicate: (Int) -> Boolean,
    ): Boolean {
        val a = actual.toDoubleOrNull() ?: return false
        val b = expected.asText().toDoubleOrNull() ?: return false
        return predicate(a.compareTo(b))
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }
}
