package app.androidrik.common.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
