package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationCapabilitiesTest {
    private val client = OkHttpClient()

    // 本模块只移植了 OpenAI 适配器，其余厂商的适配器未移植，无法在这里构造请求体
    private val settings = listOf(
        MediaGenerationProviderSetting.OpenAI(),
    )

    // 各适配器组装请求体的入口，不支持的公共字段会在这里被拒绝
    private fun buildBody(setting: MediaGenerationProviderSetting, kind: MediaKind, request: MediaGenerationRequest) {
        val model = MediaGenerationModel(modelId = "model", kind = kind)
        when (setting) {
            is MediaGenerationProviderSetting.OpenAI ->
                OpenAIMediaGenerationProvider(client).buildGenerationBody(setting, model, request)

            else -> error("Provider not ported yet: ${setting::class.simpleName}")
        }
    }

    private fun requestWith(parameter: MediaGenerationParameter): MediaGenerationRequest = when (parameter) {
        MediaGenerationParameter.COUNT -> MediaGenerationRequest(prompt = "cat", count = 2)
        MediaGenerationParameter.RESOLUTION -> MediaGenerationRequest(prompt = "cat", resolution = "1024x1024")
        MediaGenerationParameter.ASPECT_RATIO -> MediaGenerationRequest(prompt = "cat", aspectRatio = "16:9")
        MediaGenerationParameter.DURATION -> MediaGenerationRequest(prompt = "cat", durationSeconds = 5)
        MediaGenerationParameter.GENERATE_AUDIO -> MediaGenerationRequest(prompt = "cat", generateAudio = true)
        MediaGenerationParameter.WATERMARK -> MediaGenerationRequest(prompt = "cat", watermark = false)
        MediaGenerationParameter.SEED -> MediaGenerationRequest(prompt = "cat", seed = 1)
        MediaGenerationParameter.PROMPT_ENHANCEMENT -> MediaGenerationRequest(prompt = "cat", promptEnhancement = true)
    }

    @Test
    fun capabilitiesExistExactlyForSupportedKinds() {
        settings.forEach { setting ->
            val declared = MediaKind.entries.filter { setting.capabilities(it) != null }.toSet()
            assertEquals(setting.name, setting.supportedKinds, declared)
        }
    }

    @Test
    fun adaptersAcceptDeclaredParametersAndRejectTheRest() {
        settings.forEach { setting ->
            setting.supportedKinds.forEach { kind ->
                val capabilities = setting.capabilities(kind)
                assertNotNull(capabilities)
                MediaGenerationParameter.entries.forEach { parameter ->
                    val request = requestWith(parameter)
                    if (parameter in capabilities!!.parameters) {
                        buildBody(setting, kind, request)
                    } else {
                        assertThrows("${setting.name} $kind $parameter", IllegalArgumentException::class.java) {
                            buildBody(setting, kind, request)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun requiredParametersAreSupported() {
        settings.forEach { setting ->
            setting.supportedKinds.forEach { kind ->
                val capabilities = setting.capabilities(kind)!!
                assertTrue(
                    "${setting.name} $kind",
                    capabilities.parameters.containsAll(capabilities.requiredParameters),
                )
            }
        }
    }
}
