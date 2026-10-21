package app.androidrik.rikkahub.data.datastore.migration

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.tts.provider.TTSProviderSetting
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class SettingsDecodeResult(
    val settings: Settings,
    val droppedEntries: List<String> = emptyList(),
)

/**
 * 备份设置的有限降级解码：只跳过 providers / ttsProviders 中不支持的顶层多态类型。
 * 原版 3.1.0 的 chatgpt、grok、volcengine TTS 不映射为其他协议，避免误发请求。
 * 已知类型的字段损坏、嵌套类型错误及其他设置错误仍中止恢复，不静默删数据。
 * 不依赖异常的 JSON path（不同 kotlinx 版本的 path 格式并不稳定）。
 */
internal object SettingsCompatDecoder {
    private val unknownSubclass = Regex(
        """Serializer for subclass '([^']+)' is not found in the polymorphic scope""",
    )

    fun decode(json: Json, settingsJson: String): SettingsDecodeResult {
        val parsed = json.parseToJsonElement(settingsJson)
        val root = (parsed as? JsonObject)?.toMutableMap()
            ?: throw SerializationException("Backup settings must be a JSON object")
        val dropped = mutableListOf<String>()
        val removedTtsIds = mutableSetOf<String>()

        fun <T> filter(field: String, serializer: KSerializer<T>) {
            // 非数组等结构错误留给完整 Settings 解码报错。
            val entries = root[field] as? JsonArray ?: return
            root[field] = JsonArray(entries.filterIndexed { index, entry ->
                try {
                    json.decodeFromJsonElement(serializer, entry)
                    true
                } catch (e: SerializationException) {
                    val obj = entry as? JsonObject ?: throw e
                    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: throw e
                    val unsupported = unknownSubclass.find(e.message.orEmpty())?.groupValues?.get(1)
                    // 只有未知类型就是当前条目的 type 才可丢弃；不可因嵌套错误删整个 provider。
                    if (unsupported != type) throw e
                    dropped += "$field[$index] ($type)"
                    if (field == "ttsProviders") {
                        (obj["id"] as? JsonPrimitive)?.contentOrNull?.let(removedTtsIds::add)
                    }
                    false
                }
            })
        }

        filter("providers", ProviderSetting.serializer())
        filter("ttsProviders", TTSProviderSetting.serializer())
        val selectedTts = (root["selectedTTSProviderId"] as? JsonPrimitive)?.contentOrNull
        if (selectedTts in removedTtsIds) {
            root["selectedTTSProviderId"] = JsonPrimitive(DEFAULT_SYSTEM_TTS_ID.toString())
        }
        return SettingsDecodeResult(
            settings = json.decodeFromJsonElement(Settings.serializer(), JsonObject(root)),
            droppedEntries = dropped.toList(),
        )
    }
}
