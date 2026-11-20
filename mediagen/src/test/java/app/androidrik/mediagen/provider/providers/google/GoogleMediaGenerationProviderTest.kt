package app.androidrik.mediagen.provider.providers.google

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Google (Gemini) 图片适配器（M3a-4 平移）：generateContent + IMAGE 输出模态。
 * 覆盖请求体形态（模态/宽高比/customBody 合并/参考图顺序）、响应解析（inlineData 提取/
 * 草稿图跳过/拦截与纯文本拒绝透传）、n 语义循环、AI Studio 鉴权头。
 */
class GoogleMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = GoogleMediaGenerationProvider(OkHttpClient())
    private val setting = MediaGenerationProviderSetting.Google(apiKey = "AIza-test")
    private val model = MediaGenerationModel(modelId = "gemini-2.5-flash-image", kind = MediaKind.IMAGE)

    private fun params(size: String? = null, n: Int = 1) = MediaGenerationRequest(
        prompt = "a cat",
        count = n,
        resolution = size,
    )

    @Test
    fun generationBodyDeclaresTextAndImageModalities() {
        val body = provider.buildGenerationBody(model, params()).jsonObject
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(listOf("TEXT", "IMAGE"), (config["responseModalities"] as JsonArray).map { it.jsonPrimitive.content })
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertEquals("a cat", parts.single().jsonObject["text"]!!.jsonPrimitive.content)
        // 全类目安全设置 OFF（与聊天路径同口径）
        assertEquals(5, (body["safetySettings"] as JsonArray).size)
    }

    @Test
    fun autoSizeOmitsImageConfigButExplicitSizesMapToAspectRatio() {
        val auto = provider.buildGenerationBody(model, params(size = "auto")).jsonObject
        assertNull(auto["generationConfig"]!!.jsonObject["imageConfig"])

        val square = provider.buildGenerationBody(model, params(size = "1024x1024")).jsonObject
        assertEquals("1:1", square["generationConfig"]!!.jsonObject["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)

        val landscape = provider.buildGenerationBody(model, params(size = "1792x1024")).jsonObject
        assertEquals("16:9", landscape["generationConfig"]!!.jsonObject["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
    }

    @Test
    fun modelCustomBodiesMergeIntoTheTopLevelRequest() {
        val body = provider.buildGenerationBody(
            model.copy(customBodies = listOf(CustomBody("temperature", JsonPrimitive(0.7)))),
            params(),
        ).jsonObject
        assertEquals(JsonPrimitive(0.7), body["temperature"])
    }

    @Test
    fun syncCreateLoopsCountAndSendsApiKeyHeader() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(request = it, body = """
                {"candidates":[{"content":{"parts":[
                   {"thought":true,"inlineData":{"mimeType":"image/png","data":"draft"}},
                   {"inlineData":{"mimeType":"image/png","data":"SGVsbG8="}}
                ]}}]}
            """.trimIndent())
        })

        val task = manager.generate(setting, model, params(n = 2)).toList().single()

        assertEquals(2, requests.size)
        assertEquals("AIza-test", requests.first().header("x-goog-api-key"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent",
            requests.first().url.toString())
        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertEquals("gemini-2.5-flash-image", task.model)
        // 草稿图跳过，每轮请求产一张
        assertEquals(2, task.outputs.size)
        assertEquals("image/png", task.outputs.first().mimeType)
        assertEquals("Hello", task.outputs.first().data!!.decodeToString())
    }

    @Test
    fun editInputsAreInlinedBeforeThePrompt() = runBlocking {
        val image = tempFolder.newFile("ref.png").apply { writeBytes(ByteArray(4)) }
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(request = it, body = """
                {"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"SGVsbG8="}}]}}]}
            """.trimIndent())
        })

        manager.generate(
            setting,
            model,
            MediaGenerationRequest(prompt = "make it red", inputs = listOf(MediaGenerationInput.Image(image.absolutePath))),
        ).toList()

        val parts = mediaGenerationJson.parseToJsonElement(requests.single().bodyAsString()).jsonObject
        val content = parts["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        val rendered = content.toString()
        assertTrue(rendered.contains("inlineData"))
        assertTrue(rendered.indexOf("inlineData") < rendered.indexOf("make it red"))
    }

    @Test
    fun blockedPromptsSurfaceBlockReason() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(request = it, body = """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        })
        try {
            manager.generate(setting, model, params()).toList()
            fail("Expected blocked prompt to throw")
        } catch (e: IllegalStateException) {
            assertEquals("Prompt blocked: SAFETY", e.message)
        }
    }

    @Test
    fun textRefusalsSurfaceWhenNoImageIsReturned() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(request = it, body = """
                {"candidates":[{"content":{"parts":[{"text":"I cannot help with that."}]}}]}
            """.trimIndent())
        })
        try {
            manager.generate(setting, model, params()).toList()
            fail("Expected text-only response to throw")
        } catch (e: IllegalStateException) {
            assertEquals("I cannot help with that.", e.message)
        }
    }
}
