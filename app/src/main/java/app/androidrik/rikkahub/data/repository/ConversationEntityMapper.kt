package app.androidrik.rikkahub.data.repository

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Conversation ⇄ ConversationEntity 的**单一映射点**（批次 9 从 [ConversationRepository] 拆出，
 * 同包顶层函数：仓库内调用点无需 import 即可解析）。
 *
 * 两列注入 id 的编码格式由 [encodeConversationInjectionIds] 一处定义——整对象保存与
 * 单列原语两条写路径共用，格式分叉会让读路径静默丢绑定（见其 KDoc）。
 */

internal fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
    require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
    // 注意：compressionSummaries 不在此持久化（独立表 compression_checkpoint 管理，见
    // getCompressionCheckpoints/addCompressionCheckpoint）。整对象保存不会误删检查点；
    // 但内存态若构造时漏填 compressionSummaries，会导致该会话内存中压缩态「看起来丢失」，
    // 重新从 DB 加载即恢复（加载路径 loadCompressionSummaries 已填充）。
    return ConversationEntity(
        id = conversation.id.toString(),
        title = conversation.title,
        nodes = "[]",  // nodes 现在存储在单独的表中
        createAt = conversation.createAt.toEpochMilli(),
        updateAt = conversation.updateAt.toEpochMilli(),
        assistantId = conversation.assistantId.toString(),
        chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
        isPinned = conversation.isPinned,
        customSystemPrompt = conversation.customSystemPrompt ?: "",
        goal = conversation.goal ?: "",
        modeInjectionIds = encodeConversationInjectionIds(conversation.modeInjectionIds),
        lorebookIds = encodeConversationInjectionIds(conversation.lorebookIds),
        workspaceCwd = conversation.workspaceCwd ?: "",
        folderId = conversation.folderId?.toString() ?: "",
        memoryDisabled = conversation.memoryDisabled,
        hidden = conversation.hidden,
        frozenAnchors = conversation.frozenAnchors,
        upstreamConfig = conversation.upstreamConfig?.let { JsonInstant.encodeToString(it) },
        exportDisplayConfig = conversation.exportDisplayConfig?.let { JsonInstant.encodeToString(it) },
        // 空 map 存 null（列里「没有覆盖」的规范形态），解码时 null ⇒ emptyMap
        exportDisplayOverrides = conversation.exportDisplayOverrides
            .takeIf { it.isNotEmpty() }
            ?.let { JsonInstant.encodeToString(it) },
        // 行内「最近活跃」= 末尾消息的 createdAt。消息在内存里（本映射点的入参就带 messageNodes）
        // 时按实际算；元信息保存（轻量 Conversation 无消息）时返回 null，
        // 由 DAO 的 COALESCE 保留库里原值（见 updateMetadata 注释）。
        lastMessageAt = conversation.messageNodes.lastOrNull()
            ?.let { node -> node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull() }
            ?.createdAt
            // UIMessage.createdAt 是 kotlinx.datetime.LocalDateTime（无时区）
            // ⇒ 按设备时区换算成毫秒（与压缩检查点等处同一惯用法）
            ?.toInstant(TimeZone.currentSystemDefault())
            ?.toEpochMilliseconds(),
    )
}

internal fun conversationEntityToConversation(
    conversationEntity: ConversationEntity,
    messageNodes: List<MessageNode>,
    compressionSummaries: List<CompressionSummary> = emptyList(),
): Conversation {
    return Conversation(
        id = Uuid.parse(conversationEntity.id),
        title = conversationEntity.title,
        messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
        compressionSummaries = compressionSummaries,
        createAt = Instant.ofEpochMilli(conversationEntity.createAt),
        updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
        lastMessageAt = conversationEntity.lastMessageAt?.let(Instant::ofEpochMilli),
        assistantId = Uuid.parse(conversationEntity.assistantId),
        chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
        isPinned = conversationEntity.isPinned,
        customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
        goal = conversationEntity.goal.ifEmpty { null },
        modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
        lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
        workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
        folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        memoryDisabled = conversationEntity.memoryDisabled,
        hidden = conversationEntity.hidden,
        frozenAnchors = conversationEntity.frozenAnchors,
        upstreamConfig = conversationEntity.upstreamConfig?.let { JsonInstant.decodeFromString(it) },
        exportDisplayConfig = conversationEntity.exportDisplayConfig?.let { JsonInstant.decodeFromString(it) },
        exportDisplayOverrides = conversationEntity.exportDisplayOverrides
            ?.let { JsonInstant.decodeFromString<Map<String, ExportDisplayConfig>>(it) }
            ?: emptyMap(),
    )
}

internal fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
    return Conversation(
        id = Uuid.parse(entity.id),
        assistantId = Uuid.parse(entity.assistantId),
        title = entity.title,
        isPinned = entity.isPinned,
        createAt = Instant.ofEpochMilli(entity.createAt),
        updateAt = Instant.ofEpochMilli(entity.updateAt),
        lastMessageAt = entity.lastMessageAt?.let(Instant::ofEpochMilli),
        messageNodes = emptyList(),
        folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
    )
}

/**
 * 会话级注入 id 集合的**落库编码**（`mode_injection_ids` / `lorebook_ids` 两列共用）。
 *
 * 为什么必须抽成一处：这两列有两条写路径——整对象保存（`conversationToConversationEntity`）
 * 与单列原语（`ConversationRepository.updateConversationInjections`）。格式一旦分叉，
 * 读路径 `conversationEntityToConversation` 的 `decodeFromString` 只认其中一种 ⇒
 * 另一条路径写进去的绑定会在下次加载时丢失（或直接抛异常）。放在这里让「同一格式」是结构性的。
 */
internal fun encodeConversationInjectionIds(ids: Set<Uuid>): String =
    JsonInstant.encodeToString(ids)
