package app.androidrik.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

val JsonInstant by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 旧构建（上游 stepCount: Int? 时代）把显式 null 写进了消息 blob；stepCount 后来改回
        // 非空 Int = 0，null 输入会直接抛 JsonDecodingException 且整条会话打不开。
        // coerce 把「非空属性 + 有默认值」的 null 输入收敛为默认值（写回时自然清毒）。
        coerceInputValues = true
    }
}

val JsonInstantPretty by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
