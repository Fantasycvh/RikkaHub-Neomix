package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.providers.fakeClient
import app.androidrik.mediagen.provider.providers.jsonResponse
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationManagerTest {
    @Test
    fun unportedProviderFailsWithoutHittingTheNetwork() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("unported provider must not hit the network") })
        val setting = MediaGenerationProviderSetting.Aliyun()
        val model = MediaGenerationModel(modelId = "wan3.0-video", kind = MediaKind.VIDEO)

        val createError = manager.create(setting, model, MediaGenerationRequest(prompt = "cat")).exceptionOrNull()
        assertTrue(createError is UnsupportedOperationException)
        assertEquals("Provider not ported yet: Aliyun", createError!!.message)

        val queryError = manager.query(setting, model, "task-1").exceptionOrNull()
        assertTrue(queryError is UnsupportedOperationException)
        assertEquals("Provider not ported yet: Aliyun", queryError!!.message)
    }

    @Test
    fun supportsQueryFollowsTheAdapter() {
        val manager = MediaGenerationManager(fakeClient { error("supportsQuery must not hit the network") })
        val video = MediaGenerationModel(modelId = "Wan-AI/Wan2.2-T2V-A14B", kind = MediaKind.VIDEO)
        val image = MediaGenerationModel(modelId = "gpt-image-2", kind = MediaKind.IMAGE)

        // SiliconFlow 视频是任务型接口，可以按任务 ID 续查
        assertTrue(manager.supportsQuery(MediaGenerationProviderSetting.SiliconFlow(), video))
        // OpenAI 图像同步返回结果，没有任务可查
        assertFalse(manager.supportsQuery(MediaGenerationProviderSetting.OpenAI(), image))
        // 未接入的供应商一律不可查
        assertFalse(manager.supportsQuery(MediaGenerationProviderSetting.Aliyun(), video))
    }

    @Test
    fun supportsReconciliationFollowsTheAdapter() {
        val manager = MediaGenerationManager(fakeClient { error("supportsReconciliation must not hit the network") })
        val video = MediaGenerationModel(modelId = "Wan-AI/Wan2.2-T2V-A14B", kind = MediaKind.VIDEO)
        val image = MediaGenerationModel(modelId = "gpt-image-2", kind = MediaKind.IMAGE)

        // SiliconFlow 视频提交时带对账键，中断后可按请求 ID 对账
        assertTrue(manager.supportsReconciliation(MediaGenerationProviderSetting.SiliconFlow(), video))
        // 同步接口与未接入的供应商不参与对账
        assertFalse(manager.supportsReconciliation(MediaGenerationProviderSetting.OpenAI(), image))
        assertFalse(manager.supportsReconciliation(MediaGenerationProviderSetting.Aliyun(), video))
    }

    @Test
    fun unportedProviderFailsReconciliationWithoutHittingTheNetwork() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("unported provider must not hit the network") })
        val setting = MediaGenerationProviderSetting.Aliyun()
        val model = MediaGenerationModel(modelId = "wan3.0-video", kind = MediaKind.VIDEO)

        val error = manager.queryByRequestId(setting, model, "req-1").exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertEquals("Provider not ported yet: Aliyun", error!!.message)
    }

    @Test
    fun queryByRequestIdRoutesToTheAdapter() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(it, """{"requestId":"req-42","status":"InProgress"}""")
        })

        val task = manager.queryByRequestId(
            MediaGenerationProviderSetting.SiliconFlow(),
            MediaGenerationModel(modelId = "Wan-AI/Wan2.2-T2V-A14B", kind = MediaKind.VIDEO),
            "req-42",
        ).getOrThrow()

        assertEquals("req-42", task.id)
        assertEquals(MediaGenerationStatus.RUNNING, task.status)
    }
}
