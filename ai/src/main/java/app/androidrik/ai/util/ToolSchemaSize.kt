package app.androidrik.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.common.tokenizer.TokenizerRegistry

/**
 * 工具 schema 在**实际发给 provider 时**占多少 token（用于排查"谁吃掉了上下文"）。
 *
 * 为什么要按 provider 分形状、而不是拿 `description.length` 粗算：四个 provider 下发的
 * tool schema **不是同一个东西**——OpenAI 用 `{type,function:{...}}` 包装、Claude 换成
 * `input_schema` 键、Google 用 `functionDeclarations` 并**剥掉** `enum`/`format`/`const`
 * 等字段，且前两者会经 [ensureArrayItems] 补 `items`。实测三者体积差在 10% 以内，
 * 但「精确」的定义必须绑定到具体 provider，否则数字不可解释。
 *
 * 用途是**定位大头**（例如 MCP 服务器下发的几十个工具），不是计费级精确；字符→token
 * 仍用 `cjk/1.5 + other/4` 的启发式口径（与 `ContextLength` 一致）。
 */
object ToolSchemaSize {

    /** 单个工具的 token 估算（含 provider 包装与形状变换）。 */
    fun estimate(
        tool: Tool,
        providerType: ProviderSetting?,
        json: Json,
    ): Int {
        val text = runCatching {
            json.encodeToString(JsonElement.serializer(), wireElement(tool, providerType, json))
        }.getOrNull() ?: return 0
        return estimateTextTokens(text)
    }

    /**
     * 单个工具**实际下发的 JSON 文本**（已按 provider 形状包装、并做 `ensureArrayItems` 归一化）。
     *
     * 与 [estimate] 共用同一份 [wireElement]，故「估算的字节」与「展示的字节」必然一致——
     * 拼装预览用它渲染 schema 原文，避免两处各自拼 JSON 而漂移。
     */
    fun wireJson(
        tool: Tool,
        providerType: ProviderSetting?,
        json: Json,
    ): String = runCatching {
        json.encodeToString(JsonElement.serializer(), wireElement(tool, providerType, json))
    }.getOrElse { "" }

    /** 按 provider 的**实际下发形状**包装工具（估算与展示的唯一真相源）。 */
    private fun wireElement(
        tool: Tool,
        providerType: ProviderSetting?,
        json: Json,
    ): JsonElement {
        val schemaElement = runCatching {
            json.encodeToJsonElement(
                InputSchema.serializer(),
                tool.parameters() ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
            )
        }.getOrNull()

        return when (providerType) {
            is ProviderSetting.Claude -> buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", schemaElement ?: JsonObject(emptyMap()))
            }
            // OpenAI Responses API 与 Chat Completions 形状相同（都是 {type,function:{...}}）
            is ProviderSetting.OpenAI -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", schemaElement?.ensureArrayItems() ?: JsonObject(emptyMap()))
                })
            }
            is ProviderSetting.Google -> buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", (schemaElement ?: JsonObject(emptyMap()))
                    .removeElements(GOOGLE_STRIPPED_SCHEMA_KEYS)
                    .ensureArrayItems())
            }
            // 未知/自定义类型：按最常见的 OpenAI 形状估（与 provider 默认 OpenAI 兼容一致）
            null -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", schemaElement?.ensureArrayItems() ?: JsonObject(emptyMap()))
                })
            }
        }
    }

    /** 一批工具的 token 估算合计。 */
    fun estimate(
        tools: List<Tool>,
        providerType: ProviderSetting?,
        json: Json,
    ): Int = tools.sumOf { estimate(it, providerType, json) }

    /**
     * Google function schema 会剥掉的字段（`GoogleProvider` 调 `removeElements` 的那批）。
     * 逐一对应，避免两处漂移。
     */
    private val GOOGLE_STRIPPED_SCHEMA_KEYS = listOf(
        "const",
        "exclusiveMaximum",
        "exclusiveMinimum",
        "format",
        "additionalProperties",
        "propertyNames",
        "enum",
    )
}

/**
 * schema 文本的 token 数：**真 tokenizer 优先**，不可用（未预热/装载失败）时退回字符启发式
 * （CJK 1.5 字符/token、其余 4 字符/token）——与 `app` 侧 `ContextLength.estimateTokens` 同一分层：
 * 真值在 `common` 的 `TokenizerRegistry`，这里只是"取不到就退"的兜底。两处口径必须一致，
 * 否则"预览说还有空间、run 却撞墙"这类差异无法解释。
 */
private fun estimateTextTokens(text: String): Int {
    if (text.isEmpty()) return 0
    TokenizerRegistry.count(text)?.let { return it }
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af') {
            cjk++
        } else {
            other++
        }
    }
    return (cjk / 1.5 + other / 4.0).toInt().coerceAtLeast(0)
}

/**
 * 数组类型属性必须带 `items` 声明（缺则补 `{"type":"string"}`），递归处理嵌套
 * properties 与 items——与 provider 发送时所做的归一化一致，故估算体积必须套用它。
 *
 * 原定义在 `GoogleProvider`（Gemini 校验 `missing field: ...items` 所需，上游
 * 9851d037/f7869e35 未覆盖），现提升到此处供 [ToolSchemaSize] 复用——**只有一份实现**，
 * 避免估算与真实发送的形状漂移。
 */
fun JsonElement.ensureArrayItems(): JsonElement {
    val obj = this as? JsonObject ?: return this
    return buildJsonObject {
        obj.forEach { (key, value) ->
            if (value is JsonObject && value.isArrayTyped()) {
                val resolvedItems = value["items"] ?: buildJsonObject { put("type", "string") }
                put(key, buildJsonObject {
                    value.forEach { (k, v) -> put(k, v) }
                    put("items", resolvedItems.ensureArrayItems())
                })
            } else {
                put(key, value.ensureArrayItems())
            }
        }
    }
}

/** type 判定：`"array"` 或 type 列表含 "array"；畸形 type 按非数组处理、不抛。 */
private fun JsonObject.isArrayTyped(): Boolean {
    val typeElement = this["type"] ?: return false
    return when (typeElement) {
        is JsonPrimitive -> typeElement.contentOrNull == "array"
        is kotlinx.serialization.json.JsonArray ->
            typeElement.any { (it as? JsonPrimitive)?.contentOrNull == "array" }
        else -> false
    }
}
