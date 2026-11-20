package app.androidrik.mediagen.provider.providers.openrouter

import app.androidrik.common.http.CustomBody
import app.androidrik.common.http.CustomHeader
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.providers.bodyAsString
import app.androidrik.mediagen.provider.providers.fakeClient
import app.androidrik.mediagen.provider.providers.jsonResponse
import app.androidrik.mediagen.provider.providers.mediaGenerationJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * OpenRouter 图片适配器（M3a-3 parity）：生成/编辑同走 `/images/generations`，
 * 参考图以 `input_references`（base64 data URL）随 JSON 传入（上游 #1993 同款）。
 */
class OpenRouterMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val setting = MediaGenerationProviderSetting.OpenRouter(apiKey = "sk-or-test")
    private val model = MediaGenerationModel(modelId = "openai/gpt-image-2", kind = MediaKind.IMAGE)

    @Test
    fun editInputsGoThroughInputReferencesOnTheGenerationEndpoint() = runBlocking {
        val image = tempFolder.newFile("ref.png").apply { writeBytes(ByteArray(4)) }
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(request = it, body = """{"data":[{"b64_json":"aGVsbG8="}]}""")
        })

        val tasks = manager.generate(
            setting,
            model,
            MediaGenerationRequest(prompt = "make it red", inputs = listOf(MediaGenerationInput.Image(image.absolutePath))),
        ).toList()

        val request = requests.single()
        assertEquals("https://openrouter.ai/api/v1/images/generations", request.url.toString())
        assertEquals("Bearer sk-or-test", request.header("Authorization"))
        val body = mediaGenerationJson.parseToJsonElement(request.bodyAsString()).jsonObject
        assertEquals(JsonPrimitive("make it red"), body["prompt"])
        val references = body["input_references"]!!.jsonArray
        val entry = references.single().jsonObject
        assertEquals(JsonPrimitive("image_url"), entry["type"])
        assertTrue(entry.toString().contains("data:image/png;base64,"))
        assertEquals(MediaGenerationStatus.SUCCEEDED, tasks.single().status)
        assertEquals("openai/gpt-image-2", tasks.single().model)
    }

    @Test
    fun generationBodyOmitsAutoSizeAndAppliesModelCustomBodiesLast() {
        val provider = OpenRouterMediaGenerationProvider(
            okhttp3.OkHttpClient(),
        )
        val customized = model.copy(customBodies = listOf(CustomBody("n", JsonPrimitive(3))))
        val body = provider.buildGenerationBody(
            model = customized,
            request = MediaGenerationRequest(prompt = "cat", count = 1, resolution = "auto"),
        ).jsonObject
        assertEquals(JsonPrimitive(3), body["n"])
        assertTrue("size" !in body)

        val explicit = provider.buildGenerationBody(
            model = customized,
            request = MediaGenerationRequest(prompt = "cat", count = 1, resolution = "1024x1024"),
        ).jsonObject
        assertEquals(JsonPrimitive("1024x1024"), explicit["size"])
    }

    @Test
    fun modelCustomHeadersAreSentWithTheRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val customized = model.copy(customHeaders = listOf(CustomHeader("X-OR-Test", "yes")))
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(request = it, body = """{"data":[{"b64_json":"aGVsbG8="}]}""")
        })

        manager.generate(setting, customized, MediaGenerationRequest(prompt = "cat")).toList()

        assertEquals("yes", requests.single().header("X-OR-Test"))
    }
}
