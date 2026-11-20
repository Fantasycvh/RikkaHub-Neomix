package app.androidrik.rikkahub.data.media

import app.androidrik.common.http.CustomBody
import app.androidrik.common.http.CustomHeader
import app.androidrik.ai.provider.ImageEditParams
import app.androidrik.ai.provider.ImageGenerationParams
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.ai.ui.UIMessage
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaGenerationExecutorTest {
    private val provider = RecordingImageProvider()
    private val executor = MediaGenerationExecutor({ provider }, MediaGenerationManager())
    private val model = Model(
        modelId = "example/image-model",
        customHeaders = listOf(CustomHeader("X-Test", "test-value")),
        customBodies = listOf(CustomBody("quality", JsonPrimitive("high"))),
    )
    private val target = MediaGenerationTarget.Image(ProviderSetting.OpenAI(), model)

    @Test
    fun `image generation forwards model options and preserves preview order`() = runTest {
        val request = MediaGenerationRequest(prompt = "Draw a lake", count = 2, resolution = "1024x1024")

        val result = executor.submit(target, request) as MediaGenerationResult.ImageStream

        assertEquals(listOf(provider.preview, provider.finalImage), result.images.toList())
        assertEquals(
            ImageGenerationParams(
                model = model,
                prompt = "Draw a lake",
                numOfImages = 2,
                size = "1024x1024",
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
            provider.generation,
        )
        assertNull(provider.edit)
    }

    @Test
    fun `reference images select editing and preserve input order`() = runTest {
        val paths = listOf("first.png", "second.png")
        val request = MediaGenerationRequest(
            prompt = "Combine these images",
            inputs = paths.map { MediaGenerationInput.Image(url = it) },
        )

        val result = executor.submit(target, request) as MediaGenerationResult.ImageStream

        assertEquals(listOf(provider.finalImage), result.images.toList())
        assertEquals(
            ImageEditParams(
                model = model,
                prompt = "Combine these images",
                images = paths,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
            provider.edit,
        )
        assertNull(provider.generation)
    }

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
        val executor = MediaGenerationExecutor({ error("Unexpected chat provider") }, MediaGenerationManager(client))
        val target = MediaGenerationTarget.Media(
            MediaGenerationProviderSetting.OpenAI(baseUrl = "https://example.test/v1", apiKey = "test"),
            MediaGenerationModel("media-image", MediaKind.IMAGE),
        )

        val images = executor.images(target, MediaGenerationRequest(prompt = "Draw a lake")).toList()

        assertEquals(listOf(ImageGenerationItem("aW1hZ2U=", "image/png")), images)
    }

    private class RecordingImageProvider : Provider<ProviderSetting> {
        val preview = ImageGenerationItem("preview", "image/png", partial = true, partialImageIndex = 0)
        val finalImage = ImageGenerationItem("final", "image/png")
        var generation: ImageGenerationParams? = null
        var edit: ImageEditParams? = null

        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams) =
            flowOf(preview, finalImage).also { generation = params }

        override suspend fun editImage(providerSetting: ProviderSetting, params: ImageEditParams) =
            flowOf(finalImage).also { edit = params }

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Nothing = error("Unexpected text generation")

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Nothing = error("Unexpected text streaming")
    }
}
