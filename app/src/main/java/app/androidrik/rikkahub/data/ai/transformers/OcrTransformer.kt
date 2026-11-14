package app.androidrik.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.tools.ocr.DeepSeekOcrClient
import app.androidrik.ai.tools.ocr.toPromptText
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.common.cache.LruCache
import app.androidrik.common.cache.SingleFileCacheStore
import app.androidrik.rikkahub.data.datastore.DEEPSEEK_BUILTIN_PROVIDER_ID
import app.androidrik.rikkahub.data.datastore.OcrBackend
import app.androidrik.rikkahub.data.datastore.OcrDeepSeekPurpose
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.ai.UsageRecorder
import app.androidrik.rikkahub.data.ai.UsageSource
import app.androidrik.rikkahub.data.ai.UsageTag
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

internal fun resolveOcrConfiguration(settings: Settings): Pair<Model, ProviderSetting> {
    val model = checkNotNull(settings.findModelById(settings.ocrModelId)) {
        "OCR model is not configured or no longer exists. Select an OCR model in Settings > Models before sending images to a text-only model."
    }
    val provider = checkNotNull(model.findProvider(settings.providers)) {
        "The OCR model's provider no longer exists. Select an OCR model in Settings > Models."
    }
    return model to provider
}

/**
 * 解析 DeepSeek OCR 后端配置（配料 03 §2.2）：Key 共享自内置 DeepSeek 提供商，
 * 也可显式指定其他提供商 id。配置错误**向上传递**到聊天错误卡（与 MODEL 后端同口径），
 * 不能静默降级——静默换后端会改变计费口径。
 */
internal fun resolveDeepSeekOcrConfiguration(settings: Settings): Pair<ProviderSetting, String> {
    val providerId = settings.ocrDeepSeekProviderId ?: DEEPSEEK_BUILTIN_PROVIDER_ID
    val provider = checkNotNull(settings.providers.find { it.id == providerId }) {
        "The DeepSeek OCR provider (id=$providerId) no longer exists. Configure it in Settings > OCR backend."
    }
    // 多 key 感知读取（effectiveApiKeys 换行分隔），取首个非空行
    val apiKey = provider.effectiveApiKeys.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    check(apiKey.isNotBlank()) {
        "The DeepSeek provider has no API key. Set the key in Settings > Providers > DeepSeek before using DeepSeek OCR."
    }
    return provider to apiKey
}

internal fun deepSeekOcrWirePurpose(purpose: OcrDeepSeekPurpose): String = when (purpose) {
    OcrDeepSeekPurpose.TEXT -> "text_recognition"
    OcrDeepSeekPurpose.RICH_LAYOUT -> "rich_layout_recognition"
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    // 按 settings.ocrBackend 分派（配料 03 §2.4 入口 1）：
                                    // MODEL = 原视觉模型逻辑；DEEPSEEK = 文件 OCR Tool API（按页计费）。
                                    // 触发条件保持不变（仅文本模型 + file: 图片），避免和视觉模型重复花钱。
                                    val prompt = when (ctx.settings.ocrBackend) {
                                        OcrBackend.MODEL -> performOcr(part, ctx.settings)
                                        OcrBackend.DEEPSEEK -> performDeepSeekOcr(part, ctx.settings)
                                    }
                                    UIMessagePart.Text(prompt)
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image, settings: Settings): String {
        // 配置错误向上传递到聊天错误卡，不能用占位文本替换图片后继续请求模型。
        val (model, providerSetting) = resolveOcrConfiguration(settings)
        return runCatching {
            // Check cache first
            cache.get(part.url)?.let { cachedResult ->
                Log.i(TAG, "performOcr: Using cached result for ${part.url}")
                return@runCatching cachedResult
            }

            // OCR 也是真实请求：包一层流水（source=OCR），否则这部分花费在统计里完全不可见
            val provider = get<UsageRecorder>().wrap(
                get<ProviderManager>().getProviderByType(providerSetting),
                UsageTag(UsageSource.OCR),
            )
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            val content = result.message.toText().ifBlank { "[ERROR, OCR failed]" }
            Log.i(TAG, "performOcr: $content")
            val ocrResult = """
                <image_file_ocr>
                   $content
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()

            // Cache the result
            cache.put(part.url, ocrResult)
            ocrResult
        }.getOrElse {
            if (it is CancellationException) throw it
            "[ERROR, OCR failed: $it]"
        }
    }

    /**
     * DeepSeek 文件 OCR（图片入口）：整图当 1 页上传，取逐页文本。
     *
     * 与 [performOcr] 的差异：
     * - 计费按页（¥1.5/千页），与 token 无关——成功与否都要记 [UsageRecorder.recordOcrPages]；
     * - 缓存 key 含 purpose/includeLayout/sha256——「只按 URL」会在换配置后错误命中旧结果；
     * - 200-with-error、`finish_reason=length` 由 client 与 [toPromptText] 处理。
     */
    suspend fun performDeepSeekOcr(part: UIMessagePart.Image, settings: Settings): String {
        // 配置错误向上传递（与 MODEL 后端同口径）
        val (providerSetting, apiKey) = resolveDeepSeekOcrConfiguration(settings)
        return runCatching {
            val file = part.url.toUri().toFile()
            val fileBytes = file.readBytes()
            val wirePurpose = deepSeekOcrWirePurpose(settings.ocrDeepSeekPurpose)
            val cacheKey = "deepseek:$wirePurpose:${settings.ocrDeepSeekIncludeLayout}:${sha256(fileBytes)}"

            cache.get(cacheKey)?.let { cachedResult ->
                Log.i(TAG, "performDeepSeekOcr: Using cached result for ${file.name}")
                return@runCatching cachedResult
            }

            val result = get<DeepSeekOcrClient>().recognize(
                baseUrl = settings.ocrDeepSeekBaseUrl,
                apiKey = apiKey,
                fileBytes = fileBytes,
                fileName = file.name.ifBlank { "image.png" },
                purpose = wirePurpose,
            )
            // 按页计费：即使 usage 缺省也要落账（成功 page_count 才是计费依据）
            get<UsageRecorder>().recordOcrPages(
                providerSetting = providerSetting,
                pageCount = result.pageCount,
                usage = result.usage?.let {
                    TokenUsage(
                        promptTokens = it.inputTokens,
                        completionTokens = it.outputTokens,
                        totalTokens = it.totalTokens,
                    )
                },
            )

            val content = toPromptText(result.pages, includeLayout = settings.ocrDeepSeekIncludeLayout)
                .ifBlank { "[ERROR, OCR failed: no text returned]" }
            Log.i(TAG, "performDeepSeekOcr: pageCount=${result.pageCount}")

            val ocrResult = """
                <image_file_ocr>
                   $content
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()

            cache.put(cacheKey, ocrResult)
            ocrResult
        }.getOrElse {
            if (it is CancellationException) throw it
            "[ERROR, OCR failed: $it]"
        }
    }
}
