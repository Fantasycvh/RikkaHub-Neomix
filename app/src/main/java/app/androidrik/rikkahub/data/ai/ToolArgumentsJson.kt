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

/** 解析结果：[args] 为参数本体；[trailingChars] > 0 表示发生了修复（忽略了这么多尾随字符）。 */
internal data class ToolArgumentsParse(val args: JsonElement, val trailingChars: Int = 0)

/**
 * 解析工具参数：先严格解析；失败时取 [input] 中第一个完整 JSON 值重试，
 * 仍失败则抛出**原始异常**（错误信息保持真相，不因修复尝试而失真）。
 */
internal fun parseToolArguments(input: String, json: Json): ToolArgumentsParse {
    try {
        return ToolArgumentsParse(json.parseToJsonElement(input))
    } catch (first: Throwable) {
        // Throwable 而非 Exception：深嵌套工具输入可能抛 StackOverflowError（Error）
        val bounds = firstJsonValueBounds(input) ?: throw first
        val prefix = input.substring(bounds.first, bounds.last + 1)
        val repaired = try {
            json.parseToJsonElement(prefix)
        } catch (_: Throwable) {
            throw first
        }
        return ToolArgumentsParse(args = repaired, trailingChars = input.length - (bounds.last + 1))
    }
}

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
