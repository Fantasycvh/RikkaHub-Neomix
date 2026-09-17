package app.androidrik.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.androidrik.ai.core.MessageRole
import kotlin.uuid.Uuid

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /** #1812：半截生成失败保留为草稿——消息上方渲染警示横幅（重试走现有 regenerate）。 */
    @Serializable
    @SerialName("partial_failure")
    data object PartialFailure : UIMessageAnnotation()

    /**
     * #1906：用户消息引用了某条历史消息。
     *
     * 为什么是**注解**而不是新的 [UIMessagePart]：三家 provider 的 part→API 转换都是
     * 「白名单遍历 + `else -> null`」，新 part 会被静默过滤——表现为「本地看得见、模型看不见」
     * 的假功能。注解不在该白名单路径上（已按注解渲染 UI 的先例：[UrlCitation]、
     * [PartialFailure]），且 `MessageNodeEntity.messages` 是 JSON blob、反序列化开了
     * `ignoreUnknownKeys`，故新增注解类型无需数据库迁移。
     *
     * [content] 是**固定快照**：引用发生后原消息被编辑/重新生成/删除都不再影响它——
     * 快照既是悬空时的降级显示，也是发送时展开成文本的唯一依据（源消息可能已被截断，
     * 不在本次请求窗口内）。[messageId] 只在**所属会话内**解释，用于定位与显示，
     * 跨会话无意义；解析不到时保留快照、不伪造引用目标。
     */
    @Serializable
    @SerialName("quoted_message")
    data class QuotedMessage(
        val messageId: Uuid,
        val role: MessageRole,
        val content: String,
        val createdAt: LocalDateTime? = null,
    ) : UIMessageAnnotation()
}
