package app.androidrik.rikkahub.data.sync.localsnapshot

import app.androidrik.rikkahub.data.sync.BackupPhase
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.ProgressThrottle
import app.androidrik.rikkahub.data.sync.repo.BackupDocumentAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

internal class LocalSnapshotDirectory(
    private val access: BackupDocumentAccess,
    private val rename: (id: String, name: String) -> String,
) {
    fun list(): List<LocalSnapshot> = access.children(access.rootId).mapNotNull { doc ->
        if (doc.directory) null else LocalSnapshot.fromDocument(doc.id, doc.name, doc.bytes)
    }.sortedByDescending { it.name }

    suspend fun publish(archive: File, at: LocalDateTime, report: (BackupProgress) -> Unit): String {
        val existing = access.children(access.rootId)
        val existingNames = existing.mapTo(hashSetOf()) { it.name }
        val existingIds = existing.mapTo(hashSetOf()) { it.id }
        val name = generateSequence(at) { it.plusSeconds(1) }.map(LocalSnapshot::fileName).first { it !in existingNames }
        val temporaryName = "rikka-snapshot-${UUID.randomUUID()}.partial"
        val temporary = access.create(access.rootId, temporaryName, directory = false)
        check(temporary.id !in existingIds) { "The backup folder returned an existing document" }
        var id = temporary.id
        var published = false
        try {
            check(temporary.name == temporaryName && !temporary.directory) { "Cannot create a temporary backup file" }
            val bytes = archive.length()
            check(bytes > 0) { "Empty backup archive" }
            val throttle = ProgressThrottle()
            val expected = archive.inputStream().use { input ->
                access.write(id).use { output ->
                    transfer(input, output) { copied ->
                        val progress = BackupProgress(BackupPhase.COMMITTING, copied, bytes)
                        if (throttle.shouldEmit(progress)) report(progress)
                    }
                }
            }
            val actual = access.read(id).use { transfer(it, null) }
            check(expected.first == bytes && actual.first == bytes && expected.second.contentEquals(actual.second)) {
                "Backup copy verification failed"
            }
            currentCoroutineContext().ensureActive()
            val renamedId = rename(id, name)
            check(renamedId !in existingIds) { "The backup folder returned an existing document" }
            id = renamedId
            check(access.children(access.rootId).any { it.id == id && it.name == name }) {
                "The backup folder provider changed the requested filename"
            }
            published = true
            return name
        } finally {
            if (!published) runCatching { access.delete(id) }
        }
    }

    fun delete(snapshot: LocalSnapshot) = access.delete(snapshot.id)

    suspend fun stage(snapshot: LocalSnapshot, target: File) {
        val (bytes, _) = access.read(snapshot.id).use { input ->
            target.outputStream().use { output -> transfer(input, output) }
        }
        check(bytes > 0 && (snapshot.bytes == null || bytes == snapshot.bytes)) { "Incomplete backup archive" }
    }

    private suspend fun transfer(input: InputStream, output: OutputStream?, onBytes: (Long) -> Unit = {}): Pair<Long, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output?.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            copied += count
            onBytes(copied)
        }
        return copied to digest.digest()
    }
}
