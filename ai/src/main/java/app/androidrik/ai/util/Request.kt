package app.androidrik.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.CustomHeader
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.internal.http.RealResponseBody

fun List<CustomHeader>.toHeaders(): Headers {
    return Headers.Builder().apply {
        this@toHeaders
            .filter { it.name.isNotBlank() }
            .forEach {
                add(it.name, it.value)
            }
    }.build()
}

fun Request.Builder.configureReferHeaders(url: String): Request.Builder {
    val httpUrl = url.toHttpUrl()
    return when (httpUrl.host) {
        "aihubmix.com" -> {
            addHeader("APP-Code", "DKHA9468")
        }

        "openrouter.ai" -> {
            this
                .addHeader("X-Title", "RikkaHub")
                .addHeader("HTTP-Referer", "https://rikka-ai.com")
        }

        else -> this
    }
}

fun ResponseBody.stringSafe(): String? {
    return when (this) {
        is RealResponseBody -> string()
        else -> null
    }
}

fun JsonObject.mergeCustomBody(bodies: List<CustomBody>): JsonObject {
    if (bodies.isEmpty()) return this

    val content = toMutableMap()
    bodies.forEach { body ->
        if (body.key.isNotBlank()) {
            // 如果已存在相同键且两者都是JsonObject，则需要递归合并
            val existingValue = content[body.key]
            val newValue = body.value

            if (existingValue is JsonObject && newValue is JsonObject) {
                // 递归合并两个JsonObject
                content[body.key] = mergeJsonObjects(existingValue, newValue)
            } else {
                // 直接替换或添加
                content[body.key] = newValue
            }
        }
    }
    return JsonObject(content)
}

/**
 * 拼接 API endpoint，baseUrl 已含 path 时不重复追加（如 /responses/responses 404）。
 */
fun apiEndpoint(baseUrl: String, path: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return if (trimmed.endsWith(path)) trimmed else "$trimmed$path"
}

/**
 * 递归合并两个JsonObject。深度上限：深嵌套 CustomBody/响应曾触发 StackOverflowError。
 */
private const val MAX_JSON_MERGE_DEPTH = 32

private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject, depth: Int = 0): JsonObject {
    if (depth >= MAX_JSON_MERGE_DEPTH) {
        // 深度超限：直接以 overlay 覆盖，放弃更深层合并
        return JsonObject(base.toMutableMap().apply { putAll(overlay) })
    }
    val result = base.toMutableMap()

    for ((key, value) in overlay) {
        val baseValue = result[key]

        result[key] = if (baseValue is JsonObject && value is JsonObject) {
            // 如果两者都是JsonObject，递归合并
            mergeJsonObjects(baseValue, value, depth + 1)
        } else {
            // 否则使用新值替换旧值
            value
        }
    }

    return JsonObject(result)
}

/**
 * 从 JsonElement 中移除或保留指定的键
 * @param keys 要操作的键列表
 * @param keepOnly 如果为 true，则只保留指定的键；如果为 false，则移除指定的键
 * @return 处理后的 JsonElement
 */
fun JsonElement.removeElements(keys: List<String>, keepOnly: Boolean = false, depth: Int = 0): JsonElement {
    if (depth >= MAX_JSON_MERGE_DEPTH) return this
    return when (this) {
        is JsonObject -> {
            val newContent = if (keepOnly) {
                // 只保留指定的键（且键存在）
                keys.mapNotNull { key ->
                    get(key)?.let { key to it }
                }.toMap()
            } else {
                // 移除指定的键
                toMap().filterKeys { key -> key !in keys }
            }

            // 递归处理嵌套的 JsonElement
            JsonObject(newContent.mapValues { (_, value) ->
                value.removeElements(keys, keepOnly, depth + 1)
            })
        }

        is JsonArray -> {
            JsonArray(map { it.removeElements(keys, keepOnly, depth + 1) })
        }

        else -> this // 基本类型直接返回
    }
}
