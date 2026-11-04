package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** 保留策略 + 未引用对象 GC（P2 后续阶段）。 */
class IncrementalBackupRetentionTest {
    @get:Rule val temp = TemporaryFolder()
    private val deviceA = UUID.randomUUID().toString()
    private val deviceB = UUID.randomUUID().toString()
    private val store = MemoryBackupStore()
    private fun engine() = IncrementalBackupEngine(temp.root.resolve("cache"))

    private fun settingsInput() = SnapshotInput("settings.json", temp.newFile().apply { writeText("{}") })

    private fun tableObject(name: String, rowId: String = UUID.randomUUID().toString()): SnapshotDbObjectInput {
        val file = temp.newFile()
        DbObjectPayloadWriter(file, tableObjectKey(name)).use { writer ->
            writer.start(listOf(DbTableSchema(name, listOf("id", "content"))))
            writer.row(name, listOf(dbText(rowId), dbText("value")))
        }
        return SnapshotDbObjectInput(tableObjectKey(name), file)
    }

    private suspend fun save(
        deviceId: String = deviceA,
        objects: List<SnapshotDbObjectInput>,
        dbVersion: Int = 58,
    ): BackupSnapshotManifest = engine().plan(
        store, listOf(settingsInput()), listOf(BackupItem.FILES, BackupItem.DATABASE),
        SnapshotDatabaseState.PRESENT, deviceId, dbVersion, "1", objects,
    ).use { engine().commit(it) }

    @Test fun pruneKeepsNewestSnapshotsForThisDeviceOnly() = runBlocking<Unit> {
        val ids = ArrayList<String>()
        repeat(4) {
            Thread.sleep(5)
            ids += save(objects = listOf(tableObject("folderentity"))).snapshotId
        }
        Thread.sleep(5)
        val otherDevice = save(deviceId = deviceB, objects = listOf(tableObject("assistantentity")))

        val result = engine().pruneSnapshots(store, deviceA, keep = 2)
        assertEquals(2, result.deletedSnapshots)
        assertEquals(2, result.keptSnapshots)

        val remaining = engine().list(store).map { it.snapshotId }.toSet()
        assertEquals(setOf(ids[2], ids[3], otherDevice.snapshotId), remaining)
        // 物删旧快照仍保留对象，等 GC 统一回收。
        assertTrue(engine().snapshotsStrict(store).size == 3)
    }

    @Test fun pruneUnlimitedKeepsAllSnapshots() = runBlocking<Unit> {
        repeat(3) { Thread.sleep(5); save(objects = listOf(tableObject("folderentity"))) }
        val result = engine().pruneSnapshots(store, deviceA, keep = 0)
        assertEquals(0, result.deletedSnapshots)
        assertEquals(3, engine().list(store).size)
    }

    @Test fun garbageCollectDeletesOnlyUnreferencedObjects() = runBlocking<Unit> {
        val first = save(objects = listOf(tableObject("folderentity")))
        Thread.sleep(5)
        val second = save(objects = listOf(tableObject("assistantentity")))
        assertEquals(3, store.data.keys.count { it.startsWith("v3/objects/") })

        engine().deleteSnapshot(store, second)
        val result = engine().garbageCollect(store)

        assertEquals(1, result.deletedObjects)
        assertEquals(2, result.keptObjects)
        assertEquals(2, store.data.keys.count { it.startsWith("v3/objects/") })
        val remainingShas = store.data.keys.filter { it.startsWith("v3/objects/") }.map { it.substringAfterLast('/') }.toSet()
        assertTrue(remainingShas.containsAll(first.objects.map { it.sha256 }))
    }

    @Test fun garbageCollectRefusesMultiDeviceRepository() = runBlocking<Unit> {
        save(deviceId = deviceA, objects = listOf(tableObject("folderentity")))
        save(deviceId = deviceB, objects = listOf(tableObject("assistantentity")))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine().garbageCollect(store) }
        }
        assertTrue(failure.message.orEmpty().contains("跨设备"))
        assertEquals(3, store.data.keys.count { it.startsWith("v3/objects/") })
    }

    @Test fun garbageCollectFailsClosedOnDamagedSnapshot() = runBlocking<Unit> {
        val first = save(objects = listOf(tableObject("folderentity")))
        Thread.sleep(5)
        val second = save(objects = listOf(tableObject("assistantentity")))
        val objectsBefore = store.data.keys.count { it.startsWith("v3/objects/") }
        // 破坏第二份清单：宽容的 list() 会跳过，但 GC 必须失败而不是把它的对象当垃圾。
        store.data[second.manifestKey] = byteArrayOf(0, 1, 2)
        assertEquals(1, engine().list(store).size)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().garbageCollect(store) } }
        assertEquals(objectsBefore, store.data.keys.count { it.startsWith("v3/objects/") })
        // 仍引用第一份快照的对象没有被误删。
        first.objects.forEach { assertTrue(store.data.containsKey(it.key)) }
    }

    @Test fun garbageCollectAfterDeletingLastSnapshotReclaimsAllObjects() = runBlocking<Unit> {
        val saved = save(objects = listOf(tableObject("folderentity")))
        val objectsBefore = store.data.keys.count { it.startsWith("v3/objects/") }
        assertTrue(objectsBefore > 0)
        engine().deleteSnapshot(store, saved)
        assertNull(store.data[saved.manifestKey])
        assertTrue(engine().list(store).isEmpty())

        val result = engine().garbageCollect(store)
        assertEquals(objectsBefore, result.deletedObjects)
        assertEquals(0, store.data.keys.count { it.startsWith("v3/objects/") })
    }
}
