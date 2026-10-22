package app.androidrik.rikkahub.ui.pages.promptinspect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.ai.PromptInspection
import app.androidrik.rikkahub.service.ChatService
import kotlin.uuid.Uuid

data class PromptInspectState(
    val loading: Boolean = true,
    val inspection: PromptInspection? = null,
    /** 首帧骨架用的消息条数（不等组建立即可显示，③ 渐进式渲染的极早期形态） */
    val headerMessageCount: Int? = null,
)

/**
 * 拼装预览 VM：调用 [ChatService.inspectPromptAssembly]（与真实请求同源的只读组装）。
 *
 * **只在打开时算一次**（不做自动刷新）：这是排查/优化用的工具，值不值得重算由用户决定
 * （预览会跑完整 transformer 链，理论上可触发 OCR 等 IO）。
 *
 * **线程**：重活（完整组装 + 逐段 BPE 分词）由 [ChatService.inspectPromptAssembly] 在入口
 * 统一切到后台，这里刻意保持 `viewModelScope`（Main）——await 返回后仍在主线程写
 * [MutableStateFlow]，不会从后台线程碰 UI 状态，也不需要调用方再套一层 `withContext`。
 */
class PromptInspectVM(
    private val context: Context,
    private val chatService: ChatService,
) : ViewModel() {

    private val _state = MutableStateFlow(PromptInspectState())
    val state = _state.asStateFlow()

    private var loadedId: String? = null
    private var loadJob: Job? = null

    fun load(conversationId: String) {
        // 重组/配置变更时避免重复计算（同 id 只算一次）
        if (loadedId == conversationId && (_state.value.inspection != null || loadJob?.isActive == true)) return
        loadJob?.cancel()
        loadedId = conversationId

        _state.value = PromptInspectState(
            loading = true,
            headerMessageCount = runCatching {
                chatService.promptInspectMessageCount(Uuid.parse(conversationId))
            }.getOrNull(),
        )
        loadJob = viewModelScope.launch {
            // 渐进式：先拿到近似计数的一版（首屏立即有内容），精确值算完后再覆盖
            var latest: PromptInspection? = null
            val inspection = try {
                chatService.inspectPromptAssembly(
                    conversationId = Uuid.parse(conversationId),
                    labels = buildPromptInspectionLabels(context),
                    caller = "preview",
                    onPartial = { partial ->
                        latest = partial
                        _state.value = PromptInspectState(loading = false, inspection = partial)
                    },
                )
            } catch (cancelled: CancellationException) {
                // 页面关闭/重组取消时不要吞掉取消信号（预览会在后台跑长文分词）
                throw cancelled
            } catch (_: Exception) {
                null
            }
            _state.value = PromptInspectState(loading = false, inspection = inspection ?: latest)
        }
    }
}
