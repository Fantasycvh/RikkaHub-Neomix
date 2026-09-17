package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toPromptBlock

/**
 * #1906：把用户消息里的引用注解展开成文本前缀，让模型真的能看到被引用的内容。
 *
 * 为什么必须有这一步：引用存在 `UIMessageAnnotation.QuotedMessage` 上，而注解**不参与**
 * 请求组装（provider 只读 parts）——不展开就等于「UI 上有引用卡片、模型一无所知」。
 * 展开手法与 [DocumentAsPromptTransformer] 把文档转成 `<UploadFile>` 提示词同源。
 *
 * 三条约束：
 * 1. **只作用于请求副本**：transformer 接到的是 `buildInternalMessages(...)` 的产物，
 *    落库正文与 FTS 索引都读原始 `messages`，因此展开文本不会污染存储与搜索。
 * 2. **幂等**：展开后即移除该条引用注解，故工具多步循环里同一消息被反复 transform
 *    （`PromptInjectionTransformer` 也是每步重跑）不会累积出多份引用前缀。
 * 3. **自足**：只用注解里的快照，不回查源消息——源可能已被压缩检查点/`limitContext`
 *    截断而不在请求窗口内，回查会得到「有时有、有时没有」的不确定行为。
 */
object QuoteTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = expandQuotes(messages)
}

/**
 * 展开逻辑（**纯函数**，便于单测覆盖幂等与自足性——`TransformerContext` 需要 Android Context，
 * 在 JVM 单测里难以构造；与 `parseSillyTavernLorebook` 同款做法）。
 */
internal fun expandQuotes(messages: List<UIMessage>): List<UIMessage> {
    if (messages.none { message -> message.annotations.any { it is UIMessageAnnotation.QuotedMessage } }) {
        return messages
    }
    return messages.map { message ->
        val quotes = message.annotations.filterIsInstance<UIMessageAnnotation.QuotedMessage>()
        if (quotes.isEmpty()) return@map message
        val block = quotes.joinToString("\n") { it.toPromptBlock() }
        message.copy(
            parts = listOf(UIMessagePart.Text(block)) + message.parts,
            // 移除已展开的引用注解 ⇒ 同一消息被反复 transform（工具多步循环）不会累积多份前缀
            annotations = message.annotations.filterNot { it is UIMessageAnnotation.QuotedMessage },
        )
    }
}
