package app.androidrik.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import app.androidrik.rikkahub.data.ai.withProviderErrorHint
import kotlin.uuid.Uuid

/**
 * 聊天错误卡的状态与上报（批次 8 从 [ChatService] 拆出）：持有错误列表、
 * 追加/去重/清除，供 ChatVM 的错误卡列表消费。
 */
internal class ChatErrorReporter {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        // 线路侧拒绝（如中转重建 Gemini contents 丢 tool 消息）补一条可执行提示；
        // 判据见 ProviderErrorText 的 KDoc（真机 18/18 配平仍被拒）
        val shown = error.withProviderErrorHint()
        _errors.update {
            it + ChatError(title = title, error = shown, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    /**
     * 「配置缺失」类提示的**去重**上报（#1975）。
     *
     * 为什么必须去重：标题与建议生成在**每轮生成后**都会尝试一次，模型没配好时不去重就会
     * 每轮弹一张错误卡把用户刷屏（真问题反而被淹没）。同一会话内同一 [solution] 只报一次；
     * 用户点掉后（列表里已无该条）下次仍会重新提醒。
     *
     * 顺带解决「标题与建议同时失败」的重复：两者共用同一个 [ChatErrorSolution]，
     * 故一轮对话最多出一张卡。
     */
    fun addConfigErrorOnce(
        conversationId: Uuid,
        error: Throwable,
        title: String,
        solution: ChatErrorSolution,
    ) {
        if (!shouldReportConfigError(_errors.value, conversationId, solution)) return
        addError(error = error, conversationId = conversationId, title = title, solution = solution)
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }
}
