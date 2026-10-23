package app.androidrik.mediagen.provider.providers

import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「获取模型列表」的解析与兜底逻辑。
 *
 * 不联网：用 [fakeClient] 挡住请求，只验证「请求发到哪、带没带鉴权、响应怎么变成模型列表」。
 * 真实厂商的返回形态见注释里的示例。
 */
class MediaModelListTest {
    private val setting = MediaGenerationProviderSetting.OpenAI(
        apiKey = "sk-test",
        baseUrl = "https://example.com/v1/",
    )

    private fun listOpenAiModels(body: String): Pair<List<String>, okhttp3.Request> = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val provider = app.androidrik.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider(
            fakeClient(requests) { request -> jsonResponse(request, body) }
        )
        val models = provider.listModels(setting).getOrThrow()
        assertEquals(models.size, models.map { it.modelId }.distinct().size)
        models.map { it.modelId } to requests.single()
    }

    @Test
    fun openAiListModelsKeepsOnlyImageModels() {
        // 官方 /v1/models 的形态：data[].id，没有用途字段
        val body = """
            {"object":"list","data":[
              {"id":"gpt-4o","object":"model"},
              {"id":"gpt-image-1","object":"model"},
              {"id":"dall-e-3","object":"model"},
              {"id":"text-embedding-3-small","object":"model"}
            ]}
        """.trimIndent()

        val (ids, request) = listOpenAiModels(body)

        assertEquals(listOf("gpt-image-1", "dall-e-3"), ids)
        assertEquals("https://example.com/v1/models", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertNull(request.body)
    }

    @Test
    fun openAiListModelsFallsBackToEveryIdWhenNothingLooksLikeAnImageModel() {
        // 中转站常把模型改名成 flux-dev 之类，期望空手而归不如把候选全给用户勾选
        val body = """{"data":[{"id":"flux-dev"},{"id":"flux-pro"}]}"""

        val (ids, _) = listOpenAiModels(body)

        assertEquals(listOf("flux-dev", "flux-pro"), ids)
    }

    @Test
    fun openAiListModelsAssignsImageKind() {
        val body = """{"data":[{"id":"gpt-image-1"}]}"""
        runBlocking {
            val provider = app.androidrik.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider(
                fakeClient { request -> jsonResponse(request, body) }
            )
            val model = provider.listModels(setting).getOrThrow().single()
            assertEquals(MediaKind.IMAGE, model.kind)
            assertEquals("gpt-image-1", model.displayName)
        }
    }

    @Test
    fun openAiListModelsSurfacesApiErrors() {
        runBlocking {
            val provider = app.androidrik.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider(
                fakeClient { request ->
                    jsonResponse(request, """{"error":{"message":"invalid api key"}}""", code = 401)
                }
            )
            val error = provider.listModels(setting).exceptionOrNull()
            assertTrue(error is MediaGenerationApiException)
            assertTrue((error as MediaGenerationApiException).message.contains("invalid api key"))
        }
    }

    @Test
    fun siliconFlowListModelsAsksForVideoTypeAndFiltersChatModels() {
        // /models?type=video 生效时只返回视频模型；万一被忽略而混入聊天模型，按 id 特征兜底过滤
        val body = """
            {"data":[
              {"id":"deepseek-ai/DeepSeek-V3"},
              {"id":"Wan-AI/Wan2.2-T2V-A14B"},
              {"id":"Qwen/Qwen2.5-7B-Instruct"},
              {"id":"lightricks/LTX-Video"}
            ]}
        """.trimIndent()
        runBlocking {
            val requests = mutableListOf<okhttp3.Request>()
            val provider = app.androidrik.mediagen.provider.providers.siliconflow.SiliconFlowMediaGenerationProvider(
                fakeClient(requests) { request -> jsonResponse(request, body) }
            )
            val setting = MediaGenerationProviderSetting.SiliconFlow(
                apiKey = "sf-test",
                baseUrl = "https://api.example.com/v1/",
            )

            val models = provider.listModels(setting).getOrThrow()

            assertEquals(listOf("Wan-AI/Wan2.2-T2V-A14B", "lightricks/LTX-Video"), models.map { it.modelId })
            assertTrue(models.all { it.kind == MediaKind.VIDEO })
            assertEquals(
                "https://api.example.com/v1/models?type=video",
                requests.single().url.toString(),
            )
        }
    }

    @Test
    fun siliconFlowListModelsFallsBackToEveryIdWhenNoVideoModelMatches() {
        val body = """{"data":[{"id":"wan-turbo"},{"id":"spark-video-x"}]}"""
        runBlocking {
            val provider = app.androidrik.mediagen.provider.providers.siliconflow.SiliconFlowMediaGenerationProvider(
                fakeClient { request -> jsonResponse(request, body) }
            )
            val models = provider.listModels(MediaGenerationProviderSetting.SiliconFlow(apiKey = "k")).getOrThrow()
            // spark-video-x 含 video 会被保留；wan-turbo 不匹配，但因为整体匹配非空所以被过滤掉
            assertEquals(listOf("spark-video-x"), models.map { it.modelId })
        }
    }

    @Test
    fun managerOnlyClaimsModelListSupportForPortedAdapters() {
        val manager = MediaGenerationManager(fakeClient { error("must not hit the network") })

        assertTrue(manager.supportsModelList(MediaGenerationProviderSetting.OpenAI()))
        assertTrue(manager.supportsModelList(MediaGenerationProviderSetting.SiliconFlow()))
        assertEquals(false, manager.supportsModelList(MediaGenerationProviderSetting.Aliyun()))
        assertEquals(false, manager.supportsModelList(MediaGenerationProviderSetting.MiniMax()))
    }

    @Test
    fun managerListModelsFailsForUnportedProviders() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("must not hit the network") })

        val error = manager.listModels(MediaGenerationProviderSetting.Aliyun()).exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertEquals("Provider not ported yet: Aliyun", error!!.message)
    }
}
