package app.androidrik.rikkahub.data.repository

import androidx.room.withTransaction
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.db.entity.MemoryFileVersionEntity
import kotlin.uuid.Uuid

class MemoryFileRepository(private val database: AppDatabase) {
    private val dao = database.memoryFileDao()
    private val versions = database.memoryFileVersionDao()

    fun observe(storeId: String) = dao.observe(storeId)

    suspend fun list(storeId: String) = dao.list(storeId)
    suspend fun canCreate(storeId: String) = dao.count(storeId) < MAX_MEMORY_FILES

    suspend fun read(storeId: String, path: String) = dao.get(storeId, normalizeMemoryFilePath(path))

    fun observeHistory(storeId: String, fileId: String) = versions.observeHistory(storeId, fileId)

    fun observeTrash(storeId: String) = versions.observeTrash(storeId)

    suspend fun readVersion(storeId: String, versionId: Long) = versions.get(storeId, versionId)

    suspend fun write(
        storeId: String,
        path: String,
        expectedRevision: String?,
        transform: (String) -> String,
    ): MemoryFileEntity = writeFile(storeId, path, expectedRevision, pin = null, transform = transform)

    internal suspend fun writeFile(
        storeId: String,
        path: String,
        expectedRevision: String?,
        pin: Boolean?,
        transform: (String) -> String,
    ): MemoryFileEntity = database.withTransaction {
        val key = normalizeMemoryFilePath(path)
        val current = dao.get(storeId, key)
        if (current?.revision != expectedRevision) throw MemoryFileException(MemoryFileError.CHANGED)
        val content = transform(current?.content.orEmpty())
        if (content.toByteArray().size > MAX_MEMORY_FILE_BYTES) throw MemoryFileException(MemoryFileError.TOO_LARGE)
        val saved = MemoryFileEntity(
            storeId, key, content, newId(), System.currentTimeMillis(), anchorId(pin, current),
            fileId = current?.identity ?: newId(),
        )
        if (current == null) {
            if (!canCreate(storeId)) throw MemoryFileException(MemoryFileError.LIBRARY_FULL)
            dao.insert(saved)
        } else {
            update(current, saved)
        }
        saved
    }

    suspend fun delete(storeId: String, path: String, revision: String) = database.withTransaction {
        val key = normalizeMemoryFilePath(path)
        val current = dao.get(storeId, key)
        if (current?.revision != revision || dao.delete(storeId, key, revision) != 1) {
            throw MemoryFileException(MemoryFileError.CHANGED)
        }
        recordVersion(current, System.currentTimeMillis(), inTrash = true)
    }

    suspend fun move(
        storeId: String,
        source: String,
        destination: String,
        expectedRevision: String,
        content: String? = null,
        pin: Boolean? = null,
    ): MemoryFileEntity = database.withTransaction {
        val from = normalizeMemoryFilePath(source)
        val to = normalizeMemoryFilePath(destination)
        val current = dao.get(storeId, from)
        if (current?.revision != expectedRevision || (from != to && dao.get(storeId, to) != null)) {
            throw MemoryFileException(MemoryFileError.CHANGED)
        }
        val body = content ?: current.content
        if (body.toByteArray().size > MAX_MEMORY_FILE_BYTES) throw MemoryFileException(MemoryFileError.TOO_LARGE)
        val saved = current.copy(
            path = to, content = body, revision = newId(), updatedAt = System.currentTimeMillis(),
            anchorId = anchorId(pin, current), fileId = current.identity,
        )
        update(current, saved)
        saved
    }

    private suspend fun update(current: MemoryFileEntity, saved: MemoryFileEntity) {
        val updated = dao.move(
            saved.storeId, current.path, saved.path, current.revision, saved.content, saved.revision,
            saved.updatedAt, saved.anchorId, saved.identity,
        )
        if (updated != 1) {
            throw MemoryFileException(MemoryFileError.CHANGED)
        }
        recordVersion(current, saved.updatedAt, inTrash = false)
    }

    suspend fun restoreVersion(storeId: String, versionId: Long, expectedRevision: String): MemoryFileEntity =
        database.withTransaction {
            val version = requireVersion(storeId, versionId, inTrash = false)
            val current = dao.getById(storeId, version.fileId)
            if (current?.revision != expectedRevision) throw MemoryFileException(MemoryFileError.CHANGED)
            val restored = version.asNewRevision()
            requireFreePath(restored, except = current.path)
            update(current, restored)
            restored
        }

    suspend fun restoreTrash(storeId: String, versionId: Long): MemoryFileEntity = database.withTransaction {
        val version = requireVersion(storeId, versionId, inTrash = true)
        val restored = version.asNewRevision()
        requireFreePath(restored)
        if (dao.getById(storeId, version.fileId) != null) throw MemoryFileException(MemoryFileError.CHANGED)
        if (!canCreate(storeId)) throw MemoryFileException(MemoryFileError.LIBRARY_FULL)
        dao.insert(restored)
        if (versions.markRestored(storeId, versionId) != 1) throw MemoryFileException(MemoryFileError.CHANGED)
        versions.pruneHistory(storeId, version.fileId, MAX_HISTORY_COUNT)
        restored
    }

    suspend fun permanentlyDelete(storeId: String, versionId: Long) = database.withTransaction {
        val version = requireVersion(storeId, versionId, inTrash = true)
        if (dao.getById(storeId, version.fileId) != null) throw MemoryFileException(MemoryFileError.CHANGED)
        versions.deleteFileVersions(storeId, version.fileId)
    }

    private suspend fun requireVersion(storeId: String, versionId: Long, inTrash: Boolean): MemoryFileVersionEntity =
        versions.get(storeId, versionId)?.takeIf { it.inTrash == inTrash }
            ?: throw MemoryFileException(MemoryFileError.CHANGED)

    private suspend fun requireFreePath(file: MemoryFileEntity, except: String? = null) {
        val path = normalizeMemoryFilePath(file.path)
        if (path != file.path) throw MemoryFileException(MemoryFileError.INVALID_PATH)
        if (path != except && dao.get(file.storeId, path) != null) {
            throw MemoryFileException(MemoryFileError.PATH_OCCUPIED)
        }
        if (file.content.toByteArray().size > MAX_MEMORY_FILE_BYTES) throw MemoryFileException(MemoryFileError.TOO_LARGE)
    }

    private fun MemoryFileVersionEntity.asNewRevision() =
        MemoryFileEntity(storeId, path, content, newId(), System.currentTimeMillis(), anchorId, fileId)

    private suspend fun recordVersion(file: MemoryFileEntity, now: Long, inTrash: Boolean) {
        versions.insert(MemoryFileVersionEntity(
            storeId = file.storeId, fileId = file.identity, path = file.path, revision = file.revision,
            content = file.content, anchorId = file.anchorId, recordedAt = now, inTrash = inTrash,
        ))
        if (!inTrash) versions.pruneHistory(file.storeId, file.identity, MAX_HISTORY_COUNT)
    }

    private fun anchorId(pin: Boolean?, current: MemoryFileEntity?): String? = when (pin) {
        true -> current?.anchorId ?: newId()
        false -> null
        null -> current?.anchorId
    }
    suspend fun deleteStore(storeId: String) = database.withTransaction {
        dao.deleteStore(storeId)
        versions.deleteStore(storeId)
    }

    private fun newId(): String = Uuid.random().toString()
}

internal const val MAX_MEMORY_FILE_BYTES = 48 * 1024
private const val MAX_MEMORY_FILES = 200
private const val MAX_HISTORY_COUNT = 20

internal enum class MemoryFileError(val detail: String) {
    INVALID_PATH("Use a Markdown path such as /topics/example.md."),
    CHANGED("The memory file already exists or its revision changed. Read it again."),
    PATH_OCCUPIED("The original path is occupied. Move the current file before restoring."),
    TOO_LARGE("Memory files are limited to 48 KiB."),
    LIBRARY_FULL("This library already contains 200 memory files."),
}

internal class MemoryFileException(val reason: MemoryFileError) : IllegalStateException(reason.detail)

internal fun normalizeMemoryFilePath(raw: String): String {
    val path = "/" + raw.trim().removePrefix("/")
    if (path.length > 128 || !path.endsWith(".md") ||
        path.any { it.isISOControl() || it == '\\' } ||
        path.drop(1).split('/').any { it.isBlank() || it != it.trim() || it.startsWith(".") }
    ) throw MemoryFileException(MemoryFileError.INVALID_PATH)
    return path
}
