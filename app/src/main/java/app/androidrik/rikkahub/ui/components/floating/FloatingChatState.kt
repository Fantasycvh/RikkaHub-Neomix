package app.androidrik.rikkahub.ui.components.floating

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlin.uuid.Uuid

/** UI reference ends on disposal; the service retains its independent generation job. */
internal suspend fun holdFloatingChatSession(
    acquire: () -> Unit,
    initialize: suspend () -> Unit,
    onReady: () -> Unit,
    onFailure: (Throwable) -> Unit,
    release: () -> Unit,
): Nothing {
    acquire()
    try {
        try {
            initialize()
            onReady()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
        }
        awaitCancellation()
    } finally {
        release()
    }
}

internal fun floatingChatConversationId(raw: String?): Uuid? =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

internal fun List<UIMessage>.floatingChatHasPendingApproval(): Boolean = any { message ->
    message.parts.any { it is UIMessagePart.Tool && it.isPending }
}

internal data class FloatingChatActions(
    val send: Boolean,
    val stop: Boolean,
    val continueReply: Boolean,
    val regenerate: Boolean,
)

/** Stop also rejects pending tools in ChatService, so it must be disabled during approval. */
internal fun floatingChatActions(
    ready: Boolean,
    configured: Boolean,
    generating: Boolean,
    stopping: Boolean,
    draft: String,
    messages: List<UIMessage>,
): FloatingChatActions {
    val available = ready && configured && !stopping && !messages.floatingChatHasPendingApproval()
    return FloatingChatActions(
        send = available && draft.isNotBlank(),
        stop = ready && generating && !stopping && !messages.floatingChatHasPendingApproval(),
        continueReply = available && !generating && messages.lastOrNull()?.role == MessageRole.ASSISTANT,
        regenerate = available && !generating && floatingChatRegenerateTarget(messages) != null,
    )
}

internal fun floatingChatPartText(part: UIMessagePart): String = when (part) {
    is UIMessagePart.Text -> part.text
    is UIMessagePart.Reasoning -> "思考：${part.reasoning}"
    is UIMessagePart.Tool -> buildString {
        append("工具：${part.toolName}")
        if (part.isPending) append("（等待审批，请到主界面处理）")
        if (part.input.isNotBlank()) append("\n${part.input}")
        part.output.forEach { append("\n${floatingChatPartText(it)}") }
    }
    is UIMessagePart.ServerTool -> "内置工具：${part.toolName}（${part.status}）"
    is UIMessagePart.Document -> "附件：${part.fileName}（请到主界面查看）"
    is UIMessagePart.Image -> "[图片，请到主界面查看]"
    is UIMessagePart.Video -> "[视频，请到主界面查看]"
    is UIMessagePart.Audio -> "[音频，请到主界面查看]"
    else -> "[请到主界面查看此内容]"
}

internal fun UIMessage.floatingChatHasAttachment(): Boolean = parts.any {
    it is UIMessagePart.Image || it is UIMessagePart.Video ||
        it is UIMessagePart.Audio || it is UIMessagePart.Document
}

internal fun floatingChatRegenerateTarget(messages: List<UIMessage>): UIMessage? =
    messages.lastOrNull { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }

internal fun floatingChatErrorVisible(errorConversationId: Uuid?, conversationId: Uuid): Boolean =
    errorConversationId == null || errorConversationId == conversationId

internal fun floatingChatInitialId(explicit: String?, last: String?, recent: Uuid?): Uuid =
    floatingChatConversationId(explicit) ?: floatingChatConversationId(last) ?: recent ?: Uuid.random()
