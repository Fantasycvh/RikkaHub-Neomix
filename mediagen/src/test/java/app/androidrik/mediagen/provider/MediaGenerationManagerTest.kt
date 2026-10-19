package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.providers.fakeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
