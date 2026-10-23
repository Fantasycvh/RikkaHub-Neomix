package app.androidrik.rikkahub.data.files

import app.androidrik.rikkahub.data.db.dao.ManagedFileDAO
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.repository.FilesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManagedFileBatchOperationsTest {
    private fun file(id: Long = 1) = ManagedFileEntity(id, "upload", "upload/uuid-$id.pdf", "original.pdf", "application/pdf", 123, 1, 1)

    private class FakeDao(initial: List<ManagedFileEntity>) : ManagedFileDAO {
        val rows = initial.associateBy { it.id }.toMutableMap()
        override suspend fun insert(file: ManagedFileEntity): Long { rows[file.id] = file; return file.id }
        override suspend fun update(file: ManagedFileEntity) { rows[file.id] = file }
        override suspend fun renameDisplayName(id: Long, displayName: String, updatedAt: Long): Int {
            val row = rows[id] ?: return 0
            rows[id] = row.copy(displayName = displayName, updatedAt = updatedAt)
            return 1
        }
        override suspend fun getById(id: Long) = rows[id]
        override suspend fun getByPath(relativePath: String) = rows.values.firstOrNull { it.relativePath == relativePath }
        override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = MutableStateFlow(rows.values.filter { it.folder == folder })
        override suspend fun deleteById(id: Long) = if (rows.remove(id) != null) 1 else 0
        override suspend fun deleteByPath(relativePath: String) = rows.values.firstOrNull { it.relativePath == relativePath }?.let { deleteById(it.id) } ?: 0
        override suspend fun deleteByFolder(folder: String): Int {
            val ids = rows.values.filter { it.folder == folder }.map { it.id }
            ids.forEach { rows.remove(it) }
            return ids.size
        }
    }

    @Test fun `rename changes only metadata and preserves chat attachment physical path`() = runBlocking {
        val original = file()
        val dao = FakeDao(listOf(original))
        assertTrue(FilesRepository(dao).rename(original.id, "  renamed.pdf  "))
        val renamed = dao.rows.getValue(original.id)
        assertEquals("renamed.pdf", renamed.displayName)
        assertEquals(original.relativePath, renamed.relativePath)
        assertEquals(original, renamed.copy(displayName = original.displayName, updatedAt = original.updatedAt))
    }

    @Test fun `duplicate display names are valid because physical paths remain distinct`() = runBlocking {
        val dao = FakeDao(listOf(file(1), file(2)))
        val repo = FilesRepository(dao)
        assertTrue(repo.rename(1, "same.pdf"))
        assertTrue(repo.rename(2, "same.pdf"))
        assertEquals(2, dao.rows.values.map { it.relativePath }.distinct().size)
    }

    @Test fun `invalid labels are rejected and missing ids return false`() = runBlocking {
        val dao = FakeDao(listOf(file()))
        val repo = FilesRepository(dao)
        for (name in listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb")) {
            try { repo.rename(1, name); fail("Accepted invalid name: $name") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("original.pdf", dao.rows.getValue(1).displayName)
        assertFalse(repo.rename(999, "missing.pdf"))
    }

    @Test fun `batch reports every failure and continues subsequent items`() = runBlocking {
        val visited = mutableListOf<Int>()
        val results = runManagedFileOperations(listOf(1, 2, 3), { it.toString() }, { "file$it" }) {
            visited += it
            if (it == 2) error("denied")
        }
        assertEquals(listOf(1, 2, 3), visited)
        assertNull(results[0].error)
        assertEquals(ManagedFileOperationResult("2", "file2", "denied"), results[1])
        assertNull(results[2].error)
    }

    @Test fun `metadata lookup failures are item failures and do not abort batch`() = runBlocking {
        val results = runManagedFileOperations(listOf(1, 2), { it.toString() }, {
            if (it == 1) error("provider denied metadata") else "second"
        }) { }
        assertEquals("1", results[0].name)
        assertEquals("provider denied metadata", results[0].error)
        assertNull(results[1].error)
    }

    @Test fun `cancellation propagates instead of being converted to item failure`() = runBlocking {
        val visited = mutableListOf<Int>()
        try {
            runManagedFileOperations(listOf(1, 2, 3), { it.toString() }, { "file$it" }) {
                visited += it
                if (it == 2) throw CancellationException("cancel")
            }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(listOf(1, 2), visited)
    }
}
