package app.androidrik.rikkahub.data.plugin.revision

import app.androidrik.rikkahub.data.plugin.rules.JsonRuleDefinition

/**
 * JSON 规则插件的激活前预检（R3，与 JS 同口径的轻量版）：结构校验 + 正则可编译性。
 * 无语法/工具/smoke 项——规则是声明式数据，没有可执行代码（设计稿 §四）。
 */
fun precheckRule(definition: JsonRuleDefinition): PrecheckReport {
    val items = mutableListOf<PrecheckItemResult>()

    val errors = definition.validate()
    items += PrecheckItemResult(
        name = "manifest",
        passed = errors.isEmpty(),
        detail = errors.joinToString("; ").ifEmpty { "ok" },
    )

    // 正则可编译性：action.validate 已内嵌该检查（"pattern 不是合法正则"），
    // 这里作为独立维度再报一次，让预检报告的「regex」项语义自洽（设计稿 §四）
    val regexErrors = buildList {
        definition.rules.forEachIndexed { index, rule ->
            val pattern = rule.action.pattern
            if (pattern.isNotBlank()) {
                runCatching { Regex(pattern) }.onFailure {
                    add("rules[$index]: 正则不可编译 '$pattern': ${it.message}")
                }
            }
        }
    }
    items += PrecheckItemResult(
        name = "regex",
        passed = regexErrors.isEmpty(),
        detail = regexErrors.joinToString("; ").ifEmpty { "ok" },
    )

    return PrecheckReport(passed = items.all { it.passed }, items = items)
}
