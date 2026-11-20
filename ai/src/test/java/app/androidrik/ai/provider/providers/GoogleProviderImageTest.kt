package app.androidrik.ai.provider.providers

import app.androidrik.common.http.CustomBody
import app.androidrik.ai.provider.ImageEditParams
import app.androidrik.ai.provider.ImageGenerationParams
import app.androidrik.ai.provider.Model
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Google (Gemini) 生图执行层：generateContent + IMAGE 输出模态（统一媒体目录 legacy 分支放宽）。
 * 覆盖请求体形态（模态/宽高比/customBody 合并/参考图顺序）、响应解析（inlineData 提取/草稿图
 * 跳过/拦截与纯文本拒绝透传）。
 */
class GoogleProviderImageTest {

    private val provider = GoogleProvider(OkHttpClient())

    private fun params(size: String = "auto", n: Int = 1) = ImageGenerationParams(
        model = Model(modelId = "gemini-2.5-flash-image"),
        prompt = "a cat",
        numOfImages = n,
        size = size,
    )

    @Test
    fun `generation body declares text and image modalities`() {
        val body = provider.imageGenerationBody(params()).jsonObject
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(listOf("TEXT", "IMAGE"),
            (config["responseModalities"] as JsonArray).map { it.jsonPrimitive.content })
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertEquals("a cat", parts.single().jsonObject["text"]!!.jsonPrimitive.content)
        // 全类目安全设置 OFF（与聊天路径同口径）
        assertEquals(5, (body["safetySettings"] as JsonArray).size)
        assertTrue(body["safetySettings"]!!.toString().contains("HARM_CATEGORY_SEXUALLY_EXPLICIT"))
    }

    @Test
    fun `auto size omits imageConfig but explicit sizes map to aspect ratio`() {
        val auto = provider.imageGenerationBody(params(size = "auto")).jsonObject
        assertNull(auto["generationConfig"]!!.jsonObject["imageConfig"])

        val square = provider.imageGenerationBody(params(size = "1024x1024")).jsonObject
        assertEquals("1:1", square["generationConfig"]!!.jsonObject["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)

        val landscape = provider.imageGenerationBody(params(size = "1792x1024")).jsonObject
        assertEquals("16:9", landscape["generationConfig"]!!.jsonObject["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
    }

    @Test
    fun `model custom bodies merge into the top level request`() {
        val body = provider.imageGenerationBody(
            params().copy(customBody = listOf(CustomBody("responseMimeType", kotlinx.serialization.json.JsonPrimitive("application/json"))))
        ).jsonObject
        assertEquals("application/json", body["responseMimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edit body puts reference images before the prompt`() = runBlocking {
        val image = File.createTempFile("reference", ".png").apply { writeBytes(ByteArray(8)) }
        try {
            val body = provider.imageEditBody(
                ImageEditParams(
                    model = Model(modelId = "gemini-2.5-flash-image"),
                    prompt = "make it red",
                    images = listOf(image.absolutePath),
                )
            ).jsonObject
            val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
            val rendered = parts.toString()
            assertTrue(rendered.contains("inlineData"))
            assertTrue(rendered.contains("image/png"))
            assertTrue(rendered.indexOf("inlineData") < rendered.indexOf("make it red"))
            assertEquals("make it red", parts.last().jsonObject["text"]!!.jsonPrimitive.content)
        } finally {
            image.delete()
        }
    }

    @Test
    fun `edit requires an existing reference file`() = runBlocking {
        try {
            provider.imageEditBody(
                ImageEditParams(
                    model = Model(modelId = "gemini-2.5-flash-image"),
                    prompt = "p",
                    images = listOf("/nonexistent/reference.png"),
                )
            )
            fail("Expected missing reference file to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not exist"))
        }
    }

    @Test
    fun `parse collects inline images across candidates and skips thought drafts`() {
        val body = """
            {"candidates":[
              {"content":{"parts":[
                 {"thought":true,"inlineData":{"mimeType":"image/png","data":"draft"}},
                 {"text":"here you go"},
                 {"inlineData":{"mimeType":"image/png","data":"AAA"}}
              ]}},
              {"content":{"parts":[{"inlineData":{"mimeType":"image/jpeg","data":"BBB"}}]}}
            ]}
        """.trimIndent()
        val items = provider.parseImageResponse(body)
        assertEquals(listOf("AAA", "BBB"), items.map { it.data })
        assertEquals(listOf("image/png", "image/jpeg"), items.map { it.mimeType })
    }

    @Test
    fun `parse surfaces prompt block reasons`() {
        val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        try {
            provider.parseImageResponse(body)
            fail("Expected blocked prompt to throw")
        } catch (e: Exception) {
            assertEquals("Prompt blocked: SAFETY", e.message)
        }
    }

    @Test
    fun `parse surfaces text refusals when no image is returned`() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"I cannot help with that."}]}}]}
        """.trimIndent()
        try {
            provider.parseImageResponse(body)
            fail("Expected text-only response to throw")
        } catch (e: Exception) {
            assertEquals("I cannot help with that.", e.message)
        }
    }

    @Test
    fun `parse rejects responses without candidates`() {
        try {
            provider.parseImageResponse("{}")
            fail("Expected missing candidates to throw")
        } catch (e: Exception) {
            assertEquals("No candidates in response", e.message)
        }
    }
}
