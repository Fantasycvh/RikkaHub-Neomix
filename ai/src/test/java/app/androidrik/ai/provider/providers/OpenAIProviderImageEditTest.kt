package app.androidrik.ai.provider.providers

import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.ImageEditParams
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * 图片编辑的两条路径（对齐上游 `85af5b91` / #1993）：
 *
 * - OpenRouter **没有** `/images/edits`（打过去必 404）⇒ 改走 `/images/generations` +
 *   `input_references`（base64 data URL）；
 * - 其余 OpenAI 兼容端点维持 multipart `/images/edits`（回归护栏）。
 *
 * 为什么 host 判定与请求体构造要单独测：MockWebServer 起在 localhost，**无法伪装 host**，
 * 故 OpenRouter 分支不可能从 wire 侧覆盖——把这两段抽成顶层 internal 函数后在此直测；
 * 非 OpenRouter 路径则用真实请求验证「没被这次改动带偏」。
 */
class OpenAIProviderImageEditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeImage(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): String {
        val file = File(tmp.newFolder(), name)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun params(images: List<String>, size: String = "auto", customBody: List<CustomBody> = emptyList()) =
        ImageEditParams(
            model = Model(modelId = "openai/gpt-image-1"),
            prompt = "make it blue",
            images = images,
            numOfImages = 1,
            size = size,
            customBody = customBody,
        )

    @Test
    fun `openrouter host detection is exact`() {
        assertTrue(isOpenRouter("https://openrouter.ai/api/v1"))
        assertTrue(isOpenRouter("https://OpenRouter.AI/api/v1")) // 大小写不敏感
        assertTrue(isOpenRouter("https://openrouter.ai:443/api/v1")) // host 已剥端口
        // 不能被后缀/包含匹配骗到（否则会把图发到第三方域名）
        assertFalse(isOpenRouter("https://openrouter.ai.evil.com/v1"))
        assertFalse(isOpenRouter("https://notopenrouter.ai/v1"))
        assertFalse(isOpenRouter("https://api.openai.com/v1"))
        assertFalse(isOpenRouter("not a url"))
    }

    @Test
    fun `openrouter edit body carries reference images as base64 data urls`() {
        val png = writeImage("ref.png")
        val body = buildOpenRouterEditBody(params(listOf(png)))

        assertEquals("openai/gpt-image-1", body["model"]?.jsonPrimitive?.content)
        assertEquals("make it blue", body["prompt"]?.jsonPrimitive?.content)
        assertEquals(1, body["n"]?.jsonPrimitive?.content?.toInt())

        val refs = body["input_references"]!!.jsonArray
        assertEquals(1, refs.size)
        val ref = refs[0].jsonObject
        assertEquals("image_url", ref["type"]?.jsonPrimitive?.content)
        val url = ref["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            url,
        )
    }

    @Test
    fun `openrouter edit body omits size when auto and keeps it otherwise`() {
        val png = writeImage("ref.png")
        assertFalse(
            "size=auto 不写进请求体（与生成/编辑两条路径同约定）",
            buildOpenRouterEditBody(params(listOf(png), size = "auto")).containsKey("size"),
        )
        assertEquals(
            "1024x1024",
            buildOpenRouterEditBody(params(listOf(png), size = "1024x1024"))["size"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `openrouter edit body merges custom body and rejects missing files`() {
        val png = writeImage("ref.png")
        val withCustom = buildOpenRouterEditBody(
            params(listOf(png), customBody = listOf(CustomBody("quality", JsonPrimitive("high")))),
        )
        assertEquals("high", withCustom["quality"]?.jsonPrimitive?.content)

        val missing = runCatching {
            buildOpenRouterEditBody(params(listOf(File(tmp.root, "nope.png").absolutePath)))
        }
        assertTrue("缺图必须报错而不是发一个空 input_references", missing.isFailure)
    }

    @Test
    fun `non openrouter endpoints still post multipart to images edits`() {
        val server = MockWebServer()
        server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"stop here"}}"""))
        server.start()
        try {
            val provider = ProviderSetting.OpenAI(
                name = "imggen-edit-test",
                apiKey = "sk-test",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
            )
            val api = OpenAIProvider(OkHttpClient())
            runCatching {
                runBlocking {
                    api.editImage(provider, params(listOf(writeImage("ref.png")))).collect { }
                }
            }

            val recorded = server.takeRequest()
            assertEquals("/v1/images/edits", recorded.url.encodedPath)
            assertTrue(
                "multipart 路径不变",
                recorded.headers["Content-Type"]?.startsWith("multipart/form-data") == true,
            )
        } finally {
            server.close()
        }
    }
}
