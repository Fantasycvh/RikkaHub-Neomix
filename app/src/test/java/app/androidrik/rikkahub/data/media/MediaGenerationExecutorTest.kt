package app.androidrik.rikkahub.data.media

import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3b-1 目录切换后执行器只剩 mediagen 任务链路：legacy 生图模型在目录层映射成
 * Media target（见 ScheduledMediaActionTest 的映射/指纹用例），这里覆盖统一消费路径
 * （同步终态任务 → 画廊 item，url 产物走下载）。
 */
class MediaGenerationExecutorTest {
    private val executor = MediaGenerationExecutor(MediaGenerationManager())

    @Test
    fun `media provider images can be consumed by the standalone gallery and chat tool`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/v1/images/generations", request.url.encodedPath)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":[{"b64_json":"aW1hZ2U="}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val executor = MediaGenerationExecutor(
            MediaGenerationManager(client),
        )
        val target = MediaGenerationTarget.Media(
            MediaGenerationProviderSetting.OpenAI(baseUrl = "https://example.test/v1", apiKey = "test"),
            MediaGenerationModel("media-image", MediaKind.IMAGE),
        )

        val images = executor.images(target, MediaGenerationRequest(prompt = "Draw a lake")).toList()

        assertEquals(listOf(ImageGenerationItem("aW1hZ2U=", "image/png")), images)
    }

    @Test
    fun `url outputs are downloaded and re-encoded for the gallery`() = runTest {
        val imageBytes = byteArrayOf(1, 2, 3)
        // 提交与产物下载共用同一个 fake client（executor 的 downloadClient 是独立参数）
        val fakeClientForUrls = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.endsWith("/images/generations")) {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"data":[{"url":"https://cdn.example.test/out.png"}]}"""
                            .toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    // 产物 CDN 下载
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(imageBytes.toResponseBody("image/png".toMediaType()))
                        .build()
                }
            }.build()
        val executor = MediaGenerationExecutor(
            MediaGenerationManager(fakeClientForUrls),
            downloadClient = fakeClientForUrls,
        )
        val target = MediaGenerationTarget.Media(
            MediaGenerationProviderSetting.OpenAI(baseUrl = "https://example.test/v1", apiKey = "test"),
            MediaGenerationModel("media-image", MediaKind.IMAGE),
        )

        val images = executor.images(target, MediaGenerationRequest(prompt = "Draw a lake")).toList()

        assertEquals(1, images.size)
        assertEquals("image/png", images.single().mimeType)
        assertEquals(java.util.Base64.getEncoder().encodeToString(imageBytes), images.single().data)
    }

    @Test
    fun `json body prompt passes through the executor`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val executor = MediaGenerationExecutor(
            MediaGenerationManager(OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"data":[{"b64_json":"aW1hZ2U="}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()),
        )
        val target = MediaGenerationTarget.Media(
            MediaGenerationProviderSetting.OpenAI(baseUrl = "https://example.test/v1", apiKey = "test"),
            MediaGenerationModel("media-image", MediaKind.IMAGE),
        )

        executor.images(target, MediaGenerationRequest(prompt = "Draw a lake")).toList()

        val sent = kotlinx.serialization.json.Json.parseToJsonElement(
            okio.Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8(),
        ).jsonObject
        assertEquals(JsonPrimitive("Draw a lake"), sent["prompt"])
    }
}
