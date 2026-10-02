package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 工具调用参数解析（#1985 防御，纯函数可单测）。
 *
 * 背景：上游 issue #1985（Opus 5.5 调 MCP 工具）实测 `tool_use` 的 input 是**两个完整
 * JSON 对象连续拼接**（第一份紧凑、第二份带空格，语义相同），严格解析抛
 * `Expected EOF after parsing` ⇒ 工具执行失败。上游以「不用 issue 模板」拒稿未修。
 * 成因无论是模型输出 quirk 还是 provider 流「增量累积 + 完整原文各来一份」的拼接 bug，
 * 客户端兜底都是同一件事：**严格解析失败后，取第一个完整 JSON 值重新解析**，
 * 忽略尾随内容（尾随长度由调用方记 warning，便于定位是哪家中转/模型的拼接问题）。
 */

/** 解析结果：[lenient]/[trailingChars] 任一非默认即表示发生过降级修复，调用方应记 warning。 */
internal data class ToolArgumentsParse(
    val args: JsonElement,
    /** > 0 = 双 JSON 拼接等场景，取第一个完整值后忽略了这么多尾随字符 */
    val trailingChars: Int = 0,
    /** true = 宽松模式兜底（未引号 key/值等；Yuihub `0eaf2057` 同源） */
    val lenient: Boolean = false,
)

/**
 * 解析工具参数，三层降级（严格 → 宽松 → 首值提取），全部失败抛**原始异常**
 * （错误信息保持真相，不因修复尝试而失真）：
 *
 * 1. **严格解析**：常规路径，绝大多数输入到此为止；
 * 2. **宽松重试**（`isLenient`）：容忍未加引号的 key/值等畸形（Yuihub `0eaf2057`）；
 * 3. **首值提取**（#1985）：双 JSON 对象拼接等场景，取第一个完整 JSON 值重新解析。
 */
internal fun parseToolArguments(input: String, json: Json): ToolArgumentsParse {
    try {
        return ToolArgumentsParse(json.parseToJsonElement(input))
    } catch (strictError: Throwable) {
        // Throwable 而非 Exception：深嵌套工具输入可能抛 StackOverflowError（Error）
        try {
            return ToolArgumentsParse(lenientJson.parseToJsonElement(input), lenient = true)
        } catch (_: Throwable) {
            val bounds = firstJsonValueBounds(input) ?: throw strictError
            val prefix = input.substring(bounds.first, bounds.last + 1)
            val repaired = try {
                json.parseToJsonElement(prefix)
            } catch (_: Throwable) {
                try {
                    lenientJson.parseToJsonElement(prefix)
                } catch (_: Throwable) {
                    throw strictError
                }
            }
            return ToolArgumentsParse(
                args = repaired,
                trailingChars = input.length - (bounds.last + 1),
                lenient = true,
            )
        }
    }
}

/** 宽松模式（未引号 key/值等）；仅作严格解析失败后的兜底，绝不用于常规路径。 */
private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

/**
 * 扫描 [input] 中**第一个完整 JSON 值**（对象或数组）的字符区间；结构不完整或
 * 不是以 `{`/`[` 开头时返回 null。
 *
 * 扫描器带字符串/转义状态机（防止 `"a}{"` 这类字符串内容干扰配对）；配对只计与
 * 最外层同类的括号——JSON 里 `{`/`}` 与 `[`/`]` 各自严格嵌套、不会交叉，内层的
 * 另一类括号不影响配对计数。
 */
internal fun firstJsonValueBounds(input: String): IntRange? {
    var start = -1
    var depth = 0
    var inString = false
    var escaped = false
    var openChar = ' '
    for (i in input.indices) {
        val c = input[i]
        if (start < 0) {
            when {
                c == '{' || c == '[' -> {
                    start = i
                    depth = 1
                    openChar = c
                }
                !c.isWhitespace() -> return null
            }
            continue
        }
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> if (openChar == '{') depth++
            '[' -> if (openChar == '[') depth++
            '}' -> if (openChar == '{') {
                depth--
                if (depth == 0) return start..i
            }
            ']' -> if (openChar == '[') {
                depth--
                if (depth == 0) return start..i
            }
        }
    }
    return null
}
