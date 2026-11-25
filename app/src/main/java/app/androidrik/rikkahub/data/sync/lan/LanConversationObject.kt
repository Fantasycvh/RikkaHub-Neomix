package app.androidrik.rikkahub.data.sync.lan

import app.androidrik.rikkahub.data.export.collectAttachmentUrls
import app.androidrik.rikkahub.data.export.rewriteAttachmentUrls
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.healMissingParents
import app.androidrik.rikkahub.utils.normalizeGeneratedImagePath
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Locale

private val extensionPattern = Regex("[a-z0-9]{1,12}")

@Serializable
internal data class LanAttachment(val digest: String, val fileExtension: String, val image: Boolean, val modifiedAt: Long) {
    val fileName: String get() = "lan-$digest-$modifiedAt.$fileExtension"
    val folder: String get() = if (image) "images" else "upload"
    val url: String get() = "file:///rikka-lan-v1/$folder/$fileName"

    fun validate() {
        require(isLanDigest(digest) && extensionPattern.matches(fileExtension) && modifiedAt >= 0)
    }
}

@Serializable
internal data class LanConversationObject(val conversation: Conversation, val attachments: List<LanAttachment>)

internal data class LanConversationSource(val manifest: LanManifestObject, val objectFiles: Map<String, File>)

/** The manifest hashes portable conversation bytes. Device paths and local checkpoint row IDs do not travel. */
internal suspend fun captureLanConversation(conversation: Conversation, filesDir: File, objectFile: File, json: Json): LanConversationSource {
    require(!conversation.isSparse)
    val mapping = linkedMapOf<String, String>()
    val references = linkedMapOf<String, LanAttachment>()
    val objects = linkedMapOf<String, File>()
    val allowed = listOf("upload", "images").associateWith { File(filesDir, it).canonicalFile }
    conversation.collectAttachmentUrls().forEach { url ->
        val normalized = normalizeLocalFileUrl(filesDir, url).removePrefix("file://")
        val file = File(normalizeGeneratedImagePath(filesDir, normalized)).canonicalFile
        val folder = allowed.entries.firstOrNull { (_, root) -> file.path.startsWith(root.path + File.separator) }?.key
        require(folder != null && file.isFile && file.length() in 1..LAN_OBJECT_MAX_BYTES) { "Unavailable LAN attachment" }
        val digest = hashLanFile(file)
        val extension = file.extension.lowercase(Locale.ROOT).takeIf(extensionPattern::matches) ?: "bin"
        val attachment = LanAttachment(digest, extension, folder == "images", file.lastModified())
        mapping[url] = attachment.url
        references[attachment.url] = attachment
        objects.putIfAbsent(digest, file)
    }
    val wire = conversation.rewriteAttachmentUrls(mapping).let { portable -> portable.copy(
        messageNodes = portable.messageNodes.healMissingParents(),
        folderId = null,
        workspaceCwd = null,
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        frozenAnchors = null,
        frozenFileAnchors = null,
        exportDisplayConfig = null,
        exportDisplayOverrides = emptyMap(),
        compressionSummaries = conversation.compressionSummaries.map { it.copy(id = 0) },
    ) }
    val ordered = references.values.sortedBy { it.url }
    objectFile.writeText(json.encodeToString(LanConversationObject(wire, ordered)))
    val sha256 = hashLanFile(objectFile)
    val entry = LanManifestObject("conversation:${conversation.id}", sha256, conversation.updateAt.toEpochMilli(),
        objectFile.length(), ordered.map { it.digest }.distinct().sorted())
    entry.validate()
    objects[sha256] = objectFile
    return LanConversationSource(entry, objects)
}

/** Prepare a received conversation and its files for Room. This function never mutates the database. */
internal suspend fun materializeLanConversation(entry: LanManifestObject, objects: LanObjectStore, filesDir: File, json: Json): Conversation =
    materializeLanConversation(entry, filesDir, json, objects::completed)

internal suspend fun materializeLanConversation(
    entry: LanManifestObject,
    filesDir: File,
    json: Json,
    findObject: suspend (String) -> File?,
): Conversation {
    entry.validate()
    val source = requireNotNull(findObject(entry.sha256))
    check(source.length() == entry.size && hashLanFile(source) == entry.sha256) { "Invalid LAN conversation object" }
    val wire = json.decodeFromString<LanConversationObject>(source.readText())
    require(entry.key == "conversation:${wire.conversation.id}" && entry.updateTime == wire.conversation.updateAt.toEpochMilli())
    validateLanConversation(wire.conversation)
    val refs = wire.attachments.associateBy { it.url }
    require(wire.attachments.size == refs.size && refs.keys == wire.conversation.collectAttachmentUrls().toSet())
    require(wire.attachments.map { it.digest }.toSet() == entry.attachmentShas.toSet())
    val approved = wire.attachments.associateWith { ref ->
        ref.validate()
        val contents = requireNotNull(findObject(ref.digest))
        check(hashLanFile(contents) == ref.digest) { "Invalid LAN attachment" }
        contents
    }
    val local = approved.entries.associate { (ref, content) ->
        val root = File(filesDir, ref.folder).canonicalFile
        require(root.parentFile == filesDir.canonicalFile)
        check(root.isDirectory || root.mkdirs())
        val target = File(root, ref.fileName)
        require(target.canonicalFile == target.absoluteFile)
        if (!target.isFile) {
            val staged = File.createTempFile("lan-attachment-", ".part", root)
            try {
                content.inputStream().buffered().use { input ->
                    FileOutputStream(staged).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= LAN_OBJECT_MAX_BYTES)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(hashLanFile(staged) == ref.digest)
                if (ref.modifiedAt > 0) check(staged.setLastModified(ref.modifiedAt))
                try {
                    Files.move(staged.toPath(), target.toPath())
                } catch (_: FileAlreadyExistsException) {
                    // Another transfer published the same path while this file was staged.
                }
            } finally {
                staged.delete()
            }
        }
        check(hashLanFile(target) == ref.digest) { "LAN attachment path conflicts with an existing file" }
        ref.url to "file://${target.absolutePath.replace(File.separatorChar, '/')}"
    }
    return wire.conversation.rewriteAttachmentUrls(local)
}

private fun validateLanConversation(conversation: Conversation) {
    require(conversation.folderId == null && conversation.workspaceCwd == null && conversation.frozenAnchors == null &&
        conversation.frozenFileAnchors == null && conversation.modeInjectionIds.isEmpty() && conversation.lorebookIds.isEmpty() &&
        conversation.exportDisplayConfig == null && conversation.exportDisplayOverrides.isEmpty())
    val seen = hashSetOf<kotlin.uuid.Uuid>()
    conversation.messageNodes.forEachIndexed { index, node ->
        require(node.messages.isNotEmpty() && node.selectIndex in node.messages.indices)
        require(if (index == 0) node.parentId == null else node.parentId in seen)
        require(seen.add(node.id))
    }
    require(conversation.activeLeafNodeId == null || conversation.activeLeafNodeId in seen)
    require(conversation.compressionSummaries.all { it.conversationId == conversation.id && it.id == 0L })
}
