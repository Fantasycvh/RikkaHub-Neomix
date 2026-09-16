package app.androidrik.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

@Serializable
enum class FavoriteType(val value: String) {
    @SerialName("node")
    NODE("node"),

    // Keep old value for compatibility with existing data.
    @SerialName("message")
    MESSAGE("message");

    companion object {
        fun fromValue(value: String): FavoriteType? = entries.firstOrNull { it.value == value }
    }
}

@Serializable
data class FavoriteMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val previewText: String? = null,
    /**
     * 标签 id 列表（**存 id 而非名** → 标签改名只需改标签池一处，不必批量更新收藏）。
     *
     * **零迁移**：`meta_json` 是 JSON blob 且 `JsonInstant` 开了 `ignoreUnknownKeys`
     * → 旧行缺该字段自动为默认空列表，不加列、不写迁移。见设计稿 §五。
     */
    val tagIds: List<Uuid> = emptyList(),
)

@Serializable
data class NodeFavoriteRef(
    val conversationId: Uuid,
    val nodeId: Uuid,
)

data class NodeFavoriteTarget(
    val conversationId: Uuid,
    val conversationTitle: String,
    val nodeId: Uuid,
    val node: MessageNode,
)

fun UIMessage.buildFavoritePreview(maxLength: Int = 160): String {
    val plainText = parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text.trim() }
        .trim()
    if (plainText.isNotBlank()) {
        return plainText.take(maxLength)
    }
    return when (role) {
        MessageRole.USER -> "[User Message]"
        MessageRole.ASSISTANT -> "[Assistant Message]"
        MessageRole.SYSTEM -> "[System Message]"
        MessageRole.TOOL -> "[Tool Message]"
    }
}

fun MessageNode.buildFavoritePreview(maxLength: Int = 160): String {
    return currentMessage.buildFavoritePreview(maxLength)
}
