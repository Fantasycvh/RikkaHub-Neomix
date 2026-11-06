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
        // 目录列举在一次操作内缓存；provider 故障必须从「尚未列举过的路径」冒出来，不能被缓存当成 missing。
        val fresh = SafBackupObjectStore(access, "fake-tree")
        assertThrows(java.io.IOException::class.java) { runBlocking { fresh.stat("v3/missing") } }
    }

    @Test fun directoryListingIsCachedWithinOperation() = runBlocking<Unit> {
        store.ensureDir("v3/objects/aa")
        store.listChildren("v3/objects/aa")
        val calls = access.childrenCalls
        assertNull(store.stat("v3/objects/aa/missing"))
        assertNull(store.stat("v3/objects/other"))
        // 已列举过的目录不再回源：这是把对象级 O(N) 次 provider 往返压回目录数的关键。
        assertEquals(calls, access.childrenCalls)
    }

    @Test fun freshStatBypassesCache() = runBlocking<Unit> {
        store.ensureDir("v3/objects/aa")
        val calls = access.childrenCalls
        assertNull(store.statFresh("v3/objects/aa/missing"))
        assertTrue(access.childrenCalls > calls)
    }

    @Test fun putDoesNotRelistParentAfterCreate() = runBlocking<Unit> {
        store.ensureDir("dir")
        store.listChildren("dir")
        val calls = access.childrenCalls
        store.put("dir/file", source())
        // put 只回查新建文档本身，不再整层列举父目录做冲突校验。
        assertEquals(calls, access.childrenCalls)
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

    @Test fun listAllWalksNestedDirectoriesAndReturnsOnlyFiles() = runBlocking<Unit> {
        store.ensureDir("v3/objects/aa")
        store.ensureDir("v3/objects/bb")
        store.ensureDir("v3/objects/cc/deep")
        store.put("v3/objects/aa/one", source())
        store.put("v3/objects/bb/two", source())
        store.put("v3/objects/cc/deep/three", source())

        val listed = store.listAll("v3/objects")
        assertEquals(
            setOf("v3/objects/aa/one", "v3/objects/bb/two", "v3/objects/cc/deep/three"),
            listed.map { it.key }.toSet(),
        )
        assertFalse(listed.any { it.directory })
        assertTrue(store.listAll("missing").isEmpty())
    }

    @Test fun overwriteRewritesMutableMetadataWhilePutsStayImmutable() = runBlocking<Unit> {
        store.ensureDir("v3")
        store.putOverwrite("v3/gc.json", temp.newFile().apply { writeText("lease-1") })
        store.putOverwrite("v3/gc.json", temp.newFile().apply { writeText("lease-2-longer") })

        val output = temp.newFile()
        store.get("v3/gc.json", output, 100)
        assertEquals("lease-2-longer", output.readText())
        assertEquals(1, access.content.size)
        // 内容寻址对象仍然是不可变的：put 改内容必须失败。
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.put("v3/gc.json", temp.newFile().apply { writeText("lease-3") }) }
        }
    }

    @Test fun overwriteCreatesMissingMetadataFile() = runBlocking<Unit> {
        store.ensureDir("v3")
        store.putOverwrite("v3/gc.json", temp.newFile().apply { writeText("lease-1") })
        val output = temp.newFile()
        store.get("v3/gc.json", output, 100)
        assertEquals("lease-1", output.readText())
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
    var childrenCalls = 0
    override fun children(parent: String): List<SafDocument> {
        childrenCalls++
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
