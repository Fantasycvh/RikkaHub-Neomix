package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.semanticRole

internal object InvokedSkillTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = expandInvokedSkill(messages)
}

internal fun expandInvokedSkill(messages: List<UIMessage>): List<UIMessage> {
    // Choose the latest real user message, including scheduled triggers.
    val index = messages.indexOfLast { message ->
        message.semanticRole == MessageRole.USER &&
            (!message.isSynthetic || message.annotations.any { it is UIMessageAnnotation.ScheduledTrigger })
    }
    val message = messages.getOrNull(index) ?: return messages
    val skill = message.annotations.filterIsInstance<UIMessageAnnotation.InvokedSkill>().firstOrNull()
        ?: return messages
    val instructions = UIMessagePart.Text(
        "Use the following instructions for the user-selected skill ${skill.name} for this turn.\n\n${skill.instructions}",
    )
    return messages.toMutableList().apply {
        this[index] = message.copy(
            parts = listOf(instructions) + message.parts,
            annotations = message.annotations.filterNot { it is UIMessageAnnotation.InvokedSkill },
        )
    }
}
