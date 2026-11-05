package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupManifest
import app.androidrik.rikkahub.data.sync.BackupSectionStats
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.verifyBackupManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.GZIPOutputStream

class IncrementalBackupEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val device = UUID.randomUUID().toString()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = MemoryBackupStore()
    private fun engine() = IncrementalBackupEngine(temp.root.resolve("cache"))
    private fun source(path: String, content: String): SnapshotInput = SnapshotInput(path, temp.newFile().apply { writeText(content) })
    private fun settings() = source("settings.json", "{}")
    private suspend fun plan(inputs: List<SnapshotInput>, state: SnapshotDatabaseState = SnapshotDatabaseState.NOT_SELECTED,
        items: List<BackupItem> = listOf(BackupItem.FILES)) =
        // 本测试类聚焦对象层语义（对象数量/复用/孤儿校验），关掉 pack 让断言直观；pack 行为由 SnapshotPacking 测试覆盖。
        engine().plan(store, inputs, items, state, device, 58, "1", packing = false)
    private suspend fun save(inputs: List<SnapshotInput>): BackupSnapshotManifest = plan(inputs).use { engine().commit(it) }

    @Test fun firstBackupAndUnchangedBackupReuseObjects() = runBlocking<Unit> {
        val inputs = listOf(settings(), source("upload/a.txt", "hello"))
        val first = save(inputs)
        val firstObjects = store.puts.count { it.startsWith("v3/objects/") }
        plan(inputs).use { second ->
            assertEquals(0L, second.uploadBytes)
            assertEquals(inputs.sumOf { it.file.length() }, second.reusedBytes)
            engine().commit(second)
        }
        assertEquals(firstObjects, store.puts.count { it.startsWith("v3/objects/") })
        assertEquals(2, engine().list(store).size)
        assertEquals(2, first.files.size)
    }

    @Test fun planDoesNotWriteRepository() = runBlocking<Unit> {
        plan(listOf(settings())).use { assertTrue(store.data.isEmpty()) }
    }

    @Test fun sameSizeAndMtimeChangesAreDetected() = runBlocking<Unit> {
        val file = source("upload/a.txt", "old")
        val inputs = listOf(settings(), file)
        val first = save(inputs)
        val time = file.file.lastModified()
        file.file.writeText("new"); file.file.setLastModified(time)
        plan(inputs).use {
            assertNotEquals(first.files.last().sha256, it.snapshot.files.last().sha256)
            assertTrue(it.uploadBytes > 0)
        }
    }

    @Test fun planCapturesBytesBeforeConfirmation() = runBlocking<Unit> {
        val input = source("upload/a.txt", "before")
        plan(listOf(settings(), input)).use { plan ->
            input.file.writeText("after")
            val snapshot = engine().commit(plan)
            val zip = engine().assemble(store, snapshot, 58)
            ZipFile(zip).use { assertEquals("before", it.getInputStream(it.getEntry(input.path)).reader().readText()) }
        }
    }

    @Test fun additionsDeletionsAndRenamesAreCompleteViews() = runBlocking<Unit> {
        val shared = settings()
        val first = save(listOf(shared, source("upload/old.txt", "same")))
        val second = save(listOf(shared, source("upload/new.txt", "same")))
        assertEquals(first.files.last().sha256, second.files.last().sha256)
        val zip = engine().assemble(store, second, 58)
        ZipFile(zip).use { assertNull(it.getEntry("upload/old.txt")); assertNotNull(it.getEntry("upload/new.txt")) }
    }

    @Test fun failureBeforeCompletionLeavesNoSnapshot() = runBlocking<Unit> {
        plan(listOf(settings())).use { plan ->
            store.failPut = plan.snapshot.completionKey
            assertThrows(Exception::class.java) { runBlocking { engine().commit(plan) } }
            assertTrue(engine().list(store).isEmpty())
        }
    }

    @Test fun lostCompletionResponseIsConfirmed() = runBlocking<Unit> {
        plan(listOf(settings())).use { plan ->
            store.loseResponse = plan.snapshot.completionKey
            assertEquals(plan.snapshot, engine().commit(plan))
            assertEquals(1, engine().list(store).size)
        }
    }

    @Test fun malformedCompletionIsNotListed() = runBlocking<Unit> {
        save(listOf(settings()))
        store.data["v3/snapshots/${UUID.randomUUID()}.complete.json"] = "{".toByteArray()
        assertEquals(1, engine().list(store).size)
    }

    @Test fun queryFailureNeverMeansMissing() = runBlocking<Unit> {
        store.failStat = true
        assertThrows(java.io.IOException::class.java) { runBlocking { plan(listOf(settings())).close() } }
        assertTrue(store.data.isEmpty())
    }

    @Test fun missingRemoteObjectIsUploadedAgain() = runBlocking<Unit> {
        val inputs = listOf(settings(), source("upload/a.txt", "value"))
        val first = save(inputs)
        store.data.remove(first.files.last().key)
        plan(inputs).use { assertTrue(it.uploadBytes > 0); engine().commit(it) }
        assertNotNull(store.data[first.files.last().key])
    }

    @Test fun referenceDisappearingAfterPlanPreventsCommit() = runBlocking<Unit> {
        val inputs = listOf(settings())
        val first = save(inputs)
        plan(inputs).use { plan ->
            store.data.remove(first.files.single().key)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().commit(plan) } }
            assertFalse(store.data.containsKey(plan.snapshot.completionKey))
        }
    }

    @Test fun corruptedObjectRejectsRestoreBeforeZipIsReturned() = runBlocking<Unit> {
        val snapshot = save(listOf(settings()))
        store.data[snapshot.files.single().key] = byteArrayOf(0, 7, 8)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().assemble(store, snapshot, 58) } }
    }

    @Test fun orphanCorruptionIsNotTrustedOnRetry() = runBlocking<Unit> {
        plan(listOf(settings())).use { plan ->
            store.data[plan.snapshot.files.single().key] = byteArrayOf(0, 99)
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { plan(listOf(settings())).close() } }
    }

    @Test fun deletionKeepsSharedObjectsAndOtherSnapshotRecoverable() = runBlocking<Unit> {
        val inputs = listOf(settings())
        val first = save(inputs); val second = save(inputs)
        engine().deleteSnapshot(store, first)
        assertEquals(listOf(second), engine().list(store))
        assertNotNull(store.data[second.files.single().key])
        assertTrue(engine().assemble(store, second, 58).isFile)
    }

    @Test fun newerDatabaseVersionIsRejected() = runBlocking<Unit> {
        val snapshot = save(listOf(settings()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().assemble(store, snapshot, 57) } }
    }

    @Test fun assembledZipPassesExistingV2ManifestVerification() = runBlocking<Unit> {
        val snapshot = save(listOf(settings(), source("backgrounds/a.png", "image"), source("avatar-frames/frame.png", "frame")))
        ZipFile(engine().assemble(store, snapshot, 58)).use { zip ->
            val manifest = json.decodeFromString<BackupManifest>(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            verifyBackupManifest(manifest, snapshot.files.first().sha256, snapshot.files.first().size,
                null, 0, false, mapOf(BackupManifestSection.SETTINGS to 1, BackupManifestSection.FILES to 2))
            assertEquals(BackupSectionStats(2, 10), manifest.sections[BackupManifestSection.FILES])
        }
    }

    @Test fun absentDatabaseIsDifferentFromNotSelected() = runBlocking<Unit> {
        plan(listOf(settings()), SnapshotDatabaseState.ABSENT, listOf(BackupItem.DATABASE)).use {
            assertEquals(SnapshotDatabaseState.ABSENT, engine().commit(it).databaseState)
        }
    }

    @Test fun duplicateOrEscapingPathsAreRejected() = runBlocking<Unit> {
        listOf("../settings.json", "upload/../a", "/upload/a", "upload\\a", "unknown/a").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { plan(listOf(settings(), source(path, "bad"))).close() } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { plan(listOf(settings(), settings())).close() } }
    }

    @Test fun snapshotIdsAreUniqueWithinSameSecond() = runBlocking<Unit> {
        val a = save(listOf(settings())); val b = save(listOf(settings()))
        assertNotEquals(a.snapshotId, b.snapshotId)
    }

    @Test fun boundedCopyRejectsOversizedStream() {
        assertThrows(IllegalArgumentException::class.java) { copyBounded(byteArrayOf(1, 2).inputStream(), java.io.ByteArrayOutputStream(), 1) }
    }

    @Test fun forgedRawSizeAndTruncatedDeflateAreRejected() {
        val raw = temp.newFile().apply { writeText("text".repeat(1000)) }
        val encoded = temp.newFile()
        SnapshotObjectCodec.encode(raw, encoded)
        val decoded = temp.newFile()
        val entry = SnapshotFileEntry("settings.json", fileSha256(raw), raw.length())
        assertThrows(IllegalArgumentException::class.java) { SnapshotObjectCodec.decode(encoded, decoded, entry.copy(size = 1)) }
        encoded.writeBytes(encoded.readBytes().dropLast(3).toByteArray())
        assertThrows(Exception::class.java) { SnapshotObjectCodec.decode(encoded, decoded, entry) }
    }

    @Test fun unknownVersionRejected() = runBlocking<Unit> {
        plan(listOf(settings())).use { assertThrows(UnsupportedOperationException::class.java) { validateSnapshot(it.snapshot.copy(formatVersion = 100)) } }
    }

    @Test fun nonEmptyFolderWithoutRepositoryIsRejected() = runBlocking<Unit> {
        store.data["user-file"] = byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { plan(listOf(settings())).close() } }
        assertEquals(setOf("user-file"), store.data.keys)
    }

    @Test fun sourceChangingDuringCaptureIsRejected() = runBlocking<Unit> {
        val input = source("upload/file.txt", "original")
        var changed = false
        val mutating = IncrementalBackupEngine(temp.root.resolve("cache"), progress = {
            if (!changed) { changed = true; input.file.appendText("changed") }
        })
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            mutating.plan(store, listOf(input, settings()), listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, device, 58, "1", packing = false).close()
        } }
        assertTrue(store.data.isEmpty())
    }

    @Test fun entireRepositoryCanMoveWithoutLocalIndex() = runBlocking<Unit> {
        val snapshot = save(listOf(settings(), source("upload/file", "portable")))
        val moved = MemoryBackupStore().also { it.data.putAll(store.data) }
        store.data.clear()
        val freshEngine = IncrementalBackupEngine(temp.root.resolve("new-device-cache"))
        assertEquals(listOf(snapshot), freshEngine.list(moved))
        assertTrue(freshEngine.assemble(moved, snapshot, 58).isFile)
    }

    @Test fun repositoryReplacementAfterPreviewIsRejected() = runBlocking<Unit> {
        val inputs = listOf(settings())
        save(inputs)
        plan(inputs).use { plan ->
            store.data["repo.json"] = json.encodeToString(BackupRepositoryInfo(repoId = UUID.randomUUID().toString(), createdAt = java.time.Instant.now().toString())).toByteArray()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().commit(plan) } }
            assertFalse(store.data.containsKey(plan.snapshot.completionKey))
        }
    }

    @Test fun expiredPlanCannotPublish() = runBlocking<Unit> {
        plan(listOf(settings())).use { original ->
            val expired = BackupSnapshotPlan(store, original.snapshot, original.directory, original.uploads, original.reusedBytes, java.time.Instant.EPOCH)
            assertThrows(IllegalStateException::class.java) { runBlocking { engine().commit(expired) } }
            assertTrue(store.data.isEmpty())
        }
    }

    @Test fun sequentialDevicesShareObjectsWithoutSharingManifests() = runBlocking<Unit> {
        val inputs = listOf(settings())
        val first = save(inputs)
        val secondDevice = UUID.randomUUID().toString()
        engine().plan(store, inputs, listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, secondDevice, 58, "1", packing = false).use { second ->
            assertEquals(0L, second.uploadBytes)
            val saved = engine().commit(second)
            engine().deleteSnapshot(store, first)
            assertTrue(engine().assemble(store, saved, 58).isFile)
        }
    }

    @Test fun fileDirectoryConflictIsRejectedBeforeRestore() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            plan(listOf(settings(), source("images/a", "a"), source("images/a/b", "b"))).close()
        } }
    }
}

internal class MemoryBackupStore : BackupObjectStore {
    override val destinationId = "memory"
    // 引擎对独立对象并行 stat/put/get；测试替身也必须按「并发安全」实现（真实 store 同约定）。
    val data = java.util.Collections.synchronizedMap(linkedMapOf<String, ByteArray>())
    val puts = java.util.Collections.synchronizedList(mutableListOf<String>())
    val modified = java.util.Collections.synchronizedMap(linkedMapOf<String, java.time.Instant>())
    var failPut: String? = null
    var loseResponse: String? = null
    var failStat = false
    /** 让写入看起来「已经放了一阵子」，用于 GC 宽限期测试。 */
    var ageOnPut: java.time.Duration = java.time.Duration.ZERO
    /** 模拟拿不到 mtime 的 provider：GC 必须保守保留。 */
    var unknownMtime = false
    private fun stored(key: String) = BackupStoredObject(
        key,
        data.getValue(key).size.toLong(),
        lastModified = if (unknownMtime) null else modified[key],
    )
    override suspend fun stat(key: String): BackupStoredObject? {
        if (failStat) throw java.io.IOException("query failed")
        return data[key]?.let { stored(key) }
    }
    override suspend fun listChildren(prefix: String) = data.keys.filter {
        it.startsWith(if (prefix.isEmpty()) "" else "$prefix/") && !it.removePrefix(if (prefix.isEmpty()) "" else "$prefix/").contains('/')
    }.map { stored(it) }
    override suspend fun listAll(prefix: String) = data.keys.filter { it.startsWith("$prefix/") }.map { stored(it) }
    override suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit) {
        val bytes = data[key] ?: throw java.io.FileNotFoundException(key)
        target.outputStream().use { copyBounded(bytes.inputStream(), it, maxBytes, onBytes) }
    }
    override suspend fun put(key: String, source: File, onBytes: (Long) -> Unit) {
        if (failPut == key) throw java.io.IOException("write failed")
        puts += key
        data[key] = source.readBytes(); onBytes(source.length())
        modified[key] = java.time.Instant.now().minus(ageOnPut)
        if (loseResponse == key) throw java.io.IOException("response lost")
    }
    override suspend fun delete(key: String) { data.remove(key); modified.remove(key) }
    override suspend fun ensureDir(prefix: String) = Unit
}
