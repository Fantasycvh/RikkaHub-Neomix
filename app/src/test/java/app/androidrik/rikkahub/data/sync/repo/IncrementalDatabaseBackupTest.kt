package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupManifest
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.sha256Hex
import app.androidrik.rikkahub.data.sync.verifyBackupManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.zip.ZipFile

class IncrementalDatabaseBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val device = UUID.randomUUID().toString()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = MemoryBackupStore()
    private fun engine() = IncrementalBackupEngine(temp.root.resolve("cache"))
    private fun settings() = SnapshotInput("settings.json", temp.newFile().apply { writeText("{}") })

    private fun conversationObject(id: String, messageBody: String, nodeId: String = UUID.randomUUID().toString()): SnapshotDbObjectInput {
        val file = temp.newFile()
        DbObjectPayloadWriter(file, conversationObjectKey(id)).use { writer ->
            writer.start(listOf(
                DbTableSchema("conversationentity", listOf("id", "title", "update_at")),
                DbTableSchema("message_node", listOf("id", "conversation_id", "messages")),
                DbTableSchema("compression_checkpoint", listOf("id", "conversation_id", "content")),
            ))
            writer.row("conversationentity", listOf(dbText(id), dbText("title"), dbLong(1)))
            writer.row("message_node", listOf(dbText(nodeId), dbText(id), dbText(messageBody)))
        }
        return SnapshotDbObjectInput(conversationObjectKey(id), file)
    }

    private fun tableObject(name: String, rowId: String): SnapshotDbObjectInput {
        val file = temp.newFile()
        DbObjectPayloadWriter(file, tableObjectKey(name)).use { writer ->
            writer.start(listOf(DbTableSchema(name, listOf("id", "content"))))
            writer.row(name, listOf(dbText(rowId), dbText("value")))
        }
        return SnapshotDbObjectInput(tableObjectKey(name), file)
    }

    private suspend fun plan(
        inputs: List<SnapshotInput> = listOf(settings()),
        databaseObjects: List<SnapshotDbObjectInput>,
    ): BackupSnapshotPlan = engine().plan(
        store, inputs, listOf(BackupItem.FILES, BackupItem.DATABASE),
        SnapshotDatabaseState.PRESENT, device, 58, "1", databaseObjects,
        // 对象层断言（数量/复用）用独立对象更直观；pack 行为由 SnapshotPacking/引擎 pack 测试覆盖。
        packing = false,
    )

    private suspend fun save(databaseObjects: List<SnapshotDbObjectInput>): BackupSnapshotManifest =
        plan(databaseObjects = databaseObjects).use { engine().commit(it) }

    @Test fun logicalSnapshotRoundTripProducesRestorableZip() = runBlocking<Unit> {
        val conversationId = UUID.randomUUID().toString()
        val objects = listOf(
            conversationObject(conversationId, "[\"hello\"]"),
            tableObject("folderentity", UUID.randomUUID().toString()),
        )
        val saved = save(objects)
        assertEquals(SNAPSHOT_FORMAT_V3, saved.formatVersion)
        assertTrue(saved.files.none { it.path == "rikka_hub.db" })
        assertEquals(2, saved.dbObjects.size)
        assertEquals(3, saved.objects.size)

        val mergedBytes = "merged-database-bytes".toByteArray()
        val zip = engine().assemble(store, saved, 58) { decoded, _ ->
            assertEquals(2, decoded.size)
            assertEquals(setOf("conversation:$conversationId", "table:folderentity"), decoded.map { it.objectKey }.toSet())
            decoded.forEach { decodedObject -> assertTrue(readDbObjectPayload(decodedObject.raw).tables.isNotEmpty()) }
            temp.newFile("merged.db").apply { writeBytes(mergedBytes) }
        }
        ZipFile(zip).use { archive ->
            assertNotNull(archive.getEntry("rikka_hub.db"))
            val database = archive.getInputStream(archive.getEntry("rikka_hub.db")).readBytes()
            assertArrayEquals(mergedBytes, database)
            val manifest = json.decodeFromString<BackupManifest>(
                archive.getInputStream(archive.getEntry("manifest.json")).reader().readText(),
            )
            verifyBackupManifest(
                manifest,
                settingsSha256 = saved.files.single { it.path == "settings.json" }.sha256,
                settingsBytes = saved.files.single { it.path == "settings.json" }.size,
                stagedMainSha256 = sha256Hex(database),
                stagedMainBytes = database.size.toLong(),
                restoreDatabase = true,
                sectionEntries = mapOf(BackupManifestSection.SETTINGS to 1, BackupManifestSection.DATABASE to 1),
            )
            assertEquals(mergedBytes.size.toLong(), manifest.databaseBytes)
            assertEquals(1, manifest.sections.getValue(BackupManifestSection.DATABASE).entries)
        }
    }

    @Test fun onlyChangedConversationIsUploadedAgain() = runBlocking<Unit> {
        val conversationA = UUID.randomUUID().toString()
        val conversationB = UUID.randomUUID().toString()
        val nodeA = UUID.randomUUID().toString()
        val nodeB = UUID.randomUUID().toString()
        val folder = tableObject("folderentity", UUID.randomUUID().toString())
        save(listOf(conversationObject(conversationA, "a", nodeA), conversationObject(conversationB, "b", nodeB), folder))
        plan(databaseObjects = listOf(conversationObject(conversationA, "a", nodeA), conversationObject(conversationB, "changed", nodeB), folder)).use { second ->
            assertEquals(1, second.uploads.size)
            assertTrue(second.reusedBytes > 0)
        }
    }

    @Test fun crossSchemaLogicalSnapshotGate() = runBlocking<Unit> {
        val saved = save(listOf(conversationObject(UUID.randomUUID().toString(), "x")))
        // 旧快照（58 <= 当前 59）允许恢复：缺 merge 回调仍报错，提供回调即可组装。
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().assemble(store, saved, 59) } }
        val zip = engine().assemble(store, saved, 59) { _, _ ->
            temp.newFile("merged.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        }
        ZipFile(zip).use { assertNotNull(it.getEntry("rikka_hub.db")) }
        // 更新版本的快照仍然拒绝。
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().assemble(store, saved, 57) } }
    }

    @Test fun logicalSnapshotNeedsMergeCallback() = runBlocking<Unit> {
        val saved = save(listOf(conversationObject(UUID.randomUUID().toString(), "x")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine().assemble(store, saved, 58) } }
    }

    @Test fun corruptedDatabaseObjectRejectsRestore() = runBlocking<Unit> {
        val saved = save(listOf(conversationObject(UUID.randomUUID().toString(), "x")))
        store.data[saved.dbObjects.single().key] = byteArrayOf(0, 1, 2)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine().assemble(store, saved, 58) { _, _ -> temp.newFile() } }
        }
    }

    @Test fun manifestRejectsInvalidLogicalSnapshots() = runBlocking<Unit> {
        val saved = save(listOf(conversationObject(UUID.randomUUID().toString(), "x")))
        // v1 不允许携带逻辑对象
        assertThrows(IllegalArgumentException::class.java) { validateSnapshot(saved.copy(formatVersion = SNAPSHOT_FORMAT_V1)) }
        // PRESENT 必须有对象
        assertThrows(IllegalArgumentException::class.java) { validateSnapshot(saved.copy(dbObjects = emptyList())) }
        // 非 PRESENT 不允许对象
        assertThrows(IllegalArgumentException::class.java) {
            validateSnapshot(saved.copy(databaseState = SnapshotDatabaseState.NOT_SELECTED))
        }
        // 非法对象键
        assertThrows(IllegalArgumentException::class.java) {
            validateSnapshot(saved.copy(dbObjects = saved.dbObjects.map { it.copy(objectKey = "conversation:bad") }))
        }
        // 会话表不允许出现在 table: 对象里
        assertThrows(IllegalArgumentException::class.java) {
            validateSnapshot(saved.copy(dbObjects = listOf(saved.dbObjects.first().copy(objectKey = "table:message_node"))))
        }
    }

    @Test fun invalidDatabaseObjectInputIsRejected() = runBlocking<Unit> {
        val invalid = SnapshotDbObjectInput("table:message_fts", temp.newFile().apply { writeText("x") })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { plan(databaseObjects = listOf(invalid)).close() } }
    }

    @Test fun legacyWholeDatabaseSnapshotStillUsesFormatV1() = runBlocking<Unit> {
        val database = SnapshotInput("rikka_hub.db", temp.newFile().apply { writeText("db-bytes") })
        val saved = engine().plan(
            store, listOf(settings(), database), listOf(BackupItem.FILES, BackupItem.DATABASE),
            SnapshotDatabaseState.PRESENT, device, 58, "1",
        ).use { engine().commit(it) }
        assertEquals(SNAPSHOT_FORMAT_V1, saved.formatVersion)
        assertTrue(saved.dbObjects.isEmpty())
        assertNotNull(saved.files.singleOrNull { it.path == "rikka_hub.db" })
        ZipFile(engine().assemble(store, saved, 58)).use { assertNotNull(it.getEntry("rikka_hub.db")) }
    }
}
