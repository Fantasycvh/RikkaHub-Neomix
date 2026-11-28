package app.androidrik.rikkahub.data.media

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.tools.IMAGE_TOOL_NAME
import app.androidrik.rikkahub.data.ai.tools.imageToolMediaResult
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MediaCreationOutput
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.uuid.Uuid

internal fun chatMediaGalleryPath(recordId: Uuid, index: Int, output: MediaCreationOutput): String =
    "images/chat_${recordId}_$index.${File(output.path).extension}"

/** Export/sync need a portable final snapshot even when chat originally returned a pending task. */
internal suspend fun materializeChatMediaSnapshot(
    conversation: Conversation,
    loadRecord: suspend (Uuid) -> MediaCreationRecord?,
    resolveOutput: (MediaCreationRecord, Int, MediaCreationOutput) -> String,
): Conversation {
    val records = mutableMapOf<Uuid, MediaCreationRecord?>()
    var updatedAt = conversation.updateAt
    val nodes = conversation.messageNodes.map { node ->
        node.copy(messages = node.messages.map { message ->
            message.copy(parts = message.parts.map parts@{ part ->
                if (part !is UIMessagePart.Tool || part.toolName != IMAGE_TOOL_NAME) return@parts part
                val content = runCatching {
                    Json.parseToJsonElement(part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }) as? JsonObject
                }.getOrNull() ?: return@parts part
                val id = (content["record_id"] as? JsonPrimitive)?.content?.let {
                    runCatching { Uuid.parse(it) }.getOrNull()
                } ?: return@parts part
                val record = if (records.containsKey(id)) records[id] else loadRecord(id).also { records[id] = it }
                if (record == null || record.status.isActive) return@parts part
                updatedAt = maxOf(updatedAt, record.updateAt)
                val paths = record.outputs.mapIndexed { index, output -> output.path to resolveOutput(record, index, output) }.toMap()
                part.copy(output = listOf(UIMessagePart.Text(imageToolMediaResult(record) { paths.getValue(it) })))
            })
        })
    }
    return conversation.copy(messageNodes = nodes, updateAt = updatedAt)
}
