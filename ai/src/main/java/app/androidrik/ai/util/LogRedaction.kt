package app.androidrik.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 日志脱敏辅助（Yuihub 3.0.0 同域吸收，批次4）：provider 日志不再打请求体/SSE 全文
 * ——它们包含用户消息与 API key 关联的完整对话内容，进 logcat 等于明文外泄。
 * 只保留形状元数据（chars/messages/tools），诊断裸断流所需的**结束事件名**保留。
 */
fun summarizeRequestBody(json: Json, body: JsonElement): String {
    val encoded = json.encodeToString(body)
    val obj = body as? JsonObject
    val messageCount = messageArraySize(obj)
    val toolCount = (obj?.get("tools") as? JsonArray)?.size
    return buildString {
        append("chars=${encoded.length}")
        messageCount?.let { append(", messages=$it") }
        toolCount?.let { append(", tools=$it") }
    }
}

private fun messageArraySize(obj: JsonObject?): Int? = when {
    obj == null -> null
    obj["messages"] is JsonArray -> (obj["messages"] as JsonArray).size
    obj["input"] is JsonArray -> (obj["input"] as JsonArray).size  // Response API
    obj["contents"] is JsonArray -> (obj["contents"] as JsonArray).size  // Google
    else -> null
}

/** 结束事件保留事件名（裸断流诊断需要），正文事件只记长度。 */
fun summarizeSseEvent(type: String?, data: String, terminal: Boolean): String =
    if (terminal) "terminal event=${type ?: data.take(40)}" else "chars=${data.length}"

fun summarizeErrorBody(bodyRaw: String?): String =
    if (bodyRaw == null) "no body" else "chars=${bodyRaw.length}"
