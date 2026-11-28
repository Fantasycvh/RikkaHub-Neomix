package app.androidrik.rikkahub.data.media

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.tools.IMAGE_TOOL_NAME
import app.androidrik.rikkahub.data.ai.tools.imageToolMediaResult
import app.androidrik.rikkahub.data.ai.tools.parseGeneratedImagePaths
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MediaCreationOutput
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ChatMediaGalleryImage(val index: Int, val path: String)

internal fun chatMediaGalleryPath(recordId: Uuid, index: Int, output: MediaCreationOutput): String =
    "images/chat_${recordId}_$index.${File(output.path).extension}"

/** Export/sync need a portable final snapshot even when chat originally returned a pending task. */
internal suspend fun materializeChatMediaSnapshot(
    conversation: Conversation,
    loadRecord: suspend (Uuid) -> MediaCreationRecord?,
    loadGallery: suspend (Uuid) -> List<ChatMediaGalleryImage> = { emptyList() },
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
                if (record == null) {
                    // Gallery rows/files outlive media timelines and travel in full
                    // backups. They also recover a pending tool saved before completion.
                    if (parseGeneratedImagePaths(content).isNotEmpty()) return@parts part
                    val images = loadGallery(id).sortedBy { it.index }
                    if (images.isEmpty()) return@parts part
                    val result = buildJsonObject {
                        content.filterKeys { it != "error" }.forEach { (key, value) -> put(key, value) }
                        put("ok", true)
                        put("status", "SUCCEEDED")
                        put("count", images.size)
                        put("images", buildJsonArray {
                            images.forEach { image -> add(buildJsonObject {
                                put("id", "$id:${image.index}")
                                put("path", image.path)
                            }) }
                        })
                        put("note", "Images are shown to the user. Use their IDs as reference_images to edit. You have not seen their contents.")
                    }
                    return@parts part.copy(output = listOf(UIMessagePart.Text(result.toString())))
                }
                if (record.status.isActive) return@parts part
                updatedAt = maxOf(updatedAt, record.updateAt)
                val paths = record.outputs.mapIndexed { index, output -> output.path to resolveOutput(record, index, output) }.toMap()
                part.copy(output = listOf(UIMessagePart.Text(imageToolMediaResult(record) { paths.getValue(it) })))
            })
        })
    }
    return conversation.copy(messageNodes = nodes, updateAt = updatedAt)
}
