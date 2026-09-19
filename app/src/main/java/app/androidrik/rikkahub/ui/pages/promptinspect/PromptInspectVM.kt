package app.androidrik.rikkahub.ui.pages.promptinspect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.PromptInspection
import app.androidrik.rikkahub.data.ai.PromptInspectionLabels
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.service.ChatService
import kotlin.uuid.Uuid

data class PromptInspectState(
    val loading: Boolean = true,
    val inspection: PromptInspection? = null,
)

/**
 * 拼装预览 VM：调用 [ChatService.inspectPromptAssembly]（与真实请求同源的只读组装）。
 *
 * **只在打开时算一次**（不做自动刷新）：这是排查/优化用的工具，值不值得重算由用户决定
 * （预览会跑完整 transformer 链，理论上可触发 OCR 等 IO）。
 */
class PromptInspectVM(
    private val context: Context,
    private val chatService: ChatService,
) : ViewModel() {

    private val _state = MutableStateFlow(PromptInspectState())
    val state = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(conversationId: String) {
        // 重组/配置变更时避免重复计算（同 id 只算一次）
        if (loadedId == conversationId && _state.value.inspection != null) return
        loadedId = conversationId

        _state.value = PromptInspectState(loading = true)
        viewModelScope.launch {
            val inspection = runCatching {
                chatService.inspectPromptAssembly(
                    conversationId = Uuid.parse(conversationId),
                    labels = PromptInspectionLabels(
                        system = context.getString(R.string.prompt_inspect_segment_system),
                        checkpoint = context.getString(R.string.prompt_inspect_segment_checkpoint),
                        injection = context.getString(R.string.prompt_inspect_segment_injection),
                        history = context.getString(R.string.prompt_inspect_segment_history),
                        // #1542 Tier B：system 块化的细分来源标签（槽名复用角色卡槽位视图的既有字符串）
                        memory = context.getString(R.string.prompt_inspect_segment_memory),
                        toolSchema = context.getString(R.string.prompt_inspect_segment_tool_schema),
                        renderHint = context.getString(R.string.prompt_inspect_segment_render_hint),
                        cardSlotNames = mapOf(
                            CardSlot.GREETING to context.getString(R.string.assistant_page_card_slot_greeting),
                            CardSlot.SYSTEM_PROMPT to context.getString(R.string.assistant_page_card_system_prompt),
                            CardSlot.DESCRIPTION to context.getString(R.string.assistant_page_card_description),
                            CardSlot.PERSONALITY to context.getString(R.string.assistant_page_card_personality),
                            CardSlot.SCENARIO to context.getString(R.string.assistant_page_card_scenario),
                            CardSlot.EXAMPLE to context.getString(R.string.assistant_page_card_example),
                        ),
                    ),
                )
            }.getOrNull()
            _state.value = PromptInspectState(loading = false, inspection = inspection)
        }
    }
}
