package app.androidrik.rikkahub.data.repository

import android.util.Log
import app.androidrik.rikkahub.data.ai.memoryQueryTokens
import app.androidrik.rikkahub.data.ai.memoryScoreBucket
import app.androidrik.rikkahub.data.ai.rankMemoriesForSearch
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryHistoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryTrashDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.MemoryReadSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

class MemoryRevisionConflictException(message: String) : IllegalStateException(message)

data class MemoryHistoryRecord(val event: MemoryHistoryEntity, val snapshot: MemoryEntity)
data class MemoryTrashRecord(val tombstone: MemoryTrashEntity, val snapshot: MemoryEntity)

/** Every business write, its history, and any quota tombstones share the same Room transaction. */
class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val transactions: MemoryTransactionRunner,
    private val historyDAO: MemoryHistoryDAO,
    private val trashDAO: MemoryTrashDAO,
) {
    companion object {
        private const val TAG = "MemoryRepository"
        const val GLOBAL_MEMORY_ID = "__global__"
        const val MAX_MEMORY_COUNT = 200
        const val MAX_HISTORY_COUNT = 20
        const val MAX_DESCRIPTION_LENGTH = 150
        const val DEFAULT_SEARCH_LIMIT = 5
        const val MAX_SEARCH_LIMIT = 20
        private const val PUBLIC_ID_LENGTH = 8
        private const val PUBLIC_ID_ALPHABET = "0123456789abcdef"
    }

    private val snapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun MemoryEntity.toModel() = AssistantMemory(
        id = publicId.ifBlank { id.toString() },
        content = content,
        revision = revision,
        importance = importance,
        description = description,
        sortKey = id,
        updatedAt = updatedAt,
        readCount = readCount,
        lastReadAt = lastReadAt,
        isAnchor = isAnchor,
        chatReadCount = chatReadCount,
        lastChatReadAt = lastChatReadAt,
        consolidationReadCount = consolidationReadCount,
        lastConsolidationReadAt = lastConsolidationReadAt,
    )

    private suspend fun generatePublicId(): String {
        repeat(16) {
            val candidate = buildString(PUBLIC_ID_LENGTH) {
                repeat(PUBLIC_ID_LENGTH) { append(PUBLIC_ID_ALPHABET[Random.nextInt(PUBLIC_ID_ALPHABET.length)]) }
            }
            // Deleted public ids remain reserved so old tools/history can never reference a new record.
            if (!memoryDAO.publicIdExists(candidate) && !trashDAO.publicIdExists(candidate)) return candidate
        }
        error("Failed to generate a unique memory public id after 16 attempts")
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> = getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
    suspend fun getGlobalMemories(): List<AssistantMemory> = getMemoriesOfAssistant(GLOBAL_MEMORY_ID)

    /** Trusted compatibility lookup. Model-facing callers must supply a store. */
    suspend fun getMemoryById(id: String): AssistantMemory? = findEntityByPublicId(id)?.toModel()

    suspend fun getMemoryInStore(storeId: String, id: String): AssistantMemory? =
        findEntityInStore(storeId, id)?.toModel()

    suspend fun deleteMemoriesOfAssistant(
        assistantId: String,
        actor: String = "USER",
        reason: String = "",
    ) = transactions.run {
        memoryDAO.getMemoriesOfAssistant(assistantId).forEach {
            moveToTrash(it, System.currentTimeMillis(), "DELETE", actor, reason)
        }
    }

    suspend fun updateContent(
        id: String,
        content: String,
        importance: Float? = null,
        description: String? = null,
        now: Long = System.currentTimeMillis(),
        expectedRevision: Long? = null,
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val old = requireNotNull(findEntityByPublicId(id)) { "Memory record $id not found" }
        checkRevision(old, expectedRevision, actor)
        updateEntityContent(old, content, importance, description, now, actor, reason)
    }

    suspend fun updateContentInStore(
        storeId: String,
        id: String,
        content: String,
        importance: Float? = null,
        description: String? = null,
        now: Long = System.currentTimeMillis(),
        expectedRevision: Long? = null,
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val old = requireNotNull(findEntityInStore(storeId, id)) { "Memory record $id not found in store" }
        checkRevision(old, expectedRevision, actor)
        updateEntityContent(old, content, importance, description, now, actor, reason)
    }

    private suspend fun updateEntityContent(
        old: MemoryEntity,
        content: String,
        importance: Float?,
        description: String?,
        now: Long,
        actor: String,
        reason: String,
    ): AssistantMemory {
        ensureRevisionAvailable(old.revision)
        requireCas(memoryDAO.updateContentFields(
            old.id, old.assistantId, content, importance?.coerceIn(0f, 1f),
            description?.let(::normalizeDescription), now, old.revision,
        ))
        recordHistory(old, "UPDATE", actor, reason, now)
        return requireNotNull(memoryDAO.getMemoryById(old.id)).toModel()
    }

    suspend fun updateImportance(
        id: String,
        importance: Float,
        now: Long = System.currentTimeMillis(),
        expectedRevision: Long? = null,
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory? = transactions.run {
        val old = findEntityByPublicId(id) ?: return@run null
        checkRevision(old, expectedRevision, actor)
        ensureRevisionAvailable(old.revision)
        requireCas(memoryDAO.updateImportanceFields(
            old.id, old.assistantId, importance.coerceIn(0f, 1f), now, old.revision,
        ))
        recordHistory(old, "IMPORTANCE", actor, reason, now)
        memoryDAO.getMemoryById(old.id)?.toModel()
    }

    /** Anchor changes advance revision, but never refresh content recency or overwrite telemetry. */
    suspend fun setAnchor(
        id: String,
        isAnchor: Boolean,
        expectedRevision: Long? = null,
        actor: String = "USER",
        reason: String = "",
    ) = transactions.run {
        val old = findEntityByPublicId(id) ?: return@run
        checkRevision(old, expectedRevision, actor)
        if (old.isAnchor == isAnchor) return@run
        ensureRevisionAvailable(old.revision)
        requireCas(memoryDAO.updateAnchor(old.id, old.assistantId, isAnchor, old.revision))
        recordHistory(old, "ANCHOR", actor, reason, System.currentTimeMillis())
    }

    /** A single UI save: no split content/anchor writes or partially committed save. */
    suspend fun updateMemoryInStore(
        storeId: String,
        id: String,
        content: String,
        importance: Float,
        description: String,
        isAnchor: Boolean,
        expectedRevision: Long,
        now: Long = System.currentTimeMillis(),
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val old = requireNotNull(findEntityInStore(storeId, id)) { "Memory record $id not found in store" }
        checkRevision(old, expectedRevision, actor)
        replaceBusiness(old, content, importance, description, isAnchor, now, "UPDATE", actor, reason)
    }

    private suspend fun replaceBusiness(
        old: MemoryEntity,
        content: String,
        importance: Float,
        description: String,
        isAnchor: Boolean,
        now: Long,
        operation: String,
        actor: String,
        reason: String,
    ): AssistantMemory {
        ensureRevisionAvailable(old.revision)
        requireCas(memoryDAO.replaceBusinessFields(
            old.id, old.assistantId, content, importance.coerceIn(0f, 1f), normalizeDescription(description),
            isAnchor, now, old.revision,
        ))
        recordHistory(old, operation, actor, reason, now)
        return requireNotNull(memoryDAO.getMemoryById(old.id)).toModel()
    }

    /** Same-content reuse is idempotent; an actual description backfill is a versioned mutation. */
    suspend fun addMemory(
        assistantId: String,
        content: String,
        importance: Float? = null,
        description: String? = null,
        now: Long = System.currentTimeMillis(),
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val normalized = content.trim()
        memoryDAO.getMemoriesOfAssistant(assistantId).firstOrNull { it.content.trim() == normalized }?.let { old ->
            if (!description.isNullOrBlank() && old.description.isBlank()) {
                ensureRevisionAvailable(old.revision)
                requireCas(memoryDAO.backfillDescription(
                    old.id, assistantId, normalizeDescription(description), old.revision,
                ))
                recordHistory(old, "BACKFILL", actor, reason, now)
                return@run requireNotNull(memoryDAO.getMemoryById(old.id)).toModel()
            }
            return@run old.toModel()
        }
        val newMemory = MemoryEntity(
            assistantId = assistantId,
            content = normalized,
            publicId = generatePublicId(),
            importance = importance?.coerceIn(0f, 1f) ?: MemoryEntity.DEFAULT_IMPORTANCE,
            description = description?.let(::normalizeDescription) ?: "",
            updatedAt = now,
        )
        val insertedId = memoryDAO.insertMemory(newMemory).toInt()
        recordHistory(newMemory.copy(id = insertedId), "CREATE", actor, reason, now)
        enforceMaxCount(assistantId, now, actor, reason)
        checkNotNull(memoryDAO.getMemoryById(insertedId)) {
            "Memory capacity exceeded: new record would be evicted"
        }.toModel()
    }

    /** User-only combined create/anchor command. Tool-facing addMemory never accepts an anchor. */
    suspend fun createMemoryInStore(
        storeId: String,
        content: String,
        importance: Float = MemoryEntity.DEFAULT_IMPORTANCE,
        description: String = "",
        isAnchor: Boolean = false,
        now: Long = System.currentTimeMillis(),
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val created = addMemory(storeId, content, importance, description, now, actor = "USER", reason = reason)
        if (isAnchor && !created.isAnchor) {
            setAnchor(created.id, true, expectedRevision = created.revision, actor = "USER", reason = reason)
        }
        requireNotNull(getMemoryInStore(storeId, created.id))
    }

    /** Reads change only scoped usage counters; consolidation never earns chat credit or recency. */
    suspend fun readMemory(
        id: String,
        now: Long = System.currentTimeMillis(),
        source: MemoryReadSource = MemoryReadSource.CHAT,
    ): AssistantMemory? = transactions.run {
        val old = findEntityByPublicId(id) ?: return@run null
        readEntity(old, now, source)
    }

    suspend fun readMemoryInStore(
        storeId: String,
        id: String,
        now: Long = System.currentTimeMillis(),
        source: MemoryReadSource = MemoryReadSource.CHAT,
    ): AssistantMemory? = transactions.run {
        val old = findEntityInStore(storeId, id) ?: return@run null
        readEntity(old, now, source)
    }

    private suspend fun readEntity(old: MemoryEntity, now: Long, source: MemoryReadSource): AssistantMemory? {
        val updated = when (source) {
            MemoryReadSource.CHAT -> memoryDAO.recordReadInStore(old.id, old.assistantId, now)
            MemoryReadSource.CONSOLIDATION -> memoryDAO.recordConsolidationReadInStore(old.id, old.assistantId, now)
        }
        if (updated == 0) return null
        return memoryDAO.getMemoryById(old.id)?.toModel()
    }

    /** Search is not a read-credit event. */
    suspend fun searchMemories(
        assistantId: String,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<AssistantMemory> {
        if (limit <= 0) return emptyList()
        val tokens = memoryQueryTokens(query)
        if (tokens.isEmpty) return emptyList()
        return rankMemoriesForSearch(
            memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }, tokens, limit,
        )
    }

    suspend fun copyMemories(
        fromAssistantId: String,
        toAssistantId: String,
        now: Long = System.currentTimeMillis(),
        actor: String = "USER",
        reason: String = "",
    ): Int = transactions.run {
        if (fromAssistantId == toAssistantId) return@run 0
        val memories = memoryDAO.getMemoriesOfAssistant(fromAssistantId)
        memories.forEach { source ->
            val copy = MemoryEntity(
                assistantId = toAssistantId, content = source.content, publicId = generatePublicId(),
                importance = source.importance, description = source.description,
                isAnchor = source.isAnchor, updatedAt = now,
            )
            val id = memoryDAO.insertMemory(copy).toInt()
            recordHistory(copy.copy(id = id), "CREATE", actor, reason, now)
        }
        enforceMaxCount(toAssistantId, now, actor, reason)
        memories.size
    }

    /** Scores choose which live records move to durable trash; they never permanently destroy content. */
    private suspend fun enforceMaxCount(assistantId: String, now: Long, actor: String, reason: String) {
        val all = memoryDAO.getMemoriesOfAssistant(assistantId)
        if (all.size <= MAX_MEMORY_COUNT) return
        val excess = all.size - MAX_MEMORY_COUNT
        all.sortedWith(compareBy<MemoryEntity> { memoryScoreBucket(it.toModel(), now) }.thenBy { it.id })
            .take(excess).forEach { moveToTrash(it, now, "EVICT", actor, reason.ifBlank { "memory quota" }) }
        Log.i(TAG, "moved $excess lowest-score memory(ies) to trash over $MAX_MEMORY_COUNT cap")
    }

    suspend fun deleteMemory(
        id: String,
        expectedRevision: Long? = null,
        actor: String = "USER",
        reason: String = "",
    ) = transactions.run {
        val old = findEntityByPublicId(id) ?: return@run
        checkRevision(old, expectedRevision, actor)
        moveToTrash(old, System.currentTimeMillis(), "DELETE", actor, reason)
    }

    suspend fun deleteMemoryInStore(
        storeId: String,
        id: String,
        expectedRevision: Long? = null,
        actor: String = "USER",
        reason: String = "",
    ): Boolean = transactions.run {
        val old = findEntityInStore(storeId, id) ?: return@run false
        checkRevision(old, expectedRevision, actor)
        moveToTrash(old, System.currentTimeMillis(), "DELETE", actor, reason)
        true
    }

    private suspend fun moveToTrash(old: MemoryEntity, now: Long, operation: String, actor: String, reason: String) {
        ensureRevisionAvailable(old.revision)
        // Insert uses ABORT, not REPLACE: conflicting tombstones must never be silently overwritten.
        trashDAO.insert(MemoryTrashEntity(
            memoryId = old.id, assistantId = old.assistantId, publicId = old.publicId,
            revision = old.revision + 1, snapshot = snapshotJson.encodeToString(old),
            actor = actor, reason = reason, deletedAt = now,
        ))
        requireCas(memoryDAO.deleteMemoryInStore(old.id, old.assistantId, old.revision))
        recordHistory(old, operation, actor, reason, now)
    }

    suspend fun listHistory(storeId: String, id: String): List<MemoryHistoryRecord> = transactions.run {
        val memoryId = findEntityInStore(storeId, id)?.id ?: trashDAO.list(storeId)
            .firstOrNull { it.publicId == id || it.memoryId.toString() == id }?.memoryId ?: return@run emptyList()
        historyDAO.list(storeId, memoryId).map { event ->
            MemoryHistoryRecord(event, decodeSnapshot(event.snapshot, storeId, memoryId, event.publicId))
        }
    }

    suspend fun listTrash(storeId: String): List<MemoryTrashRecord> = transactions.run {
        trashDAO.list(storeId).map { tombstone ->
            MemoryTrashRecord(tombstone, decodeSnapshot(
                tombstone.snapshot, storeId, tombstone.memoryId, tombstone.publicId,
            ))
        }
    }

    /** Restore an old business state as a NEW revision, preserving the live row's current telemetry. */
    suspend fun restoreRevision(
        storeId: String,
        id: String,
        targetRevision: Long,
        expectedRevision: Long,
        now: Long = System.currentTimeMillis(),
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val old = requireNotNull(findEntityInStore(storeId, id)) { "Memory record $id not found in store" }
        checkRevision(old, expectedRevision, actor)
        val event = requireNotNull(historyDAO.getRevision(storeId, old.id, targetRevision)) {
            "Memory revision $targetRevision is not available"
        }
        val snapshot = decodeSnapshot(event.snapshot, storeId, old.id, old.publicId)
        replaceBusiness(
            old, snapshot.content, snapshot.importance, snapshot.description, snapshot.isAnchor,
            now, "RESTORE_REVISION", actor, reason,
        )
    }

    /** A full library refuses restoration; restoring never evicts another record implicitly. */
    suspend fun restoreTrash(
        storeId: String,
        memoryId: Int,
        expectedRevision: Long,
        now: Long = System.currentTimeMillis(),
        actor: String = "USER",
        reason: String = "",
    ): AssistantMemory = transactions.run {
        val tombstone = requireNotNull(trashDAO.get(storeId, memoryId)) { "Memory trash record not found in store" }
        if (expectedRevision != tombstone.revision) {
            throw MemoryRevisionConflictException("Memory trash revision conflict")
        }
        ensureRevisionAvailable(tombstone.revision)
        check(memoryDAO.getMemoriesOfAssistant(storeId).size < MAX_MEMORY_COUNT) {
            "Memory capacity exceeded: restore requires a free slot"
        }
        check(memoryDAO.getMemoryById(memoryId) == null) { "Memory restore numeric id conflict" }
        // Legacy blank tokens are addressed by their distinct numeric IDs, not by the empty token.
        check(tombstone.publicId.isBlank() || !memoryDAO.publicIdExists(tombstone.publicId)) {
            "Memory restore public id conflict"
        }
        val snapshot = decodeSnapshot(tombstone.snapshot, storeId, memoryId, tombstone.publicId)
        check(tombstone.revision > snapshot.revision) { "Invalid memory trash revision" }
        val restored = snapshot.copy(revision = tombstone.revision + 1, updatedAt = now)
        memoryDAO.insertMemory(restored)
        requireCas(trashDAO.delete(storeId, memoryId, tombstone.revision))
        recordHistory(restored, "RESTORE_TRASH", actor, reason, now)
        restored.toModel()
    }

    private suspend fun recordHistory(old: MemoryEntity, operation: String, actor: String, reason: String, now: Long) {
        historyDAO.insert(MemoryHistoryEntity(
            assistantId = old.assistantId, memoryId = old.id, publicId = old.publicId,
            revision = old.revision, snapshot = snapshotJson.encodeToString(old), operation = operation,
            actor = actor, reason = reason, recordedAt = now,
        ))
        historyDAO.prune(old.assistantId, old.id, MAX_HISTORY_COUNT)
    }

    private fun decodeSnapshot(json: String, storeId: String, memoryId: Int, publicId: String): MemoryEntity {
        val snapshot = snapshotJson.decodeFromString<MemoryEntity>(json)
        check(snapshot.assistantId == storeId && snapshot.id == memoryId && snapshot.publicId == publicId) {
            "Memory snapshot identity mismatch"
        }
        return snapshot
    }

    private fun checkRevision(old: MemoryEntity, expectedRevision: Long?, actor: String) {
        if (actor != "USER" && expectedRevision == null) {
            throw MemoryRevisionConflictException("Expected memory revision is required for $actor")
        }
        if (expectedRevision != null && old.revision != expectedRevision) {
            throw MemoryRevisionConflictException(
                "Memory revision conflict: expected $expectedRevision, found ${old.revision}",
            )
        }
    }

    private fun requireCas(affected: Int) {
        if (affected != 1) throw MemoryRevisionConflictException("Memory changed concurrently or left its store")
    }

    private fun ensureRevisionAvailable(revision: Long) {
        check(revision in 1 until Long.MAX_VALUE) { "Memory revision exhausted" }
    }

    private suspend fun findEntityInStore(storeId: String, id: String): MemoryEntity? =
        findEntityByPublicId(id)?.takeIf { it.assistantId == storeId }

    /** Public token first; legacy numeric references only fall back if that token does not exist. */
    private suspend fun findEntityByPublicId(id: String): MemoryEntity? {
        memoryDAO.getMemoryByPublicId(id)?.let { return it }
        return id.toIntOrNull()?.let { memoryDAO.getMemoryById(it) }
    }

    private fun normalizeDescription(raw: String): String = raw.trim().take(MAX_DESCRIPTION_LENGTH)
}
