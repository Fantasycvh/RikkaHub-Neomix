package app.androidrik.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.getSelectedTTSProvider
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.tts.provider.TTSManager

internal fun buildTextToSpeechTool(
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "text_to_speech",
    description = """
        Speak text aloud to the user using the device's text-to-speech engine.
        Use this when the user asks you to read something aloud, or when audio output is appropriate.
        The tool returns immediately; audio plays in the background on the device.
        Provide natural, readable text without markdown formatting.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        // 当前选中的 TTS provider 若硬编码了语气标记引导，则注入 system prompt（否则为空）
        settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?.let { ttsManager.getPromptGuidance(it) }
            .orEmpty()
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to speak aloud")
                })
            },
            required = listOf("text")
        )
    },
    execute = {
        val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: error("text is required")
        eventBus.emit(AppEvent.Speak(text))
        val payload = buildJsonObject {
            put("success", true)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
