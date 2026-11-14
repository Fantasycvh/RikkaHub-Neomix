package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.OutlinedNumberInput
import app.androidrik.rikkahub.ui.components.ui.SelectTextField
import app.androidrik.tts.provider.TTSProviderSetting

@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        // Provider type selector
        val providers = remember { TTSProviderSetting.Types }

        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_provider_type)) },
            description = { Text(stringResource(R.string.setting_tts_page_provider_type_description)) },
        ) {
            SelectTextField(
                value = when (setting) {
                    is TTSProviderSetting.OpenAI -> "OpenAI"
                    is TTSProviderSetting.Gemini -> "Gemini"
                    is TTSProviderSetting.SystemTTS -> "System TTS"
                    is TTSProviderSetting.MiniMax -> "MiniMax"
                    is TTSProviderSetting.Qwen -> "Qwen"
                    is TTSProviderSetting.Groq -> "Groq"
                    is TTSProviderSetting.XAI -> "xAI"
                    is TTSProviderSetting.MiMo -> "MiMo"
                    is TTSProviderSetting.Step -> "Step"
                    is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                    is TTSProviderSetting.FishAudio -> "Fish Audio"
                    is TTSProviderSetting.DeepSeek -> "DeepSeek"
                },
                options = providers,
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                optionToString = { providerClass ->
                    when (providerClass) {
                        TTSProviderSetting.OpenAI::class -> "OpenAI"
                        TTSProviderSetting.Gemini::class -> "Gemini"
                        TTSProviderSetting.SystemTTS::class -> "System TTS"
                        TTSProviderSetting.MiniMax::class -> "MiniMax"
                        TTSProviderSetting.Qwen::class -> "Qwen"
                        TTSProviderSetting.Groq::class -> "Groq"
                        TTSProviderSetting.XAI::class -> "xAI"
                        TTSProviderSetting.MiMo::class -> "MiMo"
                        TTSProviderSetting.ElevenLabs::class -> "ElevenLabs"
                        TTSProviderSetting.FishAudio::class -> "Fish Audio"
                        TTSProviderSetting.Step::class -> "Step"
                        TTSProviderSetting.DeepSeek::class -> "DeepSeek"
                        // 兜底也不再回落 `simpleName`（release 下是 R8 混淆短名）
                        else -> "Unknown"
                    }
                },
                onOptionSelected = { providerClass ->
                    val newSetting = when (providerClass) {
                        TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(
                            id = setting.id,
                            name = "OpenAI TTS"
                        )

                        TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(
                            id = setting.id,
                            name = "Gemini TTS"
                        )

                        TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(
                            id = setting.id,
                            name = "System TTS"
                        )

                        TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(
                            id = setting.id,
                            name = "MiniMax TTS"
                        )

                        TTSProviderSetting.Qwen::class -> TTSProviderSetting.Qwen(
                            id = setting.id,
                            name = "Qwen TTS"
                        )

                        TTSProviderSetting.Groq::class -> TTSProviderSetting.Groq(
                            id = setting.id,
                            name = "Groq TTS"
                        )

                        TTSProviderSetting.XAI::class -> TTSProviderSetting.XAI(
                            id = setting.id,
                            name = "xAI TTS"
                        )

                        TTSProviderSetting.MiMo::class -> TTSProviderSetting.MiMo(
                            id = setting.id,
                            name = "MiMo TTS"
                        )

                        TTSProviderSetting.ElevenLabs::class -> TTSProviderSetting.ElevenLabs(
                            id = setting.id,
                            name = "ElevenLabs TTS"
                        )

                        TTSProviderSetting.FishAudio::class -> TTSProviderSetting.FishAudio(
                            id = setting.id,
                            name = "Fish Audio TTS"
                        )

                        TTSProviderSetting.Step::class -> TTSProviderSetting.Step(
                            id = setting.id,
                            name = "Step TTS"
                        )

                        TTSProviderSetting.DeepSeek::class -> TTSProviderSetting.DeepSeek(
                            id = setting.id,
                            name = "DeepSeek TTS"
                        )

                        else -> setting
                    }
                    onValueChange(newSetting)
                }
            )
        }

        // Name
        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_name)) },
            description = { Text(stringResource(R.string.setting_tts_page_name_description)) }
        ) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { newName ->
                    onValueChange(setting.copyProvider(name = newName))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_tts_page_name_placeholder)) }
            )
        }

        // Provider-specific fields
        when (setting) {
            is TTSProviderSetting.OpenAI -> OpenAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Gemini -> GeminiTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiniMax -> MiniMaxTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.SystemTTS -> SystemTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Qwen -> QwenTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Groq -> GroqTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.XAI -> XAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiMo -> MiMoTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.ElevenLabs -> ElevenLabsTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.FishAudio -> FishAudioTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Step -> StepTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.DeepSeek -> DeepSeekTTSConfiguration(setting, onValueChange)
        }
    }
}

@Composable
private fun OpenAITTSConfiguration(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    // Voice
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MiMoTTSConfiguration(
    setting: TTSProviderSetting.MiMo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // MiMo 配置均为自由输入 默认值只是占位
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2-tts") }
        )
    }

    // Voice
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voice,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo_default") }
        )
    }
}

@Composable
private fun MiniMaxTTSConfiguration(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.5-hd-preview") }
        )
    }

    // Voice ID
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voiceIds,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { voiceId ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun GeminiTTSConfiguration(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_gemini)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_gemini)) }
        )
    }

    // Voice Name
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceName,
            onValueChange = { newVoiceName ->
                onValueChange(setting.copy(voiceName = newVoiceName))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_voice_name_placeholder)) }
        )
    }
}

@Composable
private fun SystemTTSConfiguration(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // Speech Rate
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        description = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..3.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    // Pitch
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        description = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..2.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

@Composable
private fun QwenTTSConfiguration(
    setting: TTSProviderSetting.Qwen,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-tts-flash") }
        )
    }

    // Voice
    val voices = listOf(
        "Cherry", "Serena", "Ethan", "Chelsie",
        "Momo", "Vivian", "Moon", "Maia", "Kai",
        "Nofish", "Bella", "Jennifer", "Ryan",
        "Katerina", "Aiden", "Eldric Sage", "Mia",
        "Mochi", "Bellona", "Vincent", "Bunny",
        "Neil", "Elias", "Arthur", "Nini"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Language Type
    val languageTypes = listOf("Auto", "Chinese", "English", "Japanese", "Korean")

    FormItem(
        label = { Text("Language Type") },
        description = { Text("Language type for TTS synthesis") }
    ) {
        SelectTextField(
            value = setting.languageType,
            options = languageTypes,
            onValueChange = { newLanguageType ->
                onValueChange(setting.copy(languageType = newLanguageType))
            },
            onOptionSelected = { languageType ->
                onValueChange(setting.copy(languageType = languageType))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GroqTTSConfiguration(
    setting: TTSProviderSetting.Groq,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gsk_xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
    }

    // Voice
    val voices = listOf("austin", "natalie", "kailin")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun XAITTSConfiguration(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("xai-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") }
        )
    }

    // Voice ID
    val voices = listOf(
        "eve" to "Eve",
        "ara" to "Ara",
        "rex" to "Rex",
        "sal" to "Sal",
        "leo" to "Leo"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voices,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Language
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es-ES" to "Spanish (Spain)",
        "es-MX" to "Spanish (Mexico)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "it" to "Italian",
        "ru" to "Russian",
        "ar-EG" to "Arabic (Egypt)",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "bn" to "Bengali"
    )

    FormItem(
        label = { Text("Language") },
    ) {
        SelectTextField(
            value = setting.language,
            options = languages,
            onValueChange = { newLanguage ->
                onValueChange(setting.copy(language = newLanguage))
            },
            onOptionSelected = { (code, _) ->
                onValueChange(setting.copy(language = code))
            },
            optionToString = { (code, displayName) -> "$displayName ($code)" },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ElevenLabsTTSConfiguration(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk_...") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") }
        )
    }

    // Model
    val models = listOf(
        "eleven_multilingual_v2" to "Eleven Multilingual v2",
        "eleven_v3" to "Eleven v3",
        "eleven_flash_v2_5" to "Eleven Flash v2.5"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("JBFqnCBsd6RMkjVDRZzb") }
        )
    }

    // Stability
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_stability)) },
        description = { Text(stringResource(R.string.setting_tts_page_stability_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                onValueChange(setting.copy(stability = newStability.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.5",
        )
    }

    // Similarity Boost
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_similarity_boost)) },
        description = { Text(stringResource(R.string.setting_tts_page_similarity_boost_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                onValueChange(setting.copy(similarityBoost = newSimilarityBoost.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.75",
        )
    }
}

@Composable
private fun FishAudioTTSConfiguration(
    setting: TTSProviderSetting.FishAudio,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://fish.audio/app/api-keys") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.fish.audio") }
        )
    }

    // Model (下拉选择框 + 文本输入框，完全同 ElevenLabs 格式)
    val models = listOf(
        "s2.1-pro" to stringResource(R.string.tts_config_fish_audio_model_s21_pro),
        "s2.1-pro-free" to stringResource(R.string.tts_config_fish_audio_model_s21_pro_free),
        "s2-pro" to "S2-Pro",
        "s1" to "S1"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID (reference_id)
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.referenceId,
            onValueChange = { newReferenceId ->
                onValueChange(setting.copy(referenceId = newReferenceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("802e3bc2b27e49c2995d23ef70e6ac89") }
        )
    }

    // Temperature
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_temperature)) },
        description = { Text(stringResource(R.string.setting_tts_page_temperature_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.temperature,
            onValueChange = { newTemperature ->
                onValueChange(setting.copy(temperature = newTemperature.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.7",
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_fish_audio_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                onValueChange(setting.copy(speed = newSpeed.coerceIn(0.5f, 2f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "1.0",
        )
    }
}

@Composable
private fun StepTTSConfiguration(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.tts_config_step_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_config_step_api_key_placeholder)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    // Model
    val models = listOf(
        "step-tts-mini" to stringResource(R.string.tts_config_step_model_mini),
        "step-tts-vivid" to stringResource(R.string.tts_config_step_model_vivid),
        "stepaudio-2.5-tts" to stringResource(R.string.tts_config_step_model_stepaudio),
        "step-tts-2" to stringResource(R.string.tts_config_step_model_tts2),
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice
    // 部分常用 voice-id, 完整列表见官方开发指南
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices = listOf(
        "elegantgentle-female" to stringResource(R.string.tts_config_step_voice_elegantgentle_female),
        "livelybreezy-female" to stringResource(R.string.tts_config_step_voice_livelybreezy_female),
        "energeticconfident-female" to stringResource(R.string.tts_config_step_voice_energeticconfident_female),
        "jingdiannvsheng" to stringResource(R.string.tts_config_step_voice_jingdiannvsheng),
        "wenroushunv" to stringResource(R.string.tts_config_step_voice_wenroushunv),
        "tianmeinvsheng" to stringResource(R.string.tts_config_step_voice_tianmeinvsheng),
        "qingchunshaonv" to stringResource(R.string.tts_config_step_voice_qingchunshaonv),
        "wenrounvsheng" to stringResource(R.string.tts_config_step_voice_wenrounvsheng),
        "ruanmengnvsheng" to stringResource(R.string.tts_config_step_voice_ruanmengnvsheng),
        "youyanvsheng" to stringResource(R.string.tts_config_step_voice_youyanvsheng),
        "lengyanyujie" to stringResource(R.string.tts_config_step_voice_lengyanyujie),
        "shuangkuaijiejie" to stringResource(R.string.tts_config_step_voice_shuangkuaijiejie),
        "wenjingxuejie" to stringResource(R.string.tts_config_step_voice_wenjingxuejie),
        "linjiajiejie" to stringResource(R.string.tts_config_step_voice_linjiajiejie),
        "linjiameimei" to stringResource(R.string.tts_config_step_voice_linjiameimei),
        "zhixingjiejie" to stringResource(R.string.tts_config_step_voice_zhixingjiejie),
        "cixingnansheng" to stringResource(R.string.tts_config_step_voice_cixingnansheng),
        "wenrounansheng" to stringResource(R.string.tts_config_step_voice_wenrounansheng),
        "yuanqinansheng" to stringResource(R.string.tts_config_step_voice_yuanqinansheng),
        "zhengpaiqingnian" to stringResource(R.string.tts_config_step_voice_zhengpaiqingnian),
        "ruyananshi" to stringResource(R.string.tts_config_step_voice_ruyananshi),
        "boyinnansheng" to stringResource(R.string.tts_config_step_voice_boyinnansheng),
        "shenchennanyin" to stringResource(R.string.tts_config_step_voice_shenchennanyin),
        "shuangkuainansheng" to stringResource(R.string.tts_config_step_voice_shuangkuainansheng),
        "ganliannvsheng" to stringResource(R.string.tts_config_step_voice_ganliannvsheng),
        "qinhenvsheng" to stringResource(R.string.tts_config_step_voice_qinhenvsheng),
        "huolinvsheng" to stringResource(R.string.tts_config_step_voice_huolinvsheng),
        "jilingshaonv" to stringResource(R.string.tts_config_step_voice_jilingshaonv),
        "yuanqishaonv" to stringResource(R.string.tts_config_step_voice_yuanqishaonv),
        "wenrougongzi" to stringResource(R.string.tts_config_step_voice_wenrougongzi),
        "qingniandaxuesheng" to stringResource(R.string.tts_config_step_voice_qingniandaxuesheng),
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voice = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Response Format
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    FormItem(
        label = { Text("Response Format") },
        description = { Text(stringResource(R.string.tts_config_step_response_format_desc)) }
    ) {
        SelectTextField(
            value = setting.responseFormat,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(responseFormat = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(responseFormat = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.tts_config_step_speed_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Volume
    FormItem(
        label = { Text("Volume") },
        description = { Text(stringResource(R.string.tts_config_step_volume_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Volume"
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    FormItem(
        label = { Text("Sample Rate") },
        description = { Text(stringResource(R.string.tts_config_step_sample_rate_desc)) }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { rate ->
                onValueChange(setting.copy(sampleRate = rate))
            },
            optionToString = { rate -> "$rate Hz" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    FormItem(
        label = { Text("Instruction") },
        description = { Text(stringResource(R.string.tts_config_step_instruction_desc)) }
    ) {
        OutlinedTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_config_step_instruction_placeholder)) },
            minLines = 2,
            maxLines = 4,
        )
    }
}

@Composable
private fun DeepSeekTTSConfiguration(
    setting: TTSProviderSetting.DeepSeek,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key（与 DeepSeek 聊天/搜索同一把 Key）
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.tts_config_deepseek_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.deepseek.com") }
        )
    }

    // Voice（stella 女 / tide 男；无语种与语速参数，按文本自动判定）
    val voices = listOf(
        "stella" to "Stella",
        "tide" to "Tide",
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.tts_config_deepseek_voice_desc)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voices,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            optionToString = { (_, displayName) -> displayName },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Format（wav 直接交播放器；pcm 是裸 16-bit 单声道 24kHz 流）
    val formats = listOf("wav", "pcm")

    FormItem(
        label = { Text("Format") },
        description = { Text(stringResource(R.string.tts_config_deepseek_format_desc)) }
    ) {
        SelectTextField(
            value = setting.format,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(format = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(format = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Filter Markdown（本仓朗读前已 stripMarkdown，默认关）
    FormItem(
        label = { Text("Filter Markdown") },
        description = { Text(stringResource(R.string.tts_config_deepseek_filter_markdown_desc)) }
    ) {
        Switch(
            checked = setting.filterMarkdown,
            onCheckedChange = { checked ->
                onValueChange(setting.copy(filterMarkdown = checked))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
