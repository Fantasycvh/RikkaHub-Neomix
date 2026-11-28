package app.androidrik.rikkahub.data.media

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.tools.IMAGE_TOOL_NAME
import app.androidrik.rikkahub.data.ai.tools.parseGeneratedImagePaths
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.MediaCreationAsset
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import app.androidrik.rikkahub.data.repository.MediaCreationRepository
import app.androidrik.rikkahub.service.MediaCreationService
import app.androidrik.rikkahub.service.MediaCreationSubmission
import app.androidrik.rikkahub.utils.normalizeGeneratedImagePath
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

internal data class ChatMediaReference(val id: String, val path: String, val nodeId: Uuid? = null)

/** IDs are scoped to the selected message path, never arbitrary model-supplied file paths. */
internal fun chatMediaSessionId(conversationId: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("chat-media:$conversationId".toByteArray(Charsets.UTF_8)).toString())

class ChatMediaGeneration(
    private val repository: MediaCreationRepository,
    private val service: MediaCreationService,
    private val filesManager: FilesManager,
) {
    internal suspend fun snapshot(conversation: app.androidrik.rikkahub.data.model.Conversation) =
        materializeChatMediaSnapshot(conversation, repository::getRecord) { record, index, output ->
            repository.resolve(chatMediaGalleryPath(record.id, index, output)).absolutePath
        }

    internal suspend fun references(messages: List<UIMessage>): MutableMap<String, ChatMediaReference> =
        withContext(Dispatchers.IO) {
            val refs = linkedMapOf<String, ChatMediaReference>()
            val knownRecords = mutableMapOf<String, MediaCreationRecord>()
            val mediaMessages = messages.filter { message -> message.parts.any {
                it is UIMessagePart.Image || (it is UIMessagePart.Tool && it.toolName == IMAGE_TOOL_NAME)
            } }.takeLast(32)
            mediaMessages.forEach { message ->
                message.parts.forEachIndexed { index, part ->
                    if (part is UIMessagePart.Image) {
                        localPath(normalizeLocalFileUrl(filesManager.getFilesDir(), part.url))?.let {
                            val id = "${message.id}:$index"
                            val sourceId = (part.metadata?.get("media_record_id") as? JsonPrimitive)?.contentOrNull
                            refs[id] = ChatMediaReference(id, it, knownRecords[sourceId]?.nodeId)
                        }
                    }
                    if (part is UIMessagePart.Tool && part.toolName == IMAGE_TOOL_NAME) {
                        val content = runCatching {
                            Json.parseToJsonElement(part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }) as? JsonObject
                        }.getOrNull() ?: return@forEachIndexed
                        val record = (content["record_id"] as? JsonPrimitive)?.contentOrNull?.let {
                            runCatching { Uuid.parse(it) }.getOrNull()
                        }?.let { repository.getRecord(it) }
                        if (record != null) {
                            knownRecords[record.id.toString()] = record
                            refs.putAll(outputReferences(record))
                        } else {
                            parseGeneratedImagePaths(content).forEachIndexed { outputIndex, path ->
                                localPath(normalizeGeneratedImagePath(filesManager.getFilesDir(), path))?.let {
                                    val snapshotImage = ((content["images"] as? JsonArray)?.getOrNull(outputIndex) as? JsonObject)
                                    val id = (snapshotImage?.get("id") as? JsonPrimitive)?.contentOrNull
                                        ?: "${message.id}:$index:$outputIndex"
                                    refs[id] = ChatMediaReference(id, it)
                                }
                            }
                        }
                    }
                }
            }
            refs.entries.toList().takeLast(32).associateTo(linkedMapOf()) { it.toPair() }
        }

    internal fun outputReferences(record: MediaCreationRecord): Map<String, ChatMediaReference> =
        record.outputs.mapIndexedNotNull { index, output ->
            if (output.isVideo) return@mapIndexedNotNull null
            localPath(repository.resolve(output.path).absolutePath)?.let {
                val id = "${record.id}:$index"
                id to ChatMediaReference(id, it, record.nodeId)
            }
        }.toMap()

    internal suspend fun generate(
        conversationId: String,
        turnId: String,
        option: MediaModelOption,
        prompt: String,
        count: Int,
        size: String,
        references: List<ChatMediaReference>,
    ): MediaCreationRecord {
        require(references.isEmpty() || app.androidrik.mediagen.model.ImageRole.REFERENCE in option.capabilities.imageRoles) {
            "The selected image model does not support reference images. Choose an image editing model."
        }
        val sessionId = chatMediaSessionId(conversationId)
        repository.getOrCreateSession(sessionId, "",
            app.androidrik.rikkahub.data.model.MediaCreationDraft(modelId = option.model.id, sourceConversationId = conversationId))
        // Only edits of a node in this timeline become a new version. Forked chats start their own timeline.
        val nodeId = references.singleOrNull()?.nodeId?.takeIf { node ->
            repository.nodeBelongsToSession(node, sessionId)
        }
        val requestKey = kotlinx.serialization.json.buildJsonArray {
            listOf(conversationId, turnId, option.model.id.toString(), prompt, count.toString(), size).forEach {
                add(kotlinx.serialization.json.JsonPrimitive(it))
            }
            references.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.id)) }
        }.toString()
        val recordId = Uuid.parse(UUID.nameUUIDFromBytes(requestKey.toByteArray(Charsets.UTF_8)).toString())
        return service.submit(MediaCreationSubmission(
            sessionId = sessionId,
            nodeId = nodeId,
            option = option,
            prompt = prompt,
            params = MediaCreationParams(count = count, resolution = size.takeUnless { it == "auto" }),
            assets = references.map { MediaCreationAsset(it.path) },
        ), recordId = recordId)
    }

    private fun localPath(path: String): String? = runCatching {
        if (path.contains("://") && !path.startsWith("file://")) return@runCatching null
        val file = File(path.removePrefix("file://")).canonicalFile
        val root = filesManager.getFilesDir().canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return@runCatching null
        // Android's /data/data and /data/user/0 aliases must use the same canonical root.
        file.relativeTo(root).path
    }.getOrNull()
}
