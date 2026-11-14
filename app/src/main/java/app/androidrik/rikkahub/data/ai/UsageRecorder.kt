package app.androidrik.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.core.merge
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.db.dao.UsageRecordDAO
import app.androidrik.rikkahub.data.db.entity.UsageRecordEntity
import kotlin.uuid.Uuid

private const val TAG = "UsageRecorder"

/**
 * 一次请求是因为什么发起的，按名称存进 `usage_record.source`。
 *
 * 统计页的「来源拆分」直接用它做分组标签，**新增值必须同时在 stats 页给出展示名**
 * （见 `ui/pages/stats/StatsPage.kt` 的来源映射），否则会退化成裸枚举名。
 */
enum class UsageSource {
    /** 聊天主流程（含工具调用的每一步） */
    CHAT,

    /** 无头子代理 run（[app.androidrik.rikkahub.service.SubagentRunner]） */
    SUBAGENT,

    /** 上下文压缩摘要 */
    COMPRESSION,

    /** 会话标题生成 */
    TITLE,

    /** 聊天建议生成 */
    SUGGESTION,

    /** 消息 / 文本翻译 */
    TRANSLATION,

    /** 记忆整理（做梦/复核） */
    MEMORY,

    /** 图片 OCR 转文字 */
    OCR,
}

/**
 * 记录用量时附带的归属信息。
 *
 * [assistantId] / [conversationId] 只用于排查「这条流水是哪来的」，统计页不按它们分组——
 * 助手和会话都可能被删掉，按它们聚合会让历史用量凭空消失。
 */
data class UsageTag(
    val source: UsageSource,
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
)

/**
 * 把每次模型请求的 usage 写进 `usage_record` 流水表。
 *
 * 用法：发起请求的地方把 provider 包一层 —— `usageRecorder.wrap(provider, tag)` ——
 * 每次 `generateText` / `streamText` 结束时记一行。**重试的每次尝试各记各的**：那些请求
 * 都已经发出去并计费了，只记最后一次会低估花费。
 *
 * 写库在 [scope] 里异步进行（Room 的 suspend 写入自己会切后台线程），失败只打日志——
 * 统计绝不能因为写流水失败而把一次成功的对话搞挂。
 */
class UsageRecorder(
    private val dao: UsageRecordDAO,
    private val scope: CoroutineScope,
) {
    /** 包一层 provider，其余能力（embedding / 图片 / 余额）原样透传。 */
    fun <T : ProviderSetting> wrap(provider: Provider<T>, tag: UsageTag): Provider<T> =
        UsageRecordingProvider(provider, tag)

    /**
     * 记一行**按页计费**的 OCR 流水（DeepSeek 适配备料 03 §2.5）。
     *
     * 与 [record] 的关键差异：**绕开零 token 行被丢弃的闸门**——DeepSeek OCR 按
     * 成功页数计费（¥1.5/千页），`usage` 仅供参照甚至缺省，按 token 过滤会把
     * 真实花费整个丢掉。page_count 行与 token 行共用一张表；统计页对 page_count>0
     * 的行按页展示。
     */
    fun recordOcrPages(
        providerSetting: ProviderSetting,
        pageCount: Int,
        usage: TokenUsage? = null,
    ) {
        if (pageCount <= 0) return
        val record = UsageRecordEntity(
            createdAt = System.currentTimeMillis(),
            source = UsageSource.OCR.name,
            providerId = providerSetting.id.toString(),
            providerName = providerSetting.name.takeIf { it.isNotBlank() },
            modelId = null,
            modelName = "DeepSeek OCR",
            assistantId = null,
            conversationId = null,
            promptTokens = usage?.promptTokens ?: 0,
            completionTokens = usage?.completionTokens ?: 0,
            cachedTokens = 0,
            pageCount = pageCount,
        )
        scope.launch {
            runCatching { dao.insert(record) }
                .onFailure { Log.w(TAG, "Failed to record OCR pages", it) }
        }
    }

    private fun record(
        tag: UsageTag,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        usage: TokenUsage,
    ) {
        // 空用量（上游没回 usage，或只有 cache 字段）不记，免得流水里全是 0 行
        if (usage.promptTokens <= 0 && usage.completionTokens <= 0) return
        val model = params.model
        val record = UsageRecordEntity(
            createdAt = System.currentTimeMillis(),
            source = tag.source.name,
            providerId = providerSetting.id.toString(),
            providerName = providerSetting.name.takeIf { it.isNotBlank() },
            modelId = model.id.toString(),
            modelName = model.displayName.ifBlank { model.modelId }.takeIf { it.isNotBlank() },
            assistantId = tag.assistantId?.toString(),
            conversationId = tag.conversationId?.toString(),
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cachedTokens = usage.cachedTokens,
            // DeepSeek 内置工具计费次数（¥5/千次各自）；普通请求全 0
            toolSearchCount = usage.toolSearch,
            toolFetchCount = usage.toolFetch,
            toolImageSearchCount = usage.toolImageSearch,
        )
        scope.launch {
            runCatching { dao.insert(record) }
                .onFailure { Log.w(TAG, "Failed to record usage (${tag.source})", it) }
        }
    }

    private inner class UsageRecordingProvider<T : ProviderSetting>(
        private val delegate: Provider<T>,
        private val tag: UsageTag,
    ) : Provider<T> by delegate {
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = delegate.generateText(providerSetting, messages, params).also { result ->
            result.usage?.let { record(tag, providerSetting, params, it) }
        }

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            val upstream = delegate.streamText(providerSetting, messages, params)
            return flow {
                // 同一次请求里的 usage 是逐步更新的快照（有的供应商只在末块给，有的每块累加），
                // 按 merge 取「最大的那份」。写库放在 finally：中途取消或上游抛错时，
                // 已经产生的用量同样是真实花费，不能丢。
                var usage: TokenUsage? = null
                try {
                    upstream.collect { chunk ->
                        if (chunk is StreamChunk.Usage) usage = usage.merge(chunk.usage)
                        emit(chunk)
                    }
                } finally {
                    usage?.let { record(tag, providerSetting, params, it) }
                }
            }
        }
    }
}
