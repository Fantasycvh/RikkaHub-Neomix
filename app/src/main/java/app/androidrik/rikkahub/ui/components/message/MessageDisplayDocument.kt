package app.androidrik.rikkahub.ui.components.message

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Stable message identity plus original part index. Display offsets never identify an edit target. */
internal data class MessageTextDocumentId(val messageId: Uuid, val partIndex: Int)

/** Display-only, not serialized. Raw parts remain the authority for copy, export and edit. */
internal data class MessageDisplayDocument(
    val messageId: Uuid,
    val sourceParts: List<UIMessagePart>,
    val header: Chrome,
    val body: List<Part>,
    val footer: Chrome,
) {
    data class Chrome(val key: String)
    data class Part(
        val key: String,
        val sourcePartIndices: List<Int>,
        val block: MessagePartBlock,
        val textDocumentId: MessageTextDocumentId?,
    )
}

/** Reuses the existing folding/grouping policy; no second interpretation of message order. */
internal fun messageDisplayDocument(
    messageId: Uuid,
    parts: List<UIMessagePart>,
    role: MessageRole,
    wholeTurnFolding: Boolean,
): MessageDisplayDocument {
    var cursor = 0
    val body = parts.groupMessageParts(role, wholeTurnFolding).map { block ->
        val indices = when (block) {
            is MessagePartBlock.ContentBlock -> listOf(block.index).also { cursor = block.index + 1 }
            is MessagePartBlock.ChartBlock -> listOf(block.index).also { cursor = block.index + 1 }
            is MessagePartBlock.ThinkingBlock -> block.steps.map { step ->
                val part = when (step) {
                    is ThinkingStep.ReasoningStep -> step.reasoning
                    is ThinkingStep.ToolStep -> step.tool
                    is ThinkingStep.ServerToolStep -> step.tool
                    is ThinkingStep.NarrationStep -> step.text
                }
                // Identity + original order handles repeated equal parts without a content hash.
                while (cursor < parts.size && parts[cursor] !== part) cursor++
                check(cursor < parts.size) { "Grouped part is not in the source message" }
                cursor++
                cursor - 1
            }
        }
        val kind = when (block) {
            is MessagePartBlock.ThinkingBlock -> "thinking"
            is MessagePartBlock.ChartBlock -> "chart"
            is MessagePartBlock.ContentBlock -> "content"
        }
        MessageDisplayDocument.Part(
            key = "$messageId:$kind:${indices.first()}",
            sourcePartIndices = indices,
            block = block,
            textDocumentId = if (block is MessagePartBlock.ContentBlock && block.part is UIMessagePart.Text)
                MessageTextDocumentId(messageId, block.index) else null,
        )
    }
    return MessageDisplayDocument(messageId, parts, MessageDisplayDocument.Chrome("$messageId:header"),
        body, MessageDisplayDocument.Chrome("$messageId:footer"))
}
