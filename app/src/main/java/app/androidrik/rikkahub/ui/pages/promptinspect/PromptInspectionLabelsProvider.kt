package app.androidrik.rikkahub.ui.pages.promptinspect

import android.content.Context
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.PromptInspectionLabels
import app.androidrik.rikkahub.data.model.CardSlot

/**
 * 分段标签的**唯一构造点**（拼装预览页与上下文容量面板共用）。
 *
 * 抽出来的理由：这是 20 行纯文案映射，两处各写一份早晚漂移（新增一种分段只改了其中一处，
 * 另一个界面就会显示成空标签或旧标签），而构造成本可忽略。
 */
fun buildPromptInspectionLabels(context: Context): PromptInspectionLabels = PromptInspectionLabels(
    system = context.getString(R.string.prompt_inspect_segment_system),
    checkpoint = context.getString(R.string.prompt_inspect_segment_checkpoint),
    injection = context.getString(R.string.prompt_inspect_segment_injection),
    history = context.getString(R.string.prompt_inspect_segment_history),
    // #1542 Tier B：system 块化的细分来源标签（槽名复用角色卡槽位视图的既有字符串）
    memory = context.getString(R.string.prompt_inspect_segment_memory),
    toolGuidance = context.getString(R.string.prompt_inspect_segment_tool_guidance),
    toolSchema = context.getString(R.string.prompt_inspect_segment_tool_schema),
    toolResult = context.getString(R.string.prompt_inspect_segment_tool_result),
    renderHint = context.getString(R.string.prompt_inspect_segment_render_hint),
    attachmentNote = context.getString(R.string.prompt_inspect_attachment_note),
    cardSlotNames = mapOf(
        CardSlot.GREETING to context.getString(R.string.assistant_page_card_slot_greeting),
        CardSlot.SYSTEM_PROMPT to context.getString(R.string.assistant_page_card_system_prompt),
        CardSlot.DESCRIPTION to context.getString(R.string.assistant_page_card_description),
        CardSlot.PERSONALITY to context.getString(R.string.assistant_page_card_personality),
        CardSlot.SCENARIO to context.getString(R.string.assistant_page_card_scenario),
        CardSlot.EXAMPLE to context.getString(R.string.assistant_page_card_example),
    ),
)
