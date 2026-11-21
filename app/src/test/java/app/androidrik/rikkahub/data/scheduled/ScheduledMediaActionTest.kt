package app.androidrik.rikkahub.data.scheduled

import app.androidrik.common.http.CustomBody
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.Modality
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
import app.androidrik.rikkahub.data.media.resolveMediaRecord
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.service.ScheduledRunRegistry
import app.androidrik.rikkahub.service.scheduledRunId
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.workspace.ExecutionScope
import kotlin.uuid.Uuid
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
    private val openRouter = MediaGenerationProviderSetting.OpenRouter()
    private val settings = Settings.dummy().copy(
        providers = listOf(legacy),
        mediaGenerationProviders = listOf(media, video, openRouter),
        imageGenerationModelId = mediaImage.id,
    )

    @Test
    fun `catalog combines the two configurations and resolves the configured default`() {
        // M3a-3 后 OpenRouter 适配器已接入，其默认模型也进目录
        val openRouterDefaults = openRouter.models.map { it.id }.toSet()
        assertEquals(setOf(legacyImage.id, mediaImage.id, firstFrameVideo.id) + openRouterDefaults,
            settings.mediaModelCatalog().map { it.model.id }.toSet())
        val option = requireNotNull(settings.defaultImageModel())
        assertEquals(media.id, option.ref.providerId)
        assertNull(settings.copy(mediaGenerationProviders = listOf(video)).resolveMediaModel(option.ref))
        assertNull(settings.resolveMediaModel(option.ref.copy(modelId = "replacement")))
    }

    @Test
    fun `legacy models map to media targets and keep the old fingerprint format`() {
        // M3b-1 目录切换：legacy IMAGE 模型在目录层映射成 Media target（执行走 mediagen 适配器），
        // 但 MediaModelRef/供应商 id/模型 id 原样保留，授权指纹按旧 Image 分支格式逐字节复算
        val option = settings.mediaModelCatalog().single { it.model.id == legacyImage.id }
        assertTrue(option.target.provider is MediaGenerationProviderSetting.OpenAI)
        assertEquals(legacyImage.id, option.model.id)
        assertEquals(legacy.id, option.ref.providerId)
        // 映射快照借走旧供应商的凭据/头/多 key（执行语义对齐旧链路）
        val snapshot = option.target.provider as MediaGenerationProviderSetting.OpenAI
        assertEquals(legacy.baseUrl, snapshot.baseUrl)
        assertEquals(legacy.customHeaders, snapshot.customHeaders)

        // 指纹逐字节兼容：手工构造旧 Image 分支格式的配置列表，与映射后的指纹比对
        val legacyFormatConfiguration = listOf(
            "image",
            legacy.baseUrl,
            app.androidrik.rikkahub.utils.JsonInstant.encodeToString(legacy.customHeaders),
            app.androidrik.rikkahub.utils.JsonInstant.encodeToString(legacyImage.customHeaders),
            app.androidrik.rikkahub.utils.JsonInstant.encodeToString(legacyImage.customBodies),
        )
        val legacyFingerprint = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(app.androidrik.rikkahub.utils.JsonInstant.encodeToString(legacyFormatConfiguration).encodeToByteArray()),
        )
        assertEquals(legacyFingerprint, option.authorizationFingerprint())

        // 既有授权继续有效：按映射后的 option 授权，旧指纹验证通过
        val grant = ScheduledMediaAction(option.ref, MediaCreationParams(count = 1), option.authorizationFingerprint())
        assertTrue(grant.permits(option))
        // 端点变化仍要求重新批准
        val moved = settings.copy(providers = listOf(legacy.copy(baseUrl = "https://other.test/v1")))
        assertFalse(grant.permits(requireNotNull(
            moved.mediaModelCatalog().single { it.model.id == legacyImage.id }
        )))
    }

    @Test
    fun `resolveMediaRecord falls back to legacy mapping for pre-migration records`() {
        val option = settings.mediaModelCatalog().single { it.model.id == legacyImage.id }
        val record = app.androidrik.rikkahub.data.model.MediaCreationRecord(
            sessionId = Uuid.random(),
            providerId = legacy.id,
            providerName = "legacy",
            modelId = "image-a",
            kind = MediaKind.IMAGE,
            prompt = "p",
        )
        val resolved = settings.resolveMediaRecord(record)
        assertEquals(option.ref, requireNotNull(resolved).ref)
    }

    @Test
    fun `unset default id falls back to the first visible image model`() {
        // imageGenerationModelId 保持 dummy 默认的随机 Uuid（从未在生图页手动选过）：
        // 目录里有可用生图模型时必须兜底解析出来，否则工具门控会误判「未配置」。
        val fallback = Settings.dummy().copy(
            providers = listOf(legacy),
            mediaGenerationProviders = listOf(video, media),
        ).defaultImageModel()
        assertEquals(media.id, requireNotNull(fallback).ref.providerId)
    }

    @Test
    fun `explicit default selection wins over the fallback order`() {
        // 显式选了旧供应商的模型时，即使媒体供应商在目录里排前面也要尊重选择。
        val explicit = Settings.dummy().copy(
            providers = listOf(legacy),
            mediaGenerationProviders = listOf(media),
            imageGenerationModelId = legacyImage.id,
        ).defaultImageModel()
        assertEquals(legacy.id, requireNotNull(explicit).ref.providerId)
    }

    @Test
    fun `fallback skips disabled providers and models`() {
        val disabledProvider = Settings.dummy().copy(
            providers = listOf(legacy.copy(enabled = false)),
        ).defaultImageModel()
        assertNull(disabledProvider)
        val disabledModel = Settings.dummy().copy(
            providers = listOf(legacy.copy(models = listOf(legacyImage.copy(enabled = false)))),
        ).defaultImageModel()
        assertNull(disabledModel)
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
    fun `media models with skeleton-overriding custom bodies need re-approval`() {
        // M3a-2 parity：模型级 customBodies 命中骨架键（n）时，Media 分支同样拒绝无界模板直跑
        val mediaCustom = media.copy(models = listOf(
            MediaGenerationModel("image-b", MediaKind.IMAGE, customBodies = listOf(CustomBody("n", JsonPrimitive(20)))),
        ))
        val option = settings.copy(mediaGenerationProviders = listOf(mediaCustom))
            .mediaModelCatalog().single { it.model.modelId == "image-b" }
        val grant = ScheduledMediaAction(option.ref, MediaCreationParams(count = 1), option.authorizationFingerprint())
        assertFalse(grant.permits(option))
        // 非骨架键不触发
        val benign = media.copy(models = listOf(
            MediaGenerationModel("image-b", MediaKind.IMAGE, customBodies = listOf(CustomBody("negative_prompt", JsonPrimitive("blur")))),
        ))
        val benignOption = settings.copy(mediaGenerationProviders = listOf(benign))
            .mediaModelCatalog().single { it.model.modelId == "image-b" }
        assertTrue(ScheduledMediaAction(
            benignOption.ref, MediaCreationParams(count = 1), benignOption.authorizationFingerprint(),
        ).permits(benignOption))
    }

    @Test
    fun `google image-output models enter the catalog by output modality`() {
        // Gemini 图片模型通常以 CHAT 类型录入（models.dev 注册表回填 IMAGE 输出模态）——
        // 收录口径是执行能力（generateContent IMAGE 模态），不是类型声明
        val gemini = Model(
            modelId = "gemini-2.5-flash-image",
            outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val google = ProviderSetting.Google(models = listOf(gemini))
        val catalog = Settings.dummy().copy(providers = listOf(google)).mediaModelCatalog()
        val option = catalog.single { it.model.id == gemini.id }
        assertEquals(google.id, option.ref.providerId)
        assertEquals(google.id, requireNotNull(
            Settings.dummy().copy(providers = listOf(google)).defaultImageModel()
        ).ref.providerId)
        // 显式 IMAGE 类型声明同样收录（目录拉取不出模态时用户手动标类型）
        val typed = Settings.dummy().copy(providers = listOf(
            google.copy(models = listOf(Model(modelId = "gemini-image", type = ModelType.IMAGE)))
        )).mediaModelCatalog()
        assertEquals(1, typed.size)
    }

    @Test
    fun `non-executable image models stay out of the catalog`() {
        // Claude 的 generateImage 是 error 占位（Anthropic 无生图端点）；Google 纯文本模型
        // 没有 IMAGE 输出模态也不收录——收录即「能选但一跑就报错」
        val dead = Settings.dummy().copy(providers = listOf(
            ProviderSetting.Claude(models = listOf(legacyImage)),
            ProviderSetting.Google(models = listOf(Model(modelId = "gemini-2.5-flash"))),
        )).mediaModelCatalog()
        assertTrue(dead.isEmpty())
        assertNull(Settings.dummy().copy(providers = listOf(
            ProviderSetting.Claude(models = listOf(legacyImage)),
        )).defaultImageModel())
    }

    @Test
    fun `google fingerprint tracks endpoint and differs from the openai branch`() {
        val gemini = Model(modelId = "gemini-2.5-flash-image",
            outputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val google = ProviderSetting.Google(models = listOf(gemini))
        val option = requireNotNull(
            Settings.dummy().copy(providers = listOf(google)).defaultImageModel()
        )
        val grant = ScheduledMediaAction(option.ref, MediaCreationParams(count = 1), option.authorizationFingerprint())
        assertTrue(grant.permits(option))
        // 端点变化 → 重新批准（与 OpenAI 分支同一语义）
        val moved = Settings.dummy().copy(providers = listOf(google.copy(baseUrl = "https://other.test")))
        assertFalse(grant.permits(requireNotNull(moved.defaultImageModel())))
        // 同配置不同分支类型指纹不同，不能互相顶替
        val openaiOption = Settings.dummy().copy(providers = listOf(legacy))
            .mediaModelCatalog().single { it.model.id == legacyImage.id }
        assertNotEquals(option.authorizationFingerprint(), openaiOption.authorizationFingerprint())
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
