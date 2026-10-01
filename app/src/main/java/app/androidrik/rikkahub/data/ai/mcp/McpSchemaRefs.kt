package app.androidrik.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP 工具 schema 的 `$ref` 就地展开（#1974 / #1968）。
 *
 * **要解决的问题**：部分 MCP 服务器（Devin、滴答清单等）用 JSON Schema 的 `$defs` +
 * `$ref` 描述工具入参。本仓的 [McpSessionRegistry] 把 SDK 的 `ToolSchema` 转成
 * [app.androidrik.ai.core.InputSchema.Obj] 时只搬运 `properties` / `required`，
 * `$defs` 被丢弃 ⇒ 下发的 `$ref` 悬空 ⇒ **整个请求被 provider 拒绝（400）**，
 * 普通对话也发不出去（openai: `Pointer '/$defs/...' does not exist`）。
 *
 * **为什么是「展开」而不是「透传 `$defs`」**：Google 的 function schema 不支持
 * `$defs`/`$ref`（`GoogleProvider` 会剥掉一批 JSON Schema 关键字），透传对 Gemini 无效；
 * 而且 [app.androidrik.ai.core.InputSchema.Obj] 是密封类型，加字段等于改所有 provider 的
 * 下发形状。就地展开对所有 provider 都是同一份合法 JSON Schema。
 *
 * **失败姿态（宁可宽松、不可拒绝）**：无法解析的引用（悬空 / 非 `$defs` 指针 /
 * 递归引用 / 超深链）退化成 `{}`——`{}` 在 JSON Schema 里表示「接受任意值」，
 * 模型仍能调用该工具（只是参数形状描述变弱），这远好于整个请求 400。
 *
 * 纯函数、无 Android 依赖，故可直接单测。
 */

/** `$ref` 键名。 */
private const val REF_KEY = "\$ref"

/** 本仓唯一能解析的引用前缀（SDK 的 `ToolSchema` 只保留 `$defs`）。 */
private const val REF_PREFIX = "#/\$defs/"

/**
 * 引用链深度上限：环检测（[resolvePointer] 的 `stack`）已能拦住直接与相互递归，
 * 这里只兜底病态的超深**无环**链——超出即截断为宽松 schema，不抛异常。
 */
private const val MAX_REF_DEPTH = 32

/** 展开结果：schema 本体 + 诊断计数（供调用方按需记日志，纯函数本身不打日志）。 */
internal data class InlineRefsResult(
    val schema: JsonObject,
    /** 成功展开的引用数（0 = 该 schema 没用 `$ref`，属常态） */
    val resolvedCount: Int,
    /** 未能解析的引用原文（悬空/递归/超深），非空时调用方应记一条 warning */
    val unresolvedRefs: List<String>,
)

/**
 * 把 [properties] 中所有 `$ref` 按 [defs]（即 `$defs`）展开。
 *
 * @param properties 工具入参的 `properties` 对象（原样作为根节点遍历）
 * @param defs `$defs` 定义表；为空时直接返回 [properties]（绝大多数服务器不用 `$defs`，
 *   该早退让常规路径零开销）
 */
internal fun inlineSchemaRefs(
    properties: JsonObject?,
    defs: JsonObject?,
): InlineRefsResult {
    val props = properties ?: return InlineRefsResult(JsonObject(emptyMap()), 0, emptyList())
    if (defs.isNullOrEmpty()) return InlineRefsResult(props, 0, emptyList())

    val counters = Counters()
    val root = inlineElement(props, defs, mutableListOf(), 0, counters)
    return InlineRefsResult(
        schema = root as? JsonObject ?: props,
        resolvedCount = counters.resolved,
        unresolvedRefs = counters.unresolved,
    )
}

/** 可变诊断计数（展开过程中累积，最后随结果返回）。 */
private class Counters {
    var resolved = 0
    val unresolved = mutableListOf<String>()
}

private fun inlineElement(
    element: JsonElement,
    defs: JsonObject,
    stack: MutableList<String>,
    depth: Int,
    counters: Counters,
): JsonElement = when (element) {
    is JsonObject -> {
        val ref = (element[REF_KEY] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        if (ref == null) {
            // 常规节点：逐值递归（键序由 mapValues 保持）
            JsonObject(element.mapValues { (_, value) -> inlineElement(value, defs, stack, depth, counters) })
        } else {
            val resolved = resolveRef(ref, defs, stack, depth, counters)
            // `$ref` 的兄弟键：draft 2020-12 允许，且兄弟键优先（合并进展开结果）
            val siblings = element.filterKeys { it != REF_KEY }
            if (siblings.isEmpty()) {
                resolved
            } else {
                val siblingValues = siblings.mapValues { (_, value) ->
                    inlineElement(value, defs, stack, depth + 1, counters)
                }
                val base = resolved as? JsonObject
                if (base == null) JsonObject(siblingValues) else JsonObject(base + siblingValues)
            }
        }
    }

    is JsonArray -> JsonArray(element.map { inlineElement(it, defs, stack, depth, counters) })

    else -> element
}

/** 解析单条引用；任何无法解析的情形都退化为 `{}` 并记入诊断。 */
private fun resolveRef(
    ref: String,
    defs: JsonObject,
    stack: MutableList<String>,
    depth: Int,
    counters: Counters,
): JsonElement {
    if (depth >= MAX_REF_DEPTH) {
        counters.unresolved += ref
        return EMPTY_SCHEMA
    }
    if (!ref.startsWith(REF_PREFIX)) {
        // 非 $defs 指针（如 #/properties/x、外部文件）：本仓从 SDK 拿不到目标，宽松降级
        counters.unresolved += ref
        return EMPTY_SCHEMA
    }
    val pointer = ref.removePrefix(REF_PREFIX)
    if (pointer.isBlank()) {
        counters.unresolved += ref
        return EMPTY_SCHEMA
    }
    // 递归引用（含相互递归）：就地截断，避免无限展开成无限大 schema
    if (pointer in stack) {
        counters.unresolved += ref
        return EMPTY_SCHEMA
    }
    val target = resolvePointer(defs, pointer)
    if (target == null) {
        counters.unresolved += ref
        return EMPTY_SCHEMA
    }

    stack.add(pointer)
    val result = inlineElement(target, defs, stack, depth + 1, counters)
    stack.removeAt(stack.lastIndex)
    counters.resolved++
    return result
}

/**
 * 按 JSON Pointer 取 [defs] 里的定义，支持多级路径（`Foo` 或 `Foo/properties/bar`）。
 * 每段做 `~1` → `/`、`~0` → `~` 反转义（JSON Pointer 规范）。
 */
private fun resolvePointer(defs: JsonObject, pointer: String): JsonElement? {
    var current: JsonElement = defs
    for (rawSegment in pointer.split('/')) {
        val segment = rawSegment.replace("~1", "/").replace("~0", "~")
        current = when (val node = current) {
            is JsonObject -> node[segment] ?: return null
            is JsonArray -> segment.toIntOrNull()?.let { node.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return current
}

/** 宽松 schema：`{}` = 接受任意值（用于所有无法解析的引用）。 */
private val EMPTY_SCHEMA: JsonObject = JsonObject(emptyMap())
