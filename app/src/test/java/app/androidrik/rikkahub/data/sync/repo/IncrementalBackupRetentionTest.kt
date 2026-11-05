package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

/** 与引擎内部租约路径一致（引擎侧是 private companion，测试直接构造仓库内容）。 */
private const val GC_LEASE_KEY_PATH = "v3/gc.json"

/** 保留策略 + 未引用对象/pack GC（P2 后续阶段）。 */
class IncrementalBackupRetentionTest {
    @get:Rule val temp = TemporaryFolder()
    private val deviceA = UUID.randomUUID().toString()
    private val deviceB = UUID.randomUUID().toString()
    private val store = MemoryBackupStore()
    private val json = Json { ignoreUnknownKeys = true }
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
        // 对象计数断言针对独立对象；pack 的回收语义由 SnapshotPacking/引擎 pack 测试覆盖。
        packing = false,
    ).use { engine().commit(it) }

    private fun repositoryId(): String = json.decodeFromString<BackupRepositoryInfo>(
        store.data.getValue("repo.json").decodeToString(),
    ).repoId

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
        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)

        assertEquals(1, result.deletedObjects)
        assertEquals(2, result.keptObjects)
        assertEquals(2, store.data.keys.count { it.startsWith("v3/objects/") })
        val remainingShas = store.data.keys.filter { it.startsWith("v3/objects/") }.map { it.substringAfterLast('/') }.toSet()
        assertTrue(remainingShas.containsAll(first.objects.map { it.sha256 }))
    }

    @Test fun garbageCollectWorksAcrossDevicesAndKeepsSharedContent() = runBlocking<Unit> {
        val first = save(deviceId = deviceA, objects = listOf(tableObject("folderentity")))
        val second = save(deviceId = deviceB, objects = listOf(tableObject("assistantentity")))
        assertEquals(3, store.data.keys.count { it.startsWith("v3/objects/") })

        engine().deleteSnapshot(store, second)
        // 默认宽限期：刚被删快照的对象还「很新」，必须保留（防另一台设备正在上传）。
        val guarded = engine().garbageCollect(store, deviceA)
        assertEquals(0, guarded.deletedObjects)
        assertTrue(guarded.skippedRecent >= 1)
        assertEquals(3, store.data.keys.count { it.startsWith("v3/objects/") })

        // 零宽限期回收：只删不可达对象，另一台设备仍引用的共享对象必须留下。
        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(1, result.deletedObjects)
        assertEquals(2, result.keptObjects)
        assertEquals(2, store.data.keys.count { it.startsWith("v3/objects/") })
        assertTrue(first.objects.all { store.data.containsKey(it.storageKey) })
    }

    @Test fun garbageCollectKeepsObjectsWithUnknownMtime() = runBlocking<Unit> {
        save(deviceId = deviceA, objects = listOf(tableObject("folderentity")))
        val stale = save(deviceId = deviceA, objects = listOf(tableObject("assistantentity")))
        engine().deleteSnapshot(store, stale)
        // 拿不到 mtime 的 provider（部分 WebDAV/S3 响应）：GC 一律保守保留。
        store.unknownMtime = true
        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(0, result.deletedObjects)
        assertTrue(result.skippedRecent >= 1)
        assertEquals(3, store.data.keys.count { it.startsWith("v3/objects/") })
    }

    @Test fun foreignGcLeaseBlocksCollection() = runBlocking<Unit> {
        save(deviceId = deviceA, objects = listOf(tableObject("folderentity")))
        val lease = GcLease(deviceB, repositoryId(), Instant.now().toString())
        store.data[GC_LEASE_KEY_PATH] = json.encodeToString(lease).toByteArray()
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine().garbageCollect(store, deviceA) }
        }
        assertTrue(failure.message.orEmpty().contains("另一台设备"))
        // 别人的租约不能被我们的失败路径清掉。
        assertNotNull(store.data[GC_LEASE_KEY_PATH])
    }

    @Test fun staleOwnLeaseIsOverwrittenAndReleased() = runBlocking<Unit> {
        save(objects = listOf(tableObject("folderentity")))
        // 上次回收被系统杀掉留下的同设备旧租约：必须能覆盖写并正常收尾，不能卡住仓库。
        val stale = GcLease(deviceA, repositoryId(), Instant.now().minus(Duration.ofHours(2)).toString())
        store.data[GC_LEASE_KEY_PATH] = json.encodeToString(stale).toByteArray()

        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(0, result.deletedObjects)
        assertNull(store.data[GC_LEASE_KEY_PATH])
    }

    @Test fun expiredForeignLeaseIsReplacedAndReleased() = runBlocking<Unit> {
        save(objects = listOf(tableObject("folderentity")))
        // 超过 TTL 的别家租约视为失效；接手后收尾只删自己的租约。
        val expired = GcLease(deviceB, repositoryId(), Instant.now().minus(Duration.ofHours(2)).toString())
        store.data[GC_LEASE_KEY_PATH] = json.encodeToString(expired).toByteArray()

        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(0, result.deletedObjects)
        assertNull(store.data[GC_LEASE_KEY_PATH])
    }

    @Test fun garbageCollectReclaimsUnreferencedPacks() = runBlocking<Unit> {
        val saved = engine().plan(
            store, listOf(settingsInput(), SnapshotInput("upload/a.txt", temp.newFile().apply { writeText("packed-content") })),
            listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, deviceA, 58, "1",
        ).use { engine().commit(it) }
        assertEquals(1, saved.packs.size)
        assertEquals(1, store.data.keys.count { it.startsWith("$PACK_DIR/") })

        engine().deleteSnapshot(store, saved)
        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(0, result.deletedObjects)
        assertEquals(1, result.deletedPacks)
        assertEquals(0, store.data.keys.count { it.startsWith("$PACK_DIR/") })
    }

    @Test fun garbageCollectFailsClosedOnDamagedSnapshot() = runBlocking<Unit> {
        val first = save(objects = listOf(tableObject("folderentity")))
        Thread.sleep(5)
        val second = save(objects = listOf(tableObject("assistantentity")))
        val objectsBefore = store.data.keys.count { it.startsWith("v3/objects/") }
        // 破坏第二份清单：宽容的 list() 会跳过，但 GC 必须失败而不是把它的对象当垃圾。
        store.data[second.manifestKey] = byteArrayOf(0, 1, 2)
        assertEquals(1, engine().list(store).size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine().garbageCollect(store, deviceA, grace = Duration.ZERO) }
        }
        assertEquals(objectsBefore, store.data.keys.count { it.startsWith("v3/objects/") })
        // 仍引用第一份快照的对象没有被误删。
        first.objects.forEach { assertTrue(store.data.containsKey(it.storageKey)) }
    }

    @Test fun garbageCollectAfterDeletingLastSnapshotReclaimsAllObjects() = runBlocking<Unit> {
        val saved = save(objects = listOf(tableObject("folderentity")))
        val objectsBefore = store.data.keys.count { it.startsWith("v3/objects/") }
        assertTrue(objectsBefore > 0)
        engine().deleteSnapshot(store, saved)
        assertNull(store.data[saved.manifestKey])
        assertTrue(engine().list(store).isEmpty())

        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(objectsBefore, result.deletedObjects)
        assertEquals(0, store.data.keys.count { it.startsWith("v3/objects/") })
    }

    // --- 拆包：pack 内单条回收 ---------------------------------------------------

    private suspend fun saveFiles(files: Map<String, String>): BackupSnapshotManifest = engine().plan(
        store,
        files.map { (path, content) -> SnapshotInput(path, temp.newFile().apply { writeText(content) }) },
        listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, deviceA, 58, "1",
    ).use { engine().commit(it) }

    private fun packKeys(): List<String> = store.data.keys.filter { it.startsWith("$PACK_DIR/") }.sorted()

    @Test fun garbageCollectRepacksPacksWithPartiallyReferencedEntries() = runBlocking<Unit> {
        val first = saveFiles(mapOf("settings.json" to "{}", "upload/a.txt" to "aaaa", "upload/b.txt" to "bbbb"))
        assertTrue(first.files.all { it.pack != null })
        assertEquals(1, packKeys().size)
        val oldPack = requireNotNull(first.files.first().pack)
        Thread.sleep(5)
        val second = saveFiles(mapOf("settings.json" to "{}", "upload/a.txt" to "aaaa"))
        // 第二份快照复用旧 pack 里的 settings/a；b.txt 只属于第一份。
        assertEquals(listOf(packKeyFor(oldPack)), packKeys())
        assertTrue(second.files.all { it.pack == oldPack })
        engine().deleteSnapshot(store, first)

        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)

        assertEquals(0, result.deletedObjects)
        assertEquals(1, result.repackedPacks)
        assertEquals(1, result.deletedPacks)
        assertNull(store.data[GC_LEASE_KEY_PATH])
        assertNull(store.data[snapshotRewriteKey(second.snapshotId)])
        val newPackKey = packKeys().single()
        assertTrue(newPackKey != packKeyFor(oldPack))
        val repacked = engine().list(store).single()
        assertTrue(repacked.files.all { it.pack != null && it.pack != oldPack })
        ZipFile(engine().assemble(store, repacked, 58)).use { zip ->
            assertEquals("{}", zip.getInputStream(zip.getEntry("settings.json")).reader().readText())
            assertEquals("aaaa", zip.getInputStream(zip.getEntry("upload/a.txt")).reader().readText())
            assertNull(zip.getEntry("upload/b.txt"))
        }
    }

    @Test fun garbageCollectDoesNotRepackFullyReferencedPacks() = runBlocking<Unit> {
        val saved = saveFiles(mapOf("settings.json" to "{}", "upload/a.txt" to "aaaa"))
        val manifestBefore = store.data.getValue(saved.manifestKey).copyOf()
        val packsBefore = packKeys()
        val result = engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
        assertEquals(0, result.repackedPacks)
        assertEquals(0, result.deletedPacks)
        assertEquals(packsBefore, packKeys())
        // 无需拆包时清单保持原样（没有改写快照）。
        assertTrue(manifestBefore.contentEquals(store.data.getValue(saved.manifestKey)))
        assertNull(store.data[snapshotRewriteKey(saved.snapshotId)])
    }

    @Test fun garbageCollectDefersRepackOfRecentlyWrittenPacks() = runBlocking<Unit> {
        val first = saveFiles(mapOf("settings.json" to "{}", "upload/a.txt" to "aaaa", "upload/b.txt" to "bbbb"))
        val oldPack = requireNotNull(first.files.first().pack)
        Thread.sleep(5)
        val second = saveFiles(mapOf("settings.json" to "{}", "upload/a.txt" to "aaaa"))
        engine().deleteSnapshot(store, first)

        // 宽限期内的 pack 先不拆（删除也要等它静默）。
        val result = engine().garbageCollect(store, deviceA, grace = Duration.ofHours(1))
        assertEquals(0, result.repackedPacks)
        assertEquals(0, result.deletedPacks)
        assertTrue(engine().list(store).single().files.all { it.pack == oldPack })
        assertEquals(oldPack, second.files.first().pack)
    }

    @Test fun snapshotsStrictRollsBackInterruptedPackRewrite() = runBlocking<Unit> {
        val saved = saveFiles(mapOf("settings.json" to "{}", "upload/a.txt" to "aaaa"))
        val originalManifest = store.data.getValue(saved.manifestKey).copyOf()
        // 模拟「旧清单已备份、清单已覆盖、完成标记尚未更新」的中断状态（此时清单哈希与标记不符）。
        store.data[snapshotRewriteKey(saved.snapshotId)] = originalManifest.copyOf()
        store.data[saved.manifestKey] = "half-written".toByteArray()

        val repaired = engine().snapshotsStrict(store)

        assertEquals(listOf(saved.snapshotId), repaired.map { it.snapshotId })
        assertEquals(saved, repaired.single())
        assertTrue(originalManifest.contentEquals(store.data.getValue(saved.manifestKey)))
        assertNull(store.data[snapshotRewriteKey(saved.snapshotId)])
        // 修复后 GC 不会把仓库判成损坏。
        engine().garbageCollect(store, deviceA, grace = Duration.ZERO)
    }

    @Test fun snapshotsStrictDropsStaleRewriteJournalWhenSnapshotMatches() = runBlocking<Unit> {
        val saved = saveFiles(mapOf("settings.json" to "{}"))
        val original = store.data.getValue(saved.manifestKey).copyOf()
        store.data[snapshotRewriteKey(saved.snapshotId)] = original.copyOf()
        assertEquals(1, engine().snapshotsStrict(store).size)
        assertNull(store.data[snapshotRewriteKey(saved.snapshotId)])
        assertTrue(original.contentEquals(store.data.getValue(saved.manifestKey)))
    }
}
