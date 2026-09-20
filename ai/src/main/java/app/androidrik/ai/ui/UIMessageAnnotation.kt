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

    /**
     * 拼装预览 Tier 4：合成注入消息的**来源条目 id**，按内容拼接顺序排列。
     *
     * 为什么需要：`PromptInjectionTransformer.applyInjections` 把同一位置的多条注入
     * `joinToString("\n")` **合并成一条消息**，逐条身份在这一步就丢了——预览里三条世界书条目
     * 落到一段文本，无法归因、也就无法「点一段跳到产出它的条目」。
     *
     * 为什么是**注解而不是改合并方式**：合并本身要保留（它是既有的请求形状，改动会改变
     * 逐字节输出并可能触碰端点的行为假设）。注解不在 part 白名单路径上 ⇒ 对请求字节
     * **零影响**，只作为带外身份标记供预览读取（同 [QuotedMessage] 的取舍，见其注释）。
     *
     * 只在**非 system 位置**的合并且上（TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH / END_OF_CHAT）：
     * system 区的 BEFORE/AFTER 注入被拼进 system 本体、且中间夹着原有人设正文，
     * 位置关系无法用一条有序 id 列表表达；那一路由预览侧直接调 `collectInjections` 归因。
     */
    @Serializable
    @SerialName("injection_sources")
    data class InjectionSources(
        val entryIds: List<Uuid>,
    ) : UIMessageAnnotation()
}
