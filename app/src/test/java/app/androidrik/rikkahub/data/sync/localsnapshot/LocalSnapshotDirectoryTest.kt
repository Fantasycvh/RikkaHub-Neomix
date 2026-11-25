package app.androidrik.rikkahub.data.sync.localsnapshot

import app.androidrik.rikkahub.data.sync.repo.BackupDocumentAccess
import app.androidrik.rikkahub.data.sync.repo.SafDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime

class LocalSnapshotDirectoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 11, 25, 10, 0)

    @Test
    fun `publishes verified bytes and allocates a distinct name on the same second`() = runBlocking {
        val access = MemoryFolder()
        val directory = LocalSnapshotDirectory(access, access::rename)
        val input = temp.newFile().also { it.writeText("archive") }
        val first = directory.publish(input, now) {}
        val second = directory.publish(input, now) {}
        assertEquals(LocalSnapshot.fileName(now), first)
        assertEquals(LocalSnapshot.fileName(now.plusSeconds(1)), second)
        assertEquals(listOf(second, first), directory.list().map { it.name })
        val restored = temp.newFile()
        directory.stage(directory.list().first(), restored)
        assertArrayEquals(input.readBytes(), restored.readBytes())
    }

    @Test
    fun `failed readback or rename leaves existing copies intact`() = runBlocking {
        val access = MemoryFolder()
        val input = temp.newFile().also { it.writeText("archive") }
        val directory = LocalSnapshotDirectory(access, access::rename)
        val first = directory.publish(input, now) {}

        access.corruptTemporaryRead = true
        assertTrue(runCatching { directory.publish(input, now) {} }.exceptionOrNull() is IllegalStateException)
        access.corruptTemporaryRead = false
        val noRename = LocalSnapshotDirectory(access) { _, _ -> throw IOException("rename rejected") }
        assertTrue(runCatching { noRename.publish(input, now) {} }.exceptionOrNull() is IOException)
        access.returnExistingOnCreate = true
        assertTrue(runCatching { directory.publish(input, now) {} }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(first), access.children(access.rootId).map { it.name })
        assertEquals(listOf(first), directory.list().map { it.name })
    }

    private class MemoryFolder : BackupDocumentAccess {
        override val rootId = "root"
        private val files = mutableMapOf<String, ByteArray>()
        var corruptTemporaryRead = false
        var returnExistingOnCreate = false

        override fun children(parent: String): List<SafDocument> = files.map { (name, bytes) ->
            SafDocument(name, name, false, bytes.size.toLong())
        }

        override fun create(parent: String, name: String, directory: Boolean): SafDocument {
            if (returnExistingOnCreate) return children(parent).first()
            files[name] = byteArrayOf()
            return SafDocument(name, name, directory, 0)
        }

        override fun read(id: String) = files.getValue(id).let {
            (if (corruptTemporaryRead && id.endsWith(".partial")) it.reversedArray() else it).inputStream()
        }

        override fun write(id: String) = object : ByteArrayOutputStream() {
            override fun close() { files[id] = toByteArray() }
        }

        override fun delete(id: String) { files.remove(id) }

        fun rename(id: String, name: String): String {
            files[name] = requireNotNull(files.remove(id))
            return name
        }
    }
}
