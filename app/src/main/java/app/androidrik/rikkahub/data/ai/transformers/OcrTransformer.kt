package app.androidrik.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.common.cache.LruCache
import app.androidrik.common.cache.SingleFileCacheStore
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.ai.UsageRecorder
import app.androidrik.rikkahub.data.ai.UsageSource
import app.androidrik.rikkahub.data.ai.UsageTag
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

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
                                    UIMessagePart.Text(performOcr(part))
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

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
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
        val content = result.choices[0].message?.toText() ?: "[ERROR, OCR failed]"
        Log.i(TAG, "performOcr: $content")
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        return ocrResult
    }.getOrElse {
        "[ERROR, OCR failed: $it]"
    }
}
