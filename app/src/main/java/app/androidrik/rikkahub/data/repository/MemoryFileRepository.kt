package app.androidrik.rikkahub.data.repository

import androidx.room.withTransaction
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import kotlin.uuid.Uuid

class MemoryFileRepository(private val database: AppDatabase) {
    private val dao = database.memoryFileDao()

    fun observe(storeId: String) = dao.observe(storeId)

    suspend fun list(storeId: String) = dao.list(storeId)
    suspend fun canCreate(storeId: String) = dao.count(storeId) < MAX_MEMORY_FILES

    suspend fun read(storeId: String, path: String) = dao.get(storeId, normalizeMemoryFilePath(path))

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
            storeId, key, content, Uuid.random().toString(), System.currentTimeMillis(), anchorId(pin, current),
        )
        if (current == null) {
            if (!canCreate(storeId)) throw MemoryFileException(MemoryFileError.LIBRARY_FULL)
            dao.insert(saved)
        } else {
            update(current, saved)
        }
        saved
    }

    suspend fun delete(storeId: String, path: String, revision: String) {
        if (dao.delete(storeId, normalizeMemoryFilePath(path), revision) != 1) {
            throw MemoryFileException(MemoryFileError.CHANGED)
        }
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
        val saved = MemoryFileEntity(
            storeId, to, body, Uuid.random().toString(), System.currentTimeMillis(), anchorId(pin, current),
        )
        update(current, saved)
        saved
    }

    private suspend fun update(current: MemoryFileEntity, saved: MemoryFileEntity) {
        if (dao.move(saved.storeId, current.path, saved.path, current.revision, saved.content, saved.revision,
                saved.updatedAt, saved.anchorId) != 1
        ) {
            throw MemoryFileException(MemoryFileError.CHANGED)
        }
    }

    private fun anchorId(pin: Boolean?, current: MemoryFileEntity?): String? = when (pin) {
        true -> current?.anchorId ?: Uuid.random().toString()
        false -> null
        null -> current?.anchorId
    }
    suspend fun deleteStore(storeId: String) = dao.deleteStore(storeId)
}

internal const val MAX_MEMORY_FILE_BYTES = 48 * 1024
private const val MAX_MEMORY_FILES = 200

internal enum class MemoryFileError(val detail: String) {
    INVALID_PATH("Use a Markdown path such as /topics/example.md."),
    CHANGED("The memory file already exists or its revision changed. Read it again."),
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
