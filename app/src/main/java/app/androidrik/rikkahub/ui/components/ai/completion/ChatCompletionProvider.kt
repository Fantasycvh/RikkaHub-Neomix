package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange

data class ChatCompletionContext(
    val text: String,
    /** Selection in the complete document; [text] is a bounded window ending at the cursor. */
    val selection: TextRange,
    val textOffset: Int = 0,
) {
    val cursor: Int = selection.max - textOffset
    val hasSelection: Boolean = selection.min != selection.max
    fun documentRange(range: TextRange) = TextRange(range.start + textOffset, range.end + textOffset)
}

internal const val MAX_COMPLETION_CONTEXT = 1024

/** Copy only the cursor's neighborhood, never materialize the complete editor buffer. */
internal fun completionContext(text: CharSequence, selection: TextRange): ChatCompletionContext? {
    if (!selection.collapsed || selection.max !in 0..text.length) return null
    val cursor = selection.max
    var start = (cursor - MAX_COMPLETION_CONTEXT - 1).coerceAtLeast(0)
    if (start > 0 && start < cursor && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
    val window = buildString(cursor - start) {
        for (index in start until cursor) append(text[index])
    }
    return ChatCompletionContext(window, selection, start)
}

enum class ChatCompletionGroup {
    COMMAND,
    SKILL,
}

sealed interface ChatCompletionEffect {
    /** 原地替换文本（@工作区提及、带参命令前缀 "/goal "、定时任务提示词内插 "/skill-name "） */
    data class InsertText(val text: String) : ChatCompletionEffect

    /** 挂载本轮 Skill Chip（可叠加）并抹除当前输入的 /xxx token */
    data class AttachSkill(val skillName: String) : ChatCompletionEffect

    /** 客户端立即执行内置斜杠命令并清理已匹配的命令文本 */
    data class ExecuteCommand(val call: SlashCommandCall) : ChatCompletionEffect
}

data class ChatCompletionList(
    val providerId: String,
    val replacementRange: TextRange,
    val items: List<ChatCompletionItem>,
)

data class ChatCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val sortScore: Int = 0,
    val key: String = "$label:$insertText",
    val group: ChatCompletionGroup? = null,
    val badge: String? = null,
    val effect: ChatCompletionEffect = ChatCompletionEffect.InsertText(insertText),
)

interface ChatCompletionProvider {
    val id: String

    suspend fun complete(context: ChatCompletionContext): ChatCompletionList?
}
