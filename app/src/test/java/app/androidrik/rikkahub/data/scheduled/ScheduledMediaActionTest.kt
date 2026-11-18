package app.androidrik.rikkahub.data.scheduled

import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.media.defaultImageModel
import app.androidrik.rikkahub.data.media.mediaModelCatalog
import app.androidrik.rikkahub.data.media.resolveMediaModel
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.service.ScheduledRunRegistry
import app.androidrik.rikkahub.service.scheduledRunId
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.workspace.ExecutionScope
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledMediaActionTest {
    private val legacyImage = Model(modelId = "image-a", type = ModelType.IMAGE)
    private val mediaImage = MediaGenerationModel("image-b", MediaKind.IMAGE)
    private val firstFrameVideo = MediaGenerationModel("example-I2V", MediaKind.VIDEO)
    private val legacy = ProviderSetting.OpenAI(models = listOf(legacyImage))
    private val media = MediaGenerationProviderSetting.OpenAI(models = listOf(mediaImage))
    private val video = MediaGenerationProviderSetting.SiliconFlow(models = listOf(firstFrameVideo))
    private val settings = Settings.dummy().copy(
        providers = listOf(legacy),
        mediaGenerationProviders = listOf(media, video, MediaGenerationProviderSetting.OpenRouter()),
        imageGenerationModelId = mediaImage.id,
    )

    @Test
    fun `catalog combines the two configurations and resolves the configured default`() {
        assertEquals(setOf(legacyImage.id, mediaImage.id, firstFrameVideo.id),
            settings.mediaModelCatalog().map { it.model.id }.toSet())
        val option = requireNotNull(settings.defaultImageModel())
        assertEquals(media.id, option.ref.providerId)
        assertNull(settings.copy(mediaGenerationProviders = listOf(video)).resolveMediaModel(option.ref))
        assertNull(settings.resolveMediaModel(option.ref.copy(modelId = "replacement")))
    }

    @Test
    fun `authorization survives serialization but requires approval after endpoint changes`() {
        val option = requireNotNull(settings.defaultImageModel())
        val grant = ScheduledMediaAction(option.ref, MediaCreationParams(count = 1),
            option.authorizationFingerprint(), templatePrompt = "A forest")
        assertEquals(grant, JsonInstant.decodeFromString<ScheduledMediaAction>(JsonInstant.encodeToString(grant)))
        assertTrue(grant.permits(option))
        val changed = settings.copy(mediaGenerationProviders = listOf(media.copy(baseUrl = "https://other.test/v1")))
        assertFalse(grant.permits(requireNotNull(changed.resolveMediaModel(option.ref))))
    }

    @Test
    fun `template requires a prompt only model and bounds the image count`() {
        val image = requireNotNull(settings.defaultImageModel())
        val excessive = ScheduledMediaAction(image.ref, MediaCreationParams(count = 5),
            image.authorizationFingerprint(), templatePrompt = "A forest")
        assertFalse(excessive.permits(image))
        val i2v = settings.mediaModelCatalog().single { it.model.id == firstFrameVideo.id }
        val template = ScheduledMediaAction(i2v.ref, MediaCreationParams(resolution = "1280x720"),
            i2v.authorizationFingerprint(), templatePrompt = "A forest")
        assertFalse(template.permits(i2v))
    }

    @Test
    fun `legacy custom request fields cannot override the granted model or count`() {
        val modified = legacyImage.copy(customBodies = listOf(CustomBody("n", JsonPrimitive(20))))
        val option = settings.copy(providers = listOf(legacy.copy(models = listOf(modified))))
            .mediaModelCatalog().single { it.model.id == modified.id }
        val grant = ScheduledMediaAction(option.ref, MediaCreationParams(count = 1), option.authorizationFingerprint())
        assertFalse(grant.permits(option))
    }

    @Test
    fun `agent tool scope consumes the grant once across the run`() {
        val registry = ScheduledRunRegistry()
        val option = requireNotNull(settings.defaultImageModel())
        val grant = ScheduledMediaAction(option.ref, MediaCreationParams(count = 2), option.authorizationFingerprint())
        val scope = ExecutionScope.run("child", ExecutionScope.scheduled("run"))
        val runId = requireNotNull(scope.scheduledRunId())
        registry.register(runId, taskId = "task")
        assertNull(registry.mediaGrant(runId))
        registry.setMediaGrant(runId, grant)
        assertFalse(registry.consumeMediaGrant(runId, grant.copy(params = MediaCreationParams(count = 4))))
        assertTrue(registry.consumeMediaGrant(runId, grant))
        assertFalse(registry.consumeMediaGrant(runId, grant))
        assertNull(registry.mediaGrant(runId))
    }
}
