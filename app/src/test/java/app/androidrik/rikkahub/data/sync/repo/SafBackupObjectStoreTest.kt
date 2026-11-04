package app.androidrik.rikkahub.data.sync.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class SafBackupObjectStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val access = FakeBackupDocuments()
    private val store = SafBackupObjectStore(access, "fake-tree")
    private fun source() = temp.newFile().apply { writeText("captured content") }

    @Test fun nestedDirectoryRoundTripAndUnknownSize() = runBlocking<Unit> {
        store.ensureDir("v3/objects/ab")
        val file = source()
        store.put("v3/objects/ab/hash", file)
        access.unknownSize = true
        assertNull(store.stat("v3/objects/ab/hash")?.bytes)
        val output = temp.newFile()
        store.get("v3/objects/ab/hash", output, 100)
        assertEquals(file.readText(), output.readText())
        assertEquals(listOf("v3/objects/ab/hash"), store.listChildren("v3/objects/ab").map { it.key })
    }

    @Test fun missingIsDifferentFromQueryFailure() = runBlocking<Unit> {
        assertNull(store.stat("missing"))
        access.failQuery = true
        assertThrows(java.io.IOException::class.java) { runBlocking { store.stat("missing") } }
    }

    @Test fun providerAutomaticRenameFailsBeforeWrite() = runBlocking<Unit> {
        access.rename = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.put("requested", source()) } }
        assertTrue(access.content.isEmpty())
    }

    @Test fun duplicateNamesAreNeverResolvedArbitrarily() = runBlocking<Unit> {
        access.duplicate = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.listChildren("") } }
    }

    @Test fun closeFailurePropagates() = runBlocking<Unit> {
        access.failClose = true
        assertThrows(java.io.IOException::class.java) { runBlocking { store.put("partial", source()) } }
        assertNull(store.stat("partial"))
    }

    @Test fun existingDifferentObjectIsNeverTruncated() = runBlocking<Unit> {
        val file = source()
        store.put("immutable", file)
        file.writeText("changed")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.put("immutable", file) } }
        val output = temp.newFile()
        store.get("immutable", output, 100)
        assertEquals("captured content", output.readText())
    }

    @Test fun sameObjectPutIsIdempotent() = runBlocking<Unit> {
        val file = source()
        store.put("immutable", file); store.put("immutable", file)
        assertEquals(1, access.content.size)
    }

    @Test fun unknownLengthStreamStillHonorsBudget() = runBlocking<Unit> {
        store.put("blob", source())
        access.unknownSize = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.get("blob", temp.newFile(), 1) } }
    }

    @Test fun directoryFileTypeConflictIsRejected() = runBlocking<Unit> {
        store.put("v3", source())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.ensureDir("v3/objects") } }
    }

    @Test fun contentUriPathTraversalIsRejected() = runBlocking<Unit> {
        listOf("../file", "/file", "v3//file", "v3\\file").forEach {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.stat(it) } }
        }
    }
}

internal class FakeBackupDocuments : BackupDocumentAccess {
    override val rootId = "root"
    private val children = mutableMapOf(rootId to mutableListOf<SafDocument>())
    val content = mutableMapOf<String, ByteArray>()
    var failQuery = false
    var rename = false
    var duplicate = false
    var failClose = false
    var unknownSize = false
    override fun children(parent: String): List<SafDocument> {
        if (failQuery) throw java.io.IOException("Provider disconnected")
        if (duplicate) return listOf(SafDocument("1", "same", false, null), SafDocument("2", "same", false, null))
        return children.getValue(parent).map { it.copy(bytes = if (unknownSize) null else content[it.id]?.size?.toLong()) }
    }
    override fun create(parent: String, name: String, directory: Boolean): SafDocument {
        val id = java.util.UUID.randomUUID().toString()
        val doc = SafDocument(id, if (rename) "$name(1)" else name, directory, null)
        children.getValue(parent).add(doc)
        if (directory) children[id] = mutableListOf()
        return doc
    }
    override fun read(id: String): InputStream = content.getValue(id).inputStream()
    override fun write(id: String): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            content[id] = toByteArray()
            if (failClose) throw java.io.IOException("Volume removed at close")
            super.close()
        }
    }
    override fun delete(id: String) { children.values.forEach { it.removeAll { it.id == id } }; content.remove(id) }
}
