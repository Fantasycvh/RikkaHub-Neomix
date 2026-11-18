package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.providers.fakeClient
import kotlinx.coroutines.runBlocking
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
}
