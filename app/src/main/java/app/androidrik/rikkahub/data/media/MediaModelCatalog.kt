package app.androidrik.rikkahub.data.media

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationCapabilities
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.capabilities
import app.androidrik.mediagen.provider.modelCapabilities
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

internal data class MediaModelOption(
    val target: MediaGenerationTarget,
    val model: MediaGenerationModel,
    val providerId: Uuid,
    val providerName: String,
    val capabilities: MediaGenerationCapabilities,
    val visible: Boolean = true,
) {
    val ref get() = MediaModelRef(model.id, providerId, model.modelId, model.kind)
    val name get() = model.displayName.ifBlank { model.modelId }
    val mediaProvider get() = (target as? MediaGenerationTarget.Media)?.provider

    /**
     * 纯文本模板直跑是否可用：模型没有必填的图片输入（SiliconFlow I2V 必填首帧，
     * 见 [modelCapabilities] 的模型级约束），其余都行。
     */
    val supportsTextTemplate get() = when (target) {
        is MediaGenerationTarget.Image -> true
        is MediaGenerationTarget.Media -> capabilities.requiredImageInputs.isEmpty()
    }

    val permitsBoundedCount: Boolean get() = when (target) {
        is MediaGenerationTarget.Image -> target.model.customBodies.none {
            it.key in setOf("model", "n", "prompt", "size", "input_references")
        }
        is MediaGenerationTarget.Media -> true
    }

    fun authorizationFingerprint(): String {
        val configuration = when (target) {
            is MediaGenerationTarget.Image -> when (val provider = target.provider) {
                // OpenAI 分支的字段顺序是既有定时授权的指纹来源，改动会使存量批准整体失效——保持逐字节不变
                is ProviderSetting.OpenAI -> listOf(
                    "image",
                    provider.baseUrl,
                    JsonInstant.encodeToString(provider.customHeaders),
                    JsonInstant.encodeToString(target.model.customHeaders),
                    JsonInstant.encodeToString(target.model.customBodies),
                )
                is ProviderSetting.Google -> listOf(
                    "image-google",
                    provider.baseUrl,
                    JsonInstant.encodeToString(provider.customHeaders),
                    JsonInstant.encodeToString(target.model.customHeaders),
                    JsonInstant.encodeToString(target.model.customBodies),
                )
                else -> error("Unsupported legacy image provider: ${target.provider::class.simpleName}")
            }
            is MediaGenerationTarget.Media -> listOf(target.provider.javaClass.name, target.provider.baseUrl)
        }
        val bytes = JsonInstant.encodeToString(configuration).encodeToByteArray()
        return Base64.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

internal fun Settings.mediaModelCatalog(): List<MediaModelOption> = buildList {
    for (stored in mediaGenerationProviders) {
        if (stored !is MediaGenerationProviderSetting.OpenAI &&
            stored !is MediaGenerationProviderSetting.SiliconFlow
        ) continue
        // 凭据引用的生效配置：引用被删或引用对象不是凭据型供应商时，这套配置的模型整体失效
        val provider = effectiveMediaProvider(stored) ?: continue
        for (model in provider.models) {
            if (model.modelId.isBlank()) continue
            val capabilities = provider.modelCapabilities(model) ?: continue
            add(MediaModelOption(MediaGenerationTarget.Media(provider, model), model, stored.id,
                provider.name.ifBlank { provider.javaClass.simpleName }, capabilities))
        }
    }
    for (provider in providers) {
        for (model in provider.models) {
            val effective = model.findProvider(providers) ?: continue
            // 收录口径 = 执行能力（见 [legacyImageExecutable]），不可执行的一律不进目录：
            // 收录即「能选但一跑就报错」——工具门控的定式是宁可没有工具。
            if (!legacyImageExecutable(effective, model)) continue
            if (model.modelId.isBlank()) continue
            val presentation = MediaGenerationModel(model.modelId, MediaKind.IMAGE, model.displayName, model.id)
            add(MediaModelOption(MediaGenerationTarget.Image(effective, model), presentation, provider.id,
                provider.name.ifBlank { "OpenAI" }, legacyImageCapabilities,
                visible = provider.enabled && model.enabled))
        }
    }
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
private fun legacyImageExecutable(provider: ProviderSetting, model: Model): Boolean = when (provider) {
    is ProviderSetting.OpenAI -> model.type == ModelType.IMAGE
    is ProviderSetting.Google -> model.type == ModelType.IMAGE ||
        model.outputModalities.contains(Modality.IMAGE)
    else -> false
}

/**
 * 媒体供应商的生效配置：引用了旧 AI 供应商凭据（[MediaGenerationProviderSetting.OpenAI.credentialRef]）
 * 时，baseUrl/apiKey/proxy/apiKeys 取自被引用者，两处配置共用一份凭据。被引用者被删、或引用对象
 * 不是凭据型的 OpenAI 兼容供应商时返回 null——这套配置的模型从目录消失，运行期按既有「模型不可用」
 * 路径报错。
 *
 * M3a-1 起代理与多 key 随引用生效（原 M2 边界「proxy 不随引用生效」因媒体侧补齐代理 parity 而作废）；
 * apiKeys 传入多 key 列表后由适配器的 keyRoulette 统一裁决（旧链路 effectiveApiKeys 同款顺序语义）。
 *
 * 供应商自身 id 不变（[MediaModelRef] 与定时授权里的引用不受影响）；`authorizationFingerprint`
 * 只看解析后的 baseUrl，凭据值变化不影响指纹，端点变化才会要求重新批准。
 */
internal fun Settings.effectiveMediaProvider(stored: MediaGenerationProviderSetting): MediaGenerationProviderSetting? {
    if (stored !is MediaGenerationProviderSetting.OpenAI) return stored
    val refId = stored.credentialRef ?: return stored
    val source = providers.firstOrNull { it.id == refId } as? ProviderSetting.OpenAI ?: return null
    return stored.copyProvider(
        baseUrl = source.baseUrl,
        apiKey = source.effectiveApiKeys.split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() }.orEmpty(),
        proxy = source.proxy,
        apiKeys = source.apiKeys.map { it.key }.filter { it.isNotBlank() },
    )
}

private val legacyImageCapabilities =
    requireNotNull(MediaGenerationProviderSetting.OpenAI().capabilities(MediaKind.IMAGE))

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
    val stored = mediaGenerationProviders.firstOrNull { it.id == record.providerId } ?: return null
    val provider = effectiveMediaProvider(stored) ?: return null
    val model = provider.models.firstOrNull {
        it.modelId == record.modelId && it.kind == record.kind &&
            (record.catalogModelId == null || it.id == record.catalogModelId)
    } ?: MediaGenerationModel(record.modelId, record.kind, id = record.catalogModelId ?: Uuid.random())
    val capabilities = provider.modelCapabilities(model) ?: return null
    return MediaModelOption(MediaGenerationTarget.Media(provider, model), model, stored.id, provider.name, capabilities)
}
