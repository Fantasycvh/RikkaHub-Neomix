package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.zip.ZipFile

/** v3 pack：确定性打包、校验、引擎端「小对象进 pack / 大对象独立」与恢复。 */
class SnapshotPackingTest {
    @get:Rule val temp = TemporaryFolder()
    private val device = UUID.randomUUID().toString()
    private val store = MemoryBackupStore()
    private fun engine() = IncrementalBackupEngine(temp.root.resolve("cache"))
    private fun source(path: String, content: String) = SnapshotInput(path, temp.newFile().apply { writeText(content) })
    private fun settings() = source("settings.json", "{}")

    private suspend fun plan(inputs: List<SnapshotInput>) = engine().plan(
        store, inputs, listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, device, 58, "1",
    )

    @Test fun sameContentProducesIdenticalPackBytes() {
        val a = temp.newFile().apply { writeText("alpha") }
        val b = temp.newFile().apply { writeText("beta") }
        val shaA = fileSha256(a)
        val shaB = fileSha256(b)
        val first = SnapshotPacking.write(temp.newFolder(), listOf(shaB to b, shaA to a))
        val second = SnapshotPacking.write(temp.newFolder(), listOf(shaA to a, shaB to b))

        assertEquals(1, first.files.size)
        val key = first.files.keys.single()
        assertEquals(key, second.files.keys.single())
        assertArrayEquals(first.files.getValue(key).readBytes(), second.files.getValue(key).readBytes())
        val packSha = requireNotNull(packShaFromKey(key))
        assertEquals(packSha, first.shaToPack[shaA])
        assertEquals(packSha, first.shaToPack[shaB])
        ZipFile(first.files.getValue(key)).use { zip ->
            assertEquals(setOf(shaA, shaB), zip.entries().asSequence().map { it.name }.toSet())
        }
    }

    @Test fun verifyRejectsTamperedPackAndMissingEntry() {
        val a = temp.newFile().apply { writeText("alpha") }
        val sha = fileSha256(a)
        val pack = SnapshotPacking.write(temp.newFolder(), listOf(sha to a)).files.values.single()
        val refs = listOf<SnapshotObjectRef>(SnapshotFileEntry("settings.json", sha, a.length()))
        SnapshotPacking.verify(pack, fileSha256(pack), refs, temp.newFolder())

        val tampered = temp.newFile().apply { writeBytes(pack.readBytes().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2] + 1).toByte() }) }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotPacking.verify(tampered, fileSha256(pack), refs, temp.newFolder())
        }
        // 清单声称的条目不在 pack 里 → 拒绝（防伪造清单）。
        val otherSha = fileSha256(temp.newFile().apply { writeText("other") })
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotPacking.verify(pack, fileSha256(pack), listOf(SnapshotFileEntry("x", otherSha, 5)), temp.newFolder())
        }
    }

    @Test fun extractAllRestoresEntriesWithChecksums() {
        val files = (0 until 3).map { temp.newFile().apply { writeText("payload-$it") } }
        val shas = files.map { fileSha256(it) }
        val pack = SnapshotPacking.write(temp.newFolder(), shas.zip(files)).files.values.single()
        val refs = shas.mapIndexed { index, sha -> SnapshotFileEntry("f$index", sha, files[index].length()) }
        val targets = shas.mapIndexed { index, _ -> temp.newFile() }

        SnapshotPacking.extractAll(pack, refs, targets)
        targets.forEachIndexed { index, target -> assertEquals("payload-$index", target.readText()) }
        assertEquals(fileSha256(pack), packShaFromKey(packKeyFor(fileSha256(pack))))
    }

    @Test fun enginePacksSmallObjectsAndRestoresThem() = runBlocking<Unit> {
        val snapshot = plan(listOf(settings(), source("upload/a.txt", "hello"), source("upload/b.txt", "world"))).use { engine().commit(it) }

        assertEquals(SNAPSHOT_FORMAT_V3, snapshot.formatVersion)
        assertEquals(1, snapshot.packs.size)
        assertTrue(snapshot.files.all { it.pack != null })
        assertTrue(store.data.keys.none { it.startsWith("v3/objects/") })
        assertEquals(1, store.data.keys.count { it.startsWith("$PACK_DIR/") })

        ZipFile(engine().assemble(store, snapshot, 58)).use { zip ->
            assertEquals("{}", zip.getInputStream(zip.getEntry("settings.json")).reader().readText())
            assertEquals("hello", zip.getInputStream(zip.getEntry("upload/a.txt")).reader().readText())
            assertEquals("world", zip.getInputStream(zip.getEntry("upload/b.txt")).reader().readText())
        }
    }

    @Test fun largeObjectStaysIndependentFromPack() = runBlocking<Unit> {
        val large = source("upload/large.bin", "x".repeat(300 * 1024))
        val snapshot = plan(listOf(settings(), large)).use { engine().commit(it) }

        assertEquals(1, snapshot.packs.size)
        val largeRef = snapshot.files.single { it.path == "upload/large.bin" }
        assertNull(largeRef.pack)
        assertNotNull(store.data[largeRef.storageKey])
        assertTrue(store.data.keys.count { it.startsWith("v3/objects/") } == 1)
        ZipFile(engine().assemble(store, snapshot, 58)).use { zip ->
            assertEquals(300L * 1024, zip.getEntry("upload/large.bin").size)
        }
    }

    @Test fun unchangedBackupReusesWholePackWithoutUpload() = runBlocking<Unit> {
        val inputs = listOf(settings(), source("upload/a.txt", "hello"))
        val first = plan(inputs).use { engine().commit(it) }
        val packBefore = store.data.keys.single { it.startsWith("$PACK_DIR/") }
        plan(inputs).use { second ->
            assertEquals(0L, second.uploadBytes)
            assertEquals(first.packs, second.snapshot.packs)
            engine().commit(second)
        }
        assertTrue(store.data.containsKey(packBefore))
    }

    @Test fun changedSmallObjectGoesToNewPackAndOldPackStaysReferenced() = runBlocking<Unit> {
        plan(listOf(settings(), source("upload/a.txt", "hello"), source("upload/b.txt", "world"))).use { engine().commit(it) }
        val packsBefore = store.data.keys.filter { it.startsWith("$PACK_DIR/") }.toSet()

        plan(listOf(settings(), source("upload/a.txt", "changed"), source("upload/b.txt", "world"))).use { second ->
            assertEquals(1, second.snapshot.packs.size - packsBefore.size)
            // 未变对象继续引用旧 pack，只有改动的对象进新 pack。
            val changed = second.snapshot.files.single { it.path == "upload/a.txt" }
            val unchanged = second.snapshot.files.single { it.path == "upload/b.txt" }
            val changedPack = requireNotNull(changed.pack).let(::packKeyFor)
            assertFalse(changedPack in packsBefore)
            assertEquals(unchanged.pack, second.snapshot.files.single { it.path == "settings.json" }.pack)
            engine().commit(second)
        }
        assertTrue(store.data.keys.containsAll(packsBefore))
        assertFalse(store.data.isEmpty())
    }

    @Test fun manifestRejectsPackPointerOnLegacyFormat() = runBlocking<Unit> {
        val snapshot = plan(listOf(settings(), source("upload/a.txt", "hello"))).use { engine().commit(it) }
        assertThrows(IllegalArgumentException::class.java) {
            validateSnapshot(snapshot.copy(formatVersion = SNAPSHOT_FORMAT_V2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateSnapshot(snapshot.copy(files = snapshot.files.map { it.copy(pack = "not-a-hash") }))
        }
        // 只有 <dir>/<sha256>.zip 形状的键才是 pack；非 pack 路径返回 null。
        assertNull(packShaFromKey("v3/objects/aa/deadbeef"))
        assertNull(packShaFromKey("bad"))
    }
}
