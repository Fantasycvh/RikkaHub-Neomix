package app.androidrik.rikkahub.utils

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 精确 BPE 计数的**多核分摊**。
 *
 * 计数是纯 CPU、彼此独立的文本级操作（[estimateTokens] 只读不可变词表 + 线程安全缓存），
 * 但一次拼装预览里几十段文本此前是单线程串行算的：长会话上实测占首次打开耗时的 70% 以上。
 * 这里按文本把工作分摊到多个核，结果与串行逐个调用完全一致（同一函数、同一口径）。
 *
 * 并行度取「可用核数」与 [MAX_PARALLELISM] 的较小值：再多只会增加缓存争用和发热，
 * 对总时长没有帮助。文本数少于 2 时直接串行，避免起协程的开销超过收益。
 */
suspend fun countTokensInParallel(texts: List<String>): List<Int> {
    if (texts.size < 2) return texts.map { estimateTokens(it) }
    return withContext(Dispatchers.Default) {
        val workers = minOf(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            MAX_PARALLELISM,
            texts.size,
        )
        if (workers < 2) return@withContext texts.map { estimateTokens(it) }

        val results = IntArray(texts.size)
        coroutineScope {
            // 轮转分配（而不是连续分块）：长文本集中的块不会把总时长拖成一个慢任务
            (0 until workers).map { worker ->
                async {
                    var index = worker
                    while (index < texts.size) {
                        results[index] = estimateTokens(texts[index])
                        index += workers
                    }
                }
            }.awaitAll()
        }
        results.toList()
    }
}

/**
 * 拼装预览会精确计数的**文本单元**——与 [app.androidrik.rikkahub.data.ai.inspectPrompt] 的
 * 分段取法保持一致（消息正文按 `"\n"` 拼接全部 Text part，工具输出另算一段）。
 *
 * 保持这个形状有两个用途：①进会话时后台预热、②预览内并行预取，两者填的缓存键与随后
 * 逐段计数用的键相同，才能真的命中。改这里就等于改预览的计数口径，务必同步。
 */
fun conversationTokenTexts(messages: List<UIMessage>): List<String> = buildList {
    messages.forEach { message ->
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        if (text.isNotEmpty()) add(text)
        message.parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isExecuted }
            .forEach { tool ->
                val output = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                if (output.isNotBlank()) add(output)
            }
    }
}

/** 并行度上限：超过 6 个核后计数已接近内存带宽瓶颈，继续加只会增加抖动。 */
private const val MAX_PARALLELISM = 6
