package app.androidrik.rikkahub.data.scheduled

import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.mediagen.model.ImageRole
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.uploadDestinationFingerprint
import app.androidrik.rikkahub.data.media.MediaGenerationTarget
import app.androidrik.rikkahub.data.media.defaultImageModel
import app.androidrik.rikkahub.data.media.mediaModelCatalog
import app.androidrik.rikkahub.data.media.resolveMediaModel
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.service.ScheduledRunRegistry
import app.androidrik.rikkahub.service.scheduledRunId
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.workspace.ExecutionScope
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `template assets and operation survive serialization`() {
        val option = requireNotNull(settings.defaultImageModel())
        val grant = ScheduledMediaAction(
            option.ref, MediaCreationParams(count = 1), option.authorizationFingerprint(),
            templatePrompt = "A forest",
            operation = ScheduledMediaOperation.IMAGE_EDIT,
            templateAssets = listOf(ScheduledMediaAsset("scheduled_media/t/a.jpg")),
            uploadFingerprint = "fp",
        )
        assertEquals(grant, JsonInstant.decodeFromString<ScheduledMediaAction>(JsonInstant.encodeToString(grant)))
    }

    @Test
    fun `template operations gate models and assets`() {
        val image = requireNotNull(settings.defaultImageModel())
        val i2v = settings.mediaModelCatalog().single { it.model.id == firstFrameVideo.id }
        val reference = ScheduledMediaAsset("scheduled_media/t/a.jpg")
        val firstFrame = ScheduledMediaAsset("scheduled_media/t/a.jpg", role = ImageRole.FIRST_FRAME)

        fun grant(operation: ScheduledMediaOperation, assets: List<ScheduledMediaAsset>, video: Boolean = false) =
            ScheduledMediaAction(
                (if (video) i2v else image).ref,
                MediaCreationParams(count = if (video) null else 1, resolution = if (video) "1280x720" else null),
                (if (video) i2v else image).authorizationFingerprint(),
                templatePrompt = "A forest",
                operation = operation,
                templateAssets = assets,
            )

        // 编辑操作必须带素材、只能给图片模型
        assertFalse(grant(ScheduledMediaOperation.IMAGE_EDIT, emptyList()).permits(image))
        assertFalse(grant(ScheduledMediaOperation.IMAGE_EDIT, listOf(reference), video = true).permits(i2v))
        assertTrue(grant(ScheduledMediaOperation.IMAGE_EDIT, listOf(reference)).permits(image))

        // 首帧操作必须提供首帧角色的一张素材、只能给视频模型；参考图角色不算
        assertFalse(grant(ScheduledMediaOperation.FIRST_FRAME, emptyList(), video = true).permits(i2v))
        assertFalse(grant(ScheduledMediaOperation.FIRST_FRAME, listOf(reference), video = true).permits(i2v))
        assertFalse(grant(ScheduledMediaOperation.FIRST_FRAME, listOf(firstFrame)).permits(image))
        assertTrue(grant(ScheduledMediaOperation.FIRST_FRAME, listOf(firstFrame), video = true).permits(i2v))

        // TEXT 不接受素材
        assertFalse(grant(ScheduledMediaOperation.TEXT, listOf(reference)).permits(image))
        assertTrue(grant(ScheduledMediaOperation.TEXT, emptyList()).permits(image))
    }

    @Test
    fun `upload destination fingerprint tracks the configuration`() {
        val configured = S3Config(endpoint = "https://s3.test", accessKeyId = "a", secretAccessKey = "b", bucket = "c")
        val fingerprint = configured.uploadDestinationFingerprint()
        assertNotNull(fingerprint)
        // 密钥不参与指纹：换密钥不影响素材还能不能传上去
        assertEquals(fingerprint, configured.copy(secretAccessKey = "x").uploadDestinationFingerprint())
        assertNotEquals(fingerprint, configured.copy(bucket = "other").uploadDestinationFingerprint())
        // 未配置或显式 http（不可用于上传）都没有指纹
        assertNull(S3Config().uploadDestinationFingerprint())
        assertNull(configured.copy(endpoint = "http://s3.test").uploadDestinationFingerprint())
    }

    @Test
    fun `credential reference resolves from the legacy provider`() {
        val source = legacy.copy(models = emptyList())
        val referenced = media.copy(credentialRef = source.id)
        val settingsWithRef = Settings.dummy().copy(providers = listOf(source), mediaGenerationProviders = listOf(referenced))

        // 生效配置取被引用者的 baseUrl/apiKey，模型在目录里可选；媒体供应商自己的 id 保持不变
        val option = requireNotNull(settingsWithRef.mediaModelCatalog().singleOrNull { it.model.id == mediaImage.id })
        assertEquals(referenced.id, option.ref.providerId)
        assertEquals(source.baseUrl, (option.target as MediaGenerationTarget.Media).provider.baseUrl)
    }

    @Test
    fun `credential reference becomes invalid when the legacy provider disappears`() {
        val referenced = media.copy(credentialRef = legacy.id)
        val withRef = Settings.dummy().copy(providers = listOf(legacy), mediaGenerationProviders = listOf(referenced))
        // 引用在时可选
        assertTrue(withRef.mediaModelCatalog().any { it.model.id == mediaImage.id })

        // 被引用者被删：模型整体失效（目录消失 → 运行期按既有「模型不可用」报错）
        val withoutSource = withRef.copy(providers = emptyList())
        assertTrue(withoutSource.mediaModelCatalog().none { it.model.id == mediaImage.id })

        // 被引用者换端点：模型还在，但授权指纹变化，旧授权需要重新批准
        val before = requireNotNull(withRef.mediaModelCatalog().singleOrNull { it.model.id == mediaImage.id })
        val grant = ScheduledMediaAction(before.ref, MediaCreationParams(count = 1), before.authorizationFingerprint())
        val moved = withRef.copy(providers = listOf(legacy.copy(baseUrl = "https://moved.test/v1")))
        val option = requireNotNull(moved.mediaModelCatalog().singleOrNull { it.model.id == mediaImage.id })
        assertFalse(grant.permits(option))
    }

    @Test
    fun `credential reference survives serialization`() {
        val referenced = media.copy(credentialRef = legacy.id)
        assertEquals(
            referenced,
            JsonInstant.decodeFromString<MediaGenerationProviderSetting>(
                JsonInstant.encodeToString<MediaGenerationProviderSetting>(referenced)
            ),
        )
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
