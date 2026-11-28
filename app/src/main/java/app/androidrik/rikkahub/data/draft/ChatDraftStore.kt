package app.androidrik.rikkahub.data.draft

import android.util.Log
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.localFileUrls
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private const val TAG = "ChatDraftStore"

const val CHAT_DRAFTS_FILE_NAME = "chat_drafts.json"
const val DRAFT_PERSIST_DEBOUNCE_MS = 300L
const val MAX_DRAFT_ENTRIES = 100

@Serializable
data class ReplacedAttachmentDraft(
    val original: UIMessagePart,
    val replacement: UIMessagePart,
)

@Serializable
data class EditingDraftState(
    val messageId: Uuid,
    val originalParts: List<UIMessagePart>,
    val textIndex: Int? = null,
    val replacedAttachments: List<ReplacedAttachmentDraft> = emptyList(),
    val keepFollowing: Boolean = true,
)

@Serializable
data class ChatDraft(
    val text: String = "",
    val attachments: List<UIMessagePart> = emptyList(),
    val pendingQuote: UIMessageAnnotation.QuotedMessage? = null,
    val pendingSkills: List<String> = emptyList(),
    val editing: EditingDraftState? = null,
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = text.isBlank() &&
            attachments.isEmpty() &&
            pendingQuote == null &&
            pendingSkills.isEmpty() &&
            editing == null

    fun contentEquals(other: ChatDraft): Boolean =
        text == other.text &&
            attachments == other.attachments &&
            pendingQuote == other.pendingQuote &&
            pendingSkills == other.pendingSkills &&
            editing == other.editing

    fun referencedLocalFileUrls(): Set<String> = buildSet {
        addAll(attachments.localFileUrls())
        editing?.let { edit ->
            addAll(edit.originalParts.localFileUrls())
            addAll(edit.replacedAttachments.flatMap { listOf(it.original, it.replacement) }.localFileUrls())
        }
    }
}

@Serializable
private data class ChatDraftEnvelope(
    val version: Int = 1,
    val drafts: Map<String, ChatDraft> = emptyMap(),
)

sealed interface ConversationDraftEvent {
    val conversationId: Uuid
    data class Deleted(override val conversationId: Uuid) : ConversationDraftEvent
    data class Restored(override val conversationId: Uuid) : ConversationDraftEvent
}

internal fun localDraftNormalizedPath(url: String, filesDir: File): String? {
    if (!url.startsWith("file:")) return null
    val normalized = normalizeLocalFileUrl(filesDir, url)
    val rawPath = runCatching { URI(normalized).path }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: normalized.removePrefix("file://").removePrefix("file:")
    return runCatching { File(rawPath).canonicalPath }.getOrNull() ?: rawPath
}

/**
 * 校验草稿引用的本地 `file:` 附件在当前安装目录下是否仍然存在。
 * 非 `file:` URI 原样放行；纯 JVM 可测，不依赖 `android.net.Uri`。
 */
internal fun localDraftFileExists(url: String, filesDir: File): Boolean {
    if (!url.startsWith("file:")) return true
    val resolved = localDraftNormalizedPath(url, filesDir) ?: return false
    return runCatching { File(resolved).isFile }.getOrDefault(false)
}

/**
 * 本机独立会话草稿仓库：内存缓存 + 防抖 JSON 原子落盘（不进 Room、不进备份/LAN 同步）。
 */
class ChatDraftStore(
    private val storageFile: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = DRAFT_PERSIST_DEBOUNCE_MS,
    private val maxEntries: Int = MAX_DRAFT_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val drafts = LinkedHashMap<Uuid, ChatDraft>()
    private val touchedBeforeLoad = HashSet<Uuid>()
    private val loaded = CompletableDeferred<Unit>()
    private val persistMutex = Mutex()
    private var persistJob: Job? = null

    private val _draftConversationIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val draftConversationIds: StateFlow<Set<Uuid>> = _draftConversationIds.asStateFlow()

    private val _conversationEvents = MutableSharedFlow<ConversationDraftEvent>(extraBufferCapacity = 32)
    val conversationEvents: SharedFlow<ConversationDraftEvent> = _conversationEvents.asSharedFlow()

    val isLoaded: Boolean
        get() = loaded.isCompleted

    init {
        scope.launch(ioDispatcher) {
            val loadedDrafts = readFromDisk()
            synchronized(lock) {
                loadedDrafts.forEach { (id, draft) ->
                    // 冷启动期间已被用户编辑或删除的会话不被慢到达的旧磁盘数据覆盖。
                    if (id !in touchedBeforeLoad && !draft.isEmpty) {
                        drafts[id] = draft
                    }
                }
                trimOldestLocked(pinnedId = null)
                _draftConversationIds.value = drafts.keys.toSet()
            }
            loaded.complete(Unit)
        }
    }

    fun getImmediate(conversationId: Uuid): ChatDraft? {
        if (!isLoaded) return null
        return synchronized(lock) { drafts[conversationId] }
    }

    suspend fun get(conversationId: Uuid): ChatDraft? {
        loaded.await()
        return synchronized(lock) { drafts[conversationId] }
    }

    fun allReferencedLocalFileUrls(): Set<String> = synchronized(lock) {
        drafts.values.flatMapTo(HashSet()) { it.referencedLocalFileUrls() }
    }

    /** 加载完成前无法证明文件无引用，保守保留；路径别名按当前安装目录比较。 */
    fun referencesLocalFile(url: String, filesDir: File): Boolean {
        if (!isLoaded) return true
        val urls = allReferencedLocalFileUrls()
        if (url in urls) return true
        val path = localDraftNormalizedPath(url, filesDir) ?: return false
        return urls.any { localDraftNormalizedPath(it, filesDir) == path }
    }

    fun restore(conversationId: Uuid, draft: ChatDraft) {
        save(conversationId, draft, immediate = true)
        _conversationEvents.tryEmit(ConversationDraftEvent.Restored(conversationId))
    }

    fun save(conversationId: Uuid, draft: ChatDraft, immediate: Boolean = false) {
        if (draft.isEmpty) {
            remove(conversationId, immediate = true)
            return
        }
        val shouldPersist = synchronized(lock) {
            if (!isLoaded) touchedBeforeLoad.add(conversationId)
            val existing = drafts[conversationId]
            if (existing != null && existing.contentEquals(draft)) {
                false
            } else {
                drafts[conversationId] = draft.copy(updatedAt = clock())
                trimOldestLocked(pinnedId = conversationId)
                _draftConversationIds.value = drafts.keys.toSet()
                true
            }
        }
        if (shouldPersist || immediate) {
            schedulePersist(immediate)
        }
    }

    fun remove(conversationId: Uuid, immediate: Boolean = true): ChatDraft? {
        var removedDraft: ChatDraft? = null
        val shouldPersist = synchronized(lock) {
            val wasUnloaded = !isLoaded
            if (wasUnloaded) touchedBeforeLoad.add(conversationId)
            removedDraft = drafts.remove(conversationId)
            if (removedDraft != null) {
                _draftConversationIds.value = drafts.keys.toSet()
            }
            removedDraft != null || wasUnloaded
        }
        if (shouldPersist) {
            schedulePersist(immediate)
        }
        return removedDraft
    }

    /** 会话实体被删除时调用：移除草稿并广播删除事件，供栈内尚存活的页面清空残留输入态。 */
    fun onConversationDeleted(conversationId: Uuid): ChatDraft? {
        val removed = remove(conversationId, immediate = true)
        _conversationEvents.tryEmit(ConversationDraftEvent.Deleted(conversationId))
        return removed
    }

    internal suspend fun awaitPendingPersist() {
        loaded.await()
        persistJob?.join()
    }

    private fun trimOldestLocked(pinnedId: Uuid?) {
        if (maxEntries <= 0) {
            drafts.clear()
            return
        }
        if (drafts.size <= maxEntries) return
        val evictionCandidates = drafts.entries
            .filter { it.key != pinnedId }
            .sortedBy { it.value.updatedAt }
            .map { it.key }
        var overflow = drafts.size - maxEntries
        for (id in evictionCandidates) {
            if (overflow <= 0) break
            drafts.remove(id)
            overflow--
        }
    }

    private fun schedulePersist(immediate: Boolean) {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch(ioDispatcher) {
                if (!immediate && debounceMillis > 0L) {
                    delay(debounceMillis)
                }
                loaded.await()
                persistSnapshotToDisk()
            }
        }
    }

    private suspend fun persistSnapshotToDisk() {
        persistMutex.withLock {
            val snapshot = synchronized(lock) {
                drafts.entries.associate { (id, draft) -> id.toString() to draft }
            }
            runCatching {
                val parent = storageFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val encoded = JsonInstant.encodeToString(ChatDraftEnvelope(drafts = snapshot))
                val tmp = File(parent ?: File("."), "${storageFile.name}.tmp")
                tmp.writeText(encoded)
                try {
                    Files.move(
                        tmp.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        tmp.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }.onFailure { error ->
                runCatching { Log.e(TAG, "Failed to persist chat drafts", error) }
            }
        }
    }

    private fun readFromDisk(): Map<Uuid, ChatDraft> {
        if (!storageFile.isFile) return emptyMap()
        val raw = runCatching { storageFile.readText() }.getOrElse { error ->
            runCatching { Log.e(TAG, "Failed to read chat drafts file", error) }
            return emptyMap()
        }
        if (raw.isBlank()) return emptyMap()
        val envelope = runCatching {
            JsonInstant.decodeFromString<ChatDraftEnvelope>(raw)
        }.getOrElse { error ->
            runCatching { Log.e(TAG, "Corrupted chat drafts file, ignoring", error) }
            return emptyMap()
        }
        val parsed = LinkedHashMap<Uuid, ChatDraft>()
        envelope.drafts.forEach { (rawId, draft) ->
            val id = runCatching { Uuid.parse(rawId) }.getOrNull() ?: return@forEach
            if (!draft.isEmpty) {
                parsed[id] = draft
            }
        }
        return parsed
    }
}
