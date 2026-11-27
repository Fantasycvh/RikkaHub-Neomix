package app.androidrik.rikkahub.data.media

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.providerTypeDisplayName
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationCapabilities
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.capabilities
import app.androidrik.mediagen.provider.modelCapabilities
import app.androidrik.mediagen.provider.providerTypeDisplayName
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@Serializable
data class MediaModelRef(val id: Uuid, val providerId: Uuid, val modelId: String, val kind: MediaKind)

/** legacy 生图模型（旧 [ProviderSetting]）映射到 mediagen 链路后，授权指纹沿用旧分支格式 */
internal enum class LegacyImageFormat { OpenAI, Google }

internal data class MediaModelOption(
    val target: MediaGenerationTarget.Media,
    val model: MediaGenerationModel,
    val providerId: Uuid,
    val providerName: String,
    val capabilities: MediaGenerationCapabilities,
    val visible: Boolean = true,
    /** 非 null = 由 legacy 生图模型运行期映射而来，指纹按旧分支格式算（存量定时批准不作废）。 */
    val legacyImageFormat: LegacyImageFormat? = null,
) {
    val ref get() = MediaModelRef(model.id, providerId, model.modelId, model.kind)
    val name get() = model.displayName.ifBlank { model.modelId }
    val mediaProvider get() = (target as? MediaGenerationTarget.Media)?.provider

    /**
     * 纯文本模板直跑是否可用：模型没有必填的图片输入（SiliconFlow I2V 必填首帧，
     * 见 [modelCapabilities] 的模型级约束）。
     */
    val supportsTextTemplate get() = capabilities.requiredImageInputs.isEmpty()

    /**
     * 模板直跑按授权的 count 有界执行；模型级 customBodies 覆盖请求骨架键
     * （M3a-2 parity：模型可用 customBodies 覆盖 n/prompt/size）时就拒绝无界的模板直跑。
     */
    val permitsBoundedCount: Boolean get() = model.customBodies.none {
        it.key in BOUNDED_COUNT_OVERRIDE_KEYS
    }

    fun authorizationFingerprint(): String {
        val configuration = when (legacyImageFormat) {
            // 旧分支格式的字段顺序是既有定时授权的指纹来源，改动会使存量批准整体失效——保持逐字节不变。
            // M3b-1 后 legacy 模型映射成 Media target，但指纹仍按旧 Image 分支的格式算：
            // 类型下沉 common 后 MediaGenerationProviderSetting/Model 的 customHeaders/customBodies
            // 与旧 ai 侧是同一类型，编码结果逐字节一致。
            LegacyImageFormat.OpenAI -> {
                val provider = target.provider as MediaGenerationProviderSetting.OpenAI
                listOf(
                    "image",
                    provider.baseUrl,
                    JsonInstant.encodeToString(provider.customHeaders),
                    JsonInstant.encodeToString(model.customHeaders),
                    JsonInstant.encodeToString(model.customBodies),
                )
            }
            LegacyImageFormat.Google -> {
                val provider = target.provider as MediaGenerationProviderSetting.Google
                listOf(
                    "image-google",
                    provider.baseUrl,
                    JsonInstant.encodeToString(provider.customHeaders),
                    JsonInstant.encodeToString(model.customHeaders),
                    JsonInstant.encodeToString(model.customBodies),
                )
            }
            null -> {
                val provider = target.provider
                val destination = listOf(provider.javaClass.name, provider.baseUrl)
                // A linked Google provider can switch endpoints without changing its stored media configuration.
                if (provider is MediaGenerationProviderSetting.Google && provider.credentialRef != null) {
                    destination + when {
                        provider.vertexAI && provider.useServiceAccount ->
                            listOf("vertex-service-account", provider.projectId, provider.location)
                        provider.vertexAI -> listOf("vertex")
                        else -> listOf("ai-studio")
                    }
                } else {
                    destination
                }
            }
        }
        val bytes = JsonInstant.encodeToString(configuration).encodeToByteArray()
        return Base64.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

internal fun Settings.mediaModelCatalog(): List<MediaModelOption> = buildList {
    for (stored in mediaGenerationProviders) {
        // 已接入适配器的媒体供应商才进目录（Aliyun/Volcengine/MiniMax 尚未移植，选了必运行失败）
        if (stored !is MediaGenerationProviderSetting.OpenAI &&
            stored !is MediaGenerationProviderSetting.Google &&
            stored !is MediaGenerationProviderSetting.OpenRouter &&
            stored !is MediaGenerationProviderSetting.SiliconFlow
        ) continue
        // 凭据引用的生效配置：引用被删或引用对象不是凭据型供应商时，这套配置的模型整体失效
        val provider = effectiveMediaProvider(stored) ?: continue
        for (model in provider.models) {
            if (model.modelId.isBlank()) continue
            val capabilities = provider.modelCapabilities(model) ?: continue
            add(MediaModelOption(MediaGenerationTarget.Media(provider, model), model, stored.id,
                provider.name.ifBlank { provider.providerTypeDisplayName }, capabilities))
        }
    }
    for (provider in providers) {
        for (model in provider.models) {
            // 收录口径 = 执行能力（见 [legacyImageExecutable]），不可执行的一律不进目录
            if (model.modelId.isBlank()) continue
            legacyMediaOption(provider, model)?.let { add(it) }
        }
    }
}

/**
 * legacy 生图模型 → mediagen [MediaGenerationTarget.Media] 的运行期映射（M3b-1 目录切换）：
 *
 * 不迁移数据——旧 [ProviderSetting] 的生图模型在目录层包装成 mediagen 快照（借凭据/代理/
 * 多 key/供应商级自定义头），执行统一走 mediagen 适配器；模型 UUID 与供应商 id 原样保留，
 * `MediaModelRef`、默认图片模型、历史记录、定时授权引用全部不受影响；授权指纹按旧分支
 * 格式逐字节复算（见 [MediaModelOption.authorizationFingerprint]）。
 *
 * 收录口径 = [legacyImageExecutable]（与真实执行能力对齐）。
 */
internal fun Settings.legacyMediaOption(provider: ProviderSetting, model: Model): MediaModelOption? {
    val effective = model.findProvider(providers) ?: return null
    if (!legacyImageExecutable(effective, model)) return null
    val presentation = model.toMediaImageModel(id = model.id)
    val (snapshot, format) = when (effective) {
        is ProviderSetting.OpenAI -> MediaGenerationProviderSetting.OpenAI(
            id = effective.id,
            name = effective.name,
            apiKey = effective.apiKey,
            baseUrl = effective.baseUrl,
            proxy = effective.proxy,
            apiKeys = effective.apiKeys.map { it.key }.filter { it.isNotBlank() },
            customHeaders = effective.customHeaders,
            models = listOf(presentation),
        ) to LegacyImageFormat.OpenAI
        is ProviderSetting.Google -> MediaGenerationProviderSetting.Google(
            id = effective.id,
            name = effective.name,
            apiKey = effective.apiKey,
            baseUrl = effective.baseUrl,
            proxy = effective.proxy,
            apiKeys = effective.apiKeys.map { it.key }.filter { it.isNotBlank() },
            customHeaders = effective.customHeaders,
            vertexAI = effective.vertexAI,
            useServiceAccount = effective.useServiceAccount,
            privateKey = effective.privateKey,
            serviceAccountEmail = effective.serviceAccountEmail,
            location = effective.location,
            projectId = effective.projectId,
            models = listOf(presentation),
        ) to LegacyImageFormat.Google
        else -> return null
    }
    val capabilities = snapshot.modelCapabilities(presentation)
        ?: snapshot.capabilities(MediaKind.IMAGE)
        ?: return null
    return MediaModelOption(
        target = MediaGenerationTarget.Media(snapshot, presentation),
        model = presentation,
        providerId = provider.id,
        providerName = provider.name.ifBlank { provider.providerTypeDisplayName },
        capabilities = capabilities,
        visible = provider.enabled && model.enabled,
        legacyImageFormat = format,
    )
}

/**
 * legacy 分支（旧 [ProviderSetting] 生图）的收录口径，与执行层严格对齐
 * （`MediaGenerationExecutor` → `Provider.generateImage` 的真实实现）：
 *
 * - **OpenAI 兼容**：`/images` 端点族（generations/edits），按 IMAGE 类型声明收录。
 * - **Google**：`generateContent` 的 IMAGE 输出模态（[GoogleProvider.generateImage]）。
 *   Gemini 图片模型（gemini-2.5-flash-image 等）通常以 CHAT 类型录入、靠 models.dev
 *   注册表回填 IMAGE 输出模态（`ModelListPage` 添加模型时），所以不能只认类型声明。
 * - **其余类型**（Claude/ChatGPT/Grok 账号）：`generateImage` 是 error 占位（Anthropic
 *   API 没有生图端点），不收录。
 */
internal fun legacyImageExecutable(provider: ProviderSetting, model: Model): Boolean = when (provider) {
    is ProviderSetting.OpenAI -> model.type == ModelType.IMAGE
    is ProviderSetting.Google -> model.type == ModelType.IMAGE ||
        model.outputModalities.contains(Modality.IMAGE)
    else -> false
}

internal fun Model.toMediaImageModel(id: Uuid = Uuid.random()) = MediaGenerationModel(
    modelId = modelId,
    kind = MediaKind.IMAGE,
    displayName = displayName,
    id = id,
    customHeaders = customHeaders,
    customBodies = customBodies,
)

/**
 * 引用旧 AI 供应商凭据时，两处配置共用一份连接和鉴权设置。引用失效后，该媒体供应商从目录消失。
 *
 * M3a-1 起代理与多 key 随引用生效（原 M2 边界「proxy 不随引用生效」因媒体侧补齐代理 parity 而作废）；
 * apiKeys 传入多 key 列表后由适配器的 keyRoulette 统一裁决（旧链路 effectiveApiKeys 同款顺序语义）。
 *
 * 供应商自身 id 不变（[MediaModelRef] 与定时授权里的引用不受影响）；`authorizationFingerprint`
 * 绑定解析后的目标地址，凭据值变化不影响指纹，端点变化才会要求重新批准。
 */
internal fun Settings.effectiveMediaProvider(stored: MediaGenerationProviderSetting): MediaGenerationProviderSetting? {
    val refId = stored.credentialRef ?: return stored
    val source = providers.firstOrNull { it.id == refId } as? ProviderSetting.ApiKey ?: return null
    return stored.withChatProviderCredentials(source)
}

internal fun MediaGenerationProviderSetting.withChatProviderCredentials(
    source: ProviderSetting.ApiKey,
): MediaGenerationProviderSetting? {
    val keys = source.effectiveApiKeys.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    return when {
        this is MediaGenerationProviderSetting.OpenAI && source is ProviderSetting.OpenAI -> copy(
            credentialRef = source.id,
            baseUrl = source.baseUrl,
            apiKey = keys.firstOrNull().orEmpty(),
            proxy = source.proxy,
            apiKeys = keys,
        )
        this is MediaGenerationProviderSetting.Google && source is ProviderSetting.Google -> copy(
            credentialRef = source.id,
            baseUrl = source.baseUrl,
            apiKey = keys.firstOrNull().orEmpty(),
            proxy = source.proxy,
            apiKeys = keys,
            vertexAI = source.vertexAI,
            useServiceAccount = source.useServiceAccount,
            privateKey = source.privateKey,
            serviceAccountEmail = source.serviceAccountEmail,
            location = source.location,
            projectId = source.projectId,
        )
        else -> null
    }
}

/** 模型级 customBodies 命中这些键 = 可能覆盖请求骨架（n/prompt/size 等），模板直跑需重新批准 */
private val BOUNDED_COUNT_OVERRIDE_KEYS = setOf("model", "n", "prompt", "size", "input_references")

internal fun Settings.resolveMediaModel(ref: MediaModelRef): MediaModelOption? =
    mediaModelCatalog().singleOrNull { it.ref == ref }

/**
 * 默认生图模型的解析口径（2026-11-19 真机反馈后定的）：
 *
 * 1. **显式选择优先**：`imageGenerationModelId` 只在生图页选择器里写入，命中即用。
 * 2. **未命中时兜底**：该 id 的默认值是随机 Uuid（[Settings.imageGenerationModelId]），从未在
 *    生图页手动选过就永远命中不了——于是「供应商里明明配好了生图模型」，工具门控却判
 *    「未配置」不装配，助手生图页也显示未配置。兜底 = 目录里第一个**可见**的生图模型
 *    （可见 = 供应商与模型均启用；媒体供应商恒可见），目录顺序即优先级（媒体供应商在前、
 *    旧 AI 供应商在后）。目录为空时仍返回 null，工具照旧不装配。
 */
internal fun Settings.defaultImageModel(): MediaModelOption? {
    val catalog = mediaModelCatalog()
    return catalog.singleOrNull { it.model.id == imageGenerationModelId && it.model.kind == MediaKind.IMAGE }
        ?: catalog.firstOrNull { it.model.kind == MediaKind.IMAGE && it.visible }
}

internal fun Settings.resolveMediaRecord(record: MediaCreationRecord): MediaModelOption? {
    record.catalogModelId?.let { id ->
        resolveMediaModel(MediaModelRef(id, record.providerId, record.modelId, record.kind))?.let { return it }
    }
    val stored = mediaGenerationProviders.firstOrNull { it.id == record.providerId }
    if (stored != null) {
        val provider = effectiveMediaProvider(stored) ?: return null
        val model = provider.models.firstOrNull {
            it.modelId == record.modelId && it.kind == record.kind &&
                (record.catalogModelId == null || it.id == record.catalogModelId)
        } ?: MediaGenerationModel(record.modelId, record.kind, id = record.catalogModelId ?: Uuid.random())
        val capabilities = provider.modelCapabilities(model) ?: return null
        return MediaModelOption(MediaGenerationTarget.Media(provider, model), model, stored.id, provider.name, capabilities)
    }
    // 目录切换前的旧记录：providerId 指向 legacy 供应商，按同款映射解析（模型已被删则如实失败）
    val legacyProvider = providers.firstOrNull { it.id == record.providerId }
    val legacyModel = legacyProvider?.models?.firstOrNull {
        it.modelId == record.modelId && record.kind == MediaKind.IMAGE &&
            (record.catalogModelId == null || it.id == record.catalogModelId)
    }
    return legacyProvider?.let { p -> legacyModel?.let { m -> legacyMediaOption(p, m) } }
}
