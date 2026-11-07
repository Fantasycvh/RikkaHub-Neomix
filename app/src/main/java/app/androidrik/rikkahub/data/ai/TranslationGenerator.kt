package app.androidrik.rikkahub.data.ai

import android.content.Context
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 翻译输入最大字符数：超长翻译文本曾 API 413 */
private const val MAX_TRANSLATE_CHARS = 200_000

/**
 * 翻译生成（批次 10 从 [GenerationHandler] 拆出）：划词翻译弹窗 / 翻译页的独立叶子功能，
 * 不经提示词装配主链（不走 generateInternal），唯一外部调用方是 ChatService。
 *
 * 普通模型走「翻译提示词 + 流式」；Qwen-MT 系走专用 translation_options 参数（非流式）。
 * 两条路都过 [withGenerationRetry] 与用量流水（统计页可见「翻译花了多少」）。
 */
internal class TranslationGenerator(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        // 翻译模型未配置（落值 UUID 解析不到）时回落到聊天模型（Miffan 同款兜底），
        // 否则划词弹窗/翻译页在只配了聊天模型的用户那里直接报错
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: settings.providers.findModelById(settings.chatModelId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        // 翻译同样走 provider 出口：包一层流水记录，统计页才能看到「翻译花了多少」
        val providerHandler = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(UsageSource.TRANSLATION),
        )

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText.take(MAX_TRANSLATE_CHARS),
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            withGenerationRetry(context, provider, settings, null) { provider ->
                val snapshot = messages
                // 重试会从头重放，处理器必须按尝试重建（保留跨尝试状态会让 open part 错位）
                val chunkHandler = StreamChunkHandler(model = model)
                try {
                    providerHandler.streamText(
                        providerSetting = provider,
                        messages = messages,
                        params = TextGenerationParams(
                            model = model,
                            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        ),
                    ).collect { chunk ->
                        messages = chunkHandler.handle(messages, chunk)
                        translatedText = messages.lastOrNull()?.toText() ?: ""

                        if (translatedText.isNotBlank()) {
                            onStreamUpdate?.invoke(translatedText)
                            emit(translatedText)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    messages = snapshot
                    throw e
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = withGenerationRetry(context, provider, settings, null) { provider ->
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                        topP = 0.95f,
                        customBody = listOf(
                            CustomBody(
                                key = "translation_options",
                                value = buildJsonObject {
                                    put("source_lang", JsonPrimitive("auto"))
                                    put(
                                        "target_lang",
                                        JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                    )
                                }
                            )
                        )
                    ),
                )
            }
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
