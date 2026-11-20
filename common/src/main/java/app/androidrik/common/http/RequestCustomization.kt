package app.androidrik.common.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 请求级自定义请求头（原定义在 ai 模块 `ProviderSetting.kt`，M3 收敛时下沉 common，
 * 供 ai 聊天链路与 mediagen 媒体链路共用）。
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

/**
 * 请求级自定义请求体字段：生成请求体时按 key 合并进顶层 JSON（或 multipart 表单字段）。
 */
@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)

/**
 * 把自定义请求体字段合并进顶层 JSON：**custom 值覆盖既有键**，两边都是 JsonObject 时递归合并。
 * 原定义在 ai 模块 `util/Request.kt`（M3 收敛时下沉 common，mediagen 适配器同样消费）。
 */
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
