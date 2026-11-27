package app.androidrik.rikkahub.data.sync.lan

import android.app.Application
import androidx.room.withTransaction
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.sync.BackupArchiveEngine
import app.androidrik.rikkahub.data.sync.PendingRestoreStore
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.flow.first
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

internal class LanSyncInventory(
    val manifest: LanManifest,
    val files: Map<String, File>,
    val directory: File,
) : Closeable {
    override fun close() {
        directory.deleteRecursively()
    }
}

internal class LanSyncData(
    private val app: Application,
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val settings: SettingsStore,
    private val archives: BackupArchiveEngine,
    private val isChatBusy: () -> Boolean,
    private val onChanged: suspend (Collection<Uuid>) -> Unit,
) {
    private val dao = database.conversationDao()
    private val tombstones = database.lanConversationTombstoneDao()
    private val families = LanFamilyStore(app, settings, database)

    suspend fun capture(fingerprint: String, name: String, now: Long): LanSyncInventory {
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "lan-inventory-").toFile()
        try {
            val entries = ArrayList<LanManifestObject>()
            val files = linkedMapOf<String, File>()
            for (id in dao.getAllIds().sorted()) {
                val conversation = database.withTransaction { conversations.getConversationById(Uuid.parse(id)) } ?: continue
                val source = captureLanConversation(conversation, app.filesDir, File(directory, id), JsonInstant)
                entries += source.manifest
                source.objectFiles.forEach { (digest, file) -> files.putIfAbsent(digest, file) }
            }
            val cutoff = now - LAN_TOMBSTONE_RETENTION_MS
            val deleted = database.withTransaction {
                tombstones.prune(cutoff)
                tombstones.listSince(cutoff)
            }.sortedBy { it.conversationId }.map { LanTombstone("conversation:${it.conversationId}", it.deletedAt) }
            val device = LanManifestDevice(fingerprint, name, AppDatabase.SCHEMA_VERSION, LAN_FORMAT_VERSION)
            val extra = families.capture(directory, now)
            files.putAll(extra.files)
            return LanSyncInventory(LanManifest(LAN_PROTOCOL, device, conversations = entries,
                assistants = extra.entries.filter { it.key.startsWith("assistant:") },
                families = extra.entries.filterNot { it.key.startsWith("assistant:") },
                tombstones = deleted + extra.tombstones), files, directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    suspend fun apply(
        local: LanSyncInventory,
        remote: LanManifest,
        plan: LanMergePlan,
        staging: LanObjectStore,
    ): LanSyncReport = DataOperationLock.runWaiting(DataOperation.RESTORE) {
        requireSupportedFamilies(remote)
        check(!PendingRestoreStore.hasPending(app.filesDir) && !PendingRestoreStore.hasApplied(app.filesDir))
        check(!isChatBusy()) { "A conversation is generating" }
        val familyReport = families.apply(local, plan, staging)
        val plan = plan.copy(
            pull = plan.pull.filter { it.key.startsWith("conversation:") },
            deleteLocal = plan.deleteLocal.filter { it.key.startsWith("conversation:") },
            tombstones = plan.tombstones.filter { it.key.startsWith("conversation:") },
            conflicts = plan.conflicts.filter { it.winner.key.startsWith("conversation:") },
        )
        val captured = local.manifest.conversations.associateBy { it.key }
        val capturedMarks = local.manifest.tombstones.associateBy { it.key }
        val conflicts = plan.conflicts.associateBy { it.winner.key }
        val replacements = linkedMapOf<String, Conversation>()
        suspend fun materialize(entry: LanManifestObject): Conversation {
            return materializeLanConversation(entry, app.filesDir, JsonInstant) { digest ->
                staging.completed(digest) ?: local.files[digest]
            }
        }
        for (entry in plan.pull.filterNot { it.key in conflicts }) replacements[entry.key] = materialize(entry)
        for ((key, conflict) in conflicts) {
            val winner = materialize(conflict.winner)
            val merged = mergeLanConflict(winner, materialize(conflict.loser), conflict.loser.sha256)
            if (captured[key] != conflict.winner || merged != winner) replacements[key] = merged
        }
        val changes = replacements.keys + plan.deleteLocal.map { it.key }
        val marks = plan.tombstones.filter { it != capturedMarks[it.key] }
        if (changes.isEmpty() && marks.isEmpty()) return@runWaiting familyReport
        if (changes.any(captured::containsKey)) archives.writeSafetyBackup()
        val needed = replacements.values.map { it.assistantId }.toSet()
        if (needed.isNotEmpty()) {
            val present = settings.settingsFlow.first { !it.init }.assistants.mapTo(hashSetOf()) { it.id }
            if (!present.containsAll(needed)) settings.update { current ->
                val known = current.assistants.mapTo(hashSetOf()) { it.id }
                val placeholders = (needed - known).sortedBy { it.toString() }.map {
                    Assistant(id = it, name = app.getString(R.string.lan_sync_imported_assistant))
                }
                if (placeholders.isEmpty()) current else current.copy(assistants = current.assistants + placeholders)
            }
        }
        val verification = File(local.directory, ".verify")
        val affected = changes.map(::conversationId)
        val report = database.withTransaction {
            check(!isChatBusy()) { "A conversation is generating" }
            val current = (changes + marks.map { it.key }).associateWith { key ->
                val id = conversationId(key)
                val row = conversations.getConversationById(id)
                val live = row?.let { captureLanConversation(it, app.filesDir, verification, JsonInstant).manifest }
                check(live == captured[key]) { "Conversations changed during LAN sync" }
                val currentMark = tombstones.time(id.toString())
                check(currentMark == capturedMarks[key]?.deletedAt) { "A deletion changed during LAN sync" }
                row
            }
            for ((key, replacement) in replacements) conversations.applyLanConversation(replacement, current[key])
            for (mark in plan.deleteLocal) {
                val row = requireNotNull(current[mark.key])
                conversations.deleteConversation(row, deleteFiles = false, propagatedAt = mark.deletedAt)
            }
            for (mark in marks) conversations.markLanDeletion(conversationId(mark.key).toString(), mark.deletedAt)
            val added = replacements.keys.count { current[it] == null }
            LanSyncReport(added, replacements.size - added, plan.deleteLocal.size, conflicts.size)
        }
        onChanged(affected)
        LanSyncReport(report.added + familyReport.added, report.updated + familyReport.updated,
            report.deleted + familyReport.deleted, report.conflicts + familyReport.conflicts)
    }
}

internal fun requireSupportedFamilies(manifest: LanManifest) {
    require(manifest.objects.all { it.key.substringBefore(':') in setOf("conversation", "assistant", "lorebook", "skill", "workspace") })
}

private fun conversationId(key: String): Uuid = Uuid.parse(key.removePrefix("conversation:"))
