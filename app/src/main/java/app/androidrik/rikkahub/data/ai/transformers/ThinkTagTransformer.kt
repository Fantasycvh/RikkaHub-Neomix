package app.androidrik.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.time.Instant

// 不把 EOF 当作闭合标签：流式未闭合、断流或字面标签示例都应保留原文。
private val THINKING_REGEX = Regex("\\A\\s*<think>([\\s\\S]*?)</think>")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override val affectsOnlyLatestOnStream: Boolean = true

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = false,
        )
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = true,
        )
    }
}

internal fun List<UIMessage>.transformThinkTags(
    now: Instant,
    generationFinished: Boolean,
): List<UIMessage> = map { message ->
    if (message.role != MessageRole.ASSISTANT) {
        return@map message
    }
    if (message.hasPart<UIMessagePart.Reasoning>()) {
        return@map if (generationFinished) {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = now)
                    } else {
                        part
                    }
                }
            )
        } else {
            message
        }
    }

    val textPartIndex = message.parts.indexOfFirst { part ->
        part is UIMessagePart.Text && part.text.isNotBlank()
    }
    val textPart = message.parts.getOrNull(textPartIndex) as? UIMessagePart.Text
        ?: return@map message
    if (!textPart.text.trimStart().startsWith("<think>")) return@map message
    val match = THINKING_REGEX.find(textPart.text) ?: return@map message
    val reasoning = UIMessagePart.Reasoning(
        reasoning = match.groupValues[1].trim(),
        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
        finishedAt = now,
    )
    val strippedText = textPart.copy(text = textPart.text.removeRange(match.range))

    message.copy(
        parts = buildList {
            addAll(message.parts.subList(0, textPartIndex))
            add(reasoning)
            add(strippedText)
            addAll(message.parts.subList(textPartIndex + 1, message.parts.size))
        }
    )
}
