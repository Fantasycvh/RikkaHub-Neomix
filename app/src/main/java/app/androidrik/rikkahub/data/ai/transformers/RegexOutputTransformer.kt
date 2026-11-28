package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantAffectScope
import app.androidrik.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override val affectsOnlyLatestOnStream: Boolean = true

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        var anyMessageChanged = false
        val mapped = messages.map { message ->
            val scope = when (message.role) {
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@map message // Skip non-assistant messages
            }
            var partsChanged = false
            val newParts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        val updatedText = replaceRegexesGuarded(part.text) {
                            it.replaceRegexes(assistant, scope, visual = false)
                        }
                        if (updatedText != part.text) {
                            partsChanged = true
                            part.copy(text = updatedText)
                        } else {
                            part
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        val updatedReasoning = replaceRegexesGuarded(part.reasoning) {
                            it.replaceRegexes(assistant, scope, visual = false)
                        }
                        if (updatedReasoning != part.reasoning) {
                            partsChanged = true
                            part.copy(reasoning = updatedReasoning)
                        } else {
                            part
                        }
                    }

                    else -> part
                }
            }
            if (partsChanged) {
                anyMessageChanged = true
                message.copy(parts = newParts)
            } else {
                message
            }
        }
        return if (anyMessageChanged) mapped else messages
    }
}

/**
 * #2317 治本（A）：非视觉正则的写回会落库（GenerationHandler 收尾把 visualTransforms 的结果
 * 并入消息本体）。规则把**非空**内容整体替换成空白时保留原文——否则会留下一条空候选：
 * UI 上整条回答连同工具栏一起消失（只剩用量行），且空消息会被后续轮次当作上下文发出
 * （`messagesForRequest` 不过滤空消息，部分 provider 会直接报错）。
 * 局部清空（消息里还有其他非空 part）不拦——那不构成空候选，属规则作者的表达自由。
 */
internal fun replaceRegexesGuarded(original: String, transform: (String) -> String): String {
    val replaced = transform(original)
    return if (replaced.isBlank() && original.isNotBlank()) original else replaced
}
