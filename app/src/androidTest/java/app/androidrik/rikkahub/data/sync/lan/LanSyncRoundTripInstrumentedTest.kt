package app.androidrik.rikkahub.data.sync.lan

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.db.fts.createMessageFtsTables
import app.androidrik.rikkahub.data.export.collectAttachmentUrls
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import kotlinx.coroutines.flow.first
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.data.sync.BackupArchiveEngine
import app.androidrik.rikkahub.utils.JsonInstant
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.net.Inet4Address
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LanSyncRoundTripInstrumentedTest {
    @Test
    fun synchronizesFamiliesAssetsSkillsWorkspaceAndDeletions() = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            check(base.packageName.contains(".localsnapshotqa.") || base.packageName.contains(".backupverification."))
            val address = requireNotNull(findPrivateAddress())
            val fixture = Files.createTempDirectory(base.cacheDir.toPath(), "lan-families-").toFile()
            val aliases = List(2) { "lan-families-${UUID.randomUUID()}" }
            startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
            try {
                roomPeer(base, File(fixture, "left"), aliases[0]).use { left ->
                    roomPeer(base, File(fixture, "right"), aliases[1]).use { right ->
                        left.settings.settingsFlow.first { !it.init }
                        right.settings.settingsFlow.first { !it.init }
                        val image = File(left.app.filesDir, "avatars/fixture.png")
                        image.parentFile!!.mkdirs()
                        image.writeText("avatar fixture")
                        val book = Lorebook(name = "synced world")
                        val assistant = Assistant(name = "full config", systemPrompt = "keep prompt",
                            temperature = 0.42f, enabledSkills = setOf("qa-skill"), lorebookIds = setOf(book.id),
                            avatar = Avatar.Image("file://${image.absolutePath}"))
                        left.settings.update { it.copy(assistants = it.assistants + assistant, lorebooks = it.lorebooks + book) }
                        val skill = File(left.app.filesDir, "skills/qa-skill/SKILL.md")
                        skill.parentFile!!.mkdirs()
                        skill.writeText("---\nname: qa-skill\ndescription: fixture\n---\nUse this fixture.")
                        val workspace = WorkspaceEntity(UUID.randomUUID().toString(), "test workspace", "qa-workspace",
                            createdAt = 1, updatedAt = 1)
                        left.database.workspaceDao().upsert(workspace)
                        val content = File(left.app.filesDir, "workspaces/qa-workspace/files/src/file.txt")
                        content.parentFile!!.mkdirs()
                        content.writeBytes(ByteArray(128 * 1024) { (it % 113).toByte() })
                        round(address, left, right, budget = 32 * 1024)
                        assertTrue(right.settings.settingsFlow.value.assistants.any { it.id == assistant.id })
                        assertTrue(!File(right.app.filesDir, "workspaces/qa-workspace/files/src/file.txt").exists())
                        round(address, left, right)
                        val copied = right.settings.settingsFlow.first { it.assistants.any { a -> a.id == assistant.id } }
                        val received = copied.assistants.single { it.id == assistant.id }
                        assertEquals(assistant.systemPrompt, received.systemPrompt)
                        assertEquals(assistant.temperature, received.temperature)
                        assertEquals(assistant.lorebookIds, received.lorebookIds)
                        assertEquals(assistant.enabledSkills, received.enabledSkills)
                        assertEquals(image.readText(), File((received.avatar as Avatar.Image).url.removePrefix("file://")).readText())
                        assertEquals(book, copied.lorebooks.single { it.id == book.id })
                        assertEquals(skill.readText(), File(right.app.filesDir, "skills/qa-skill/SKILL.md").readText())
                        val remoteContent = File(right.app.filesDir, "workspaces/qa-workspace/files/src/file.txt")
                        assertEquals(content.readText(), remoteContent.readText())
                        assertNotNull(right.database.workspaceDao().getByRoot("qa-workspace"))
                        assertEquals(LanSyncReport() to LanSyncReport(), round(address, left, right))
                        right.settings.update { current -> current.copy(assistants = current.assistants.map {
                            if (it.id == assistant.id) it.copy(systemPrompt = "edited on right") else it
                        }) }
                        round(address, right, left)
                        assertEquals("edited on right", left.settings.settingsFlow.value.assistants.single { it.id == assistant.id }.systemPrompt)
                        remoteContent.writeText("edited on right")
                        round(address, right, left)
                        assertEquals("edited on right", content.readText())
                        assertTrue(content.delete())
                        left.settings.update { it.copy(lorebooks = it.lorebooks.filterNot { b -> b.id == book.id }) }
                        round(address, left, right)
                        assertTrue(!remoteContent.exists())
                        assertTrue(right.settings.settingsFlow.value.lorebooks.none { it.id == book.id })
                        assertEquals(LanSyncReport() to LanSyncReport(), round(address, left, right))
                        val collisionId = UUID.randomUUID().toString()
                        left.database.workspaceDao().upsert(WorkspaceEntity(collisionId, "source", "collision-source",
                            createdAt = 1, updatedAt = 1))
                        right.database.workspaceDao().upsert(WorkspaceEntity(collisionId, "target", "collision-target",
                            createdAt = 1, updatedAt = 1))
                        val conflictingFile = File(left.app.filesDir, "workspaces/collision-source/files/file.txt")
                        conflictingFile.parentFile!!.mkdirs()
                        conflictingFile.writeText("a file that must roll back")
                        left.settings.update { current -> current.copy(assistants = current.assistants.map {
                            if (it.id == assistant.id) it.copy(systemPrompt = "must roll back") else it
                        }) }
                        val failure = runCatching { round(address, left, right) }.exceptionOrNull()
                        assertEquals("Workspace identity already has a different root", failure?.message)
                        assertEquals("edited on right", right.settings.settingsFlow.value.assistants.single { it.id == assistant.id }.systemPrompt)
                        assertTrue(!File(right.app.filesDir, "workspaces/collision-source/files/file.txt").exists())
                    }
                }
            } finally {
                fixture.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store -> aliases.forEach(store::deleteEntry) }
                stopKoin()
            }
        }
    }

    @Test
    fun synchronizesBothRoomsWithResumeConflictsDeletionAndUndo() = runBlocking {
        withContext(Dispatchers.IO) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            check(app.packageName.contains(".localsnapshotqa.") || app.packageName.contains(".backupverification.")) {
                "LAN sync integration test requires an isolated application ID"
            }
            val endpoint = requireNotNull(findPrivateAddress())
            val fixture = Files.createTempDirectory(app.cacheDir.toPath(), "lan-sync-roundtrip-").toFile()
            val aliases = List(2) { "rikka-lan-roundtrip-${UUID.randomUUID()}" }
            val attachment = File.createTempFile("lan-roundtrip-", ".png", File(app.filesDir, "upload").apply { mkdirs() })
            startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
            try {
                attachment.writeBytes(ByteArray(LAN_CHUNK_BYTES + 300) { (it % 81).toByte() })
                val root = MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(
                    UIMessagePart.Image(url = "file://${attachment.absolutePath}"),
                )))
                val reply = MessageNode(parentId = root.id, messages = listOf(UIMessage.assistant("early")))
                val first = Conversation(assistantId = DEFAULT_ASSISTANT_ID, title = "Left", messageNodes = listOf(root, reply),
                    activeLeafNodeId = reply.id, updateAt = Instant.now().minusSeconds(10))
                val second = first.copy(id = kotlin.uuid.Uuid.random(), title = "Right", messageNodes = listOf(
                    root.copy(id = kotlin.uuid.Uuid.random()),
                ), activeLeafNodeId = null)
                roomPeer(app, File(fixture, "left"), aliases[0]).use { left ->
                    roomPeer(app, File(fixture, "right"), aliases[1]).use { right ->
                        left.repository.insertConversation(first)
                        right.repository.insertConversation(second)
                        left.database.conversationDao().setLanMessageMetadata(first.id.toString(), null, reply.id.toString())
                        val preciseLastMessageAt = 1_799_333_000_723L
                        right.database.conversationDao().setLanMessageMetadata(second.id.toString(), preciseLastMessageAt, null)
                        round(endpoint, left, right, interrupt = true)
                        assertNull(requireNotNull(right.repository.getConversationById(first.id)).lastMessageAt)
                        assertEquals(Instant.ofEpochMilli(preciseLastMessageAt),
                            requireNotNull(left.repository.getConversationById(second.id)).lastMessageAt)
                        assertEquals(LanSyncReport() to LanSyncReport(), round(endpoint, left, right))

                        val later = first.updateAt.plusSeconds(1)
                        val currentLeft = requireNotNull(left.repository.getConversationById(first.id))
                        val currentRight = requireNotNull(right.repository.getConversationById(first.id))
                        val leftEdit = currentLeft.messageNodes.last().copy(messages = listOf(UIMessage.assistant("left edit")))
                        val rightEdit = currentRight.messageNodes.last().copy(messages = listOf(UIMessage.assistant("right edit")))
                        left.repository.updateConversation(currentLeft.copy(messageNodes = listOf(currentLeft.messageNodes.first(), leftEdit),
                            updateAt = later))
                        right.repository.updateConversation(currentRight.copy(messageNodes = listOf(currentRight.messageNodes.first(), rightEdit),
                            updateAt = later))
                        val conflict = round(endpoint, left, right)
                        assertEquals(1, conflict.first.conflicts)
                        assertEquals(1, conflict.second.conflicts)
                        val merged = requireNotNull(left.repository.getConversationById(first.id))
                        assertEquals(setOf(leftEdit.currentMessage.toText(), rightEdit.currentMessage.toText()),
                            merged.messageNodes.drop(1).map { it.currentMessage.toText() }.toSet())
                        assertEquals(LanSyncReport() to LanSyncReport(), round(endpoint, left, right))

                        left.repository.deleteConversation(merged, deleteFiles = false)
                        assertEquals(1, round(endpoint, left, right).second.deleted)
                        assertNull(right.repository.getConversationById(first.id))
                        val shared = requireNotNull(right.repository.getConversationById(second.id)).collectAttachmentUrls().single()
                        assertTrue(File(shared.removePrefix("file://")).isFile)
                        assertNotNull(right.database.lanConversationTombstoneDao().time(first.id.toString()))
                        left.repository.insertConversation(merged)
                        round(endpoint, left, right)
                        assertNotNull(right.repository.getConversationById(first.id))
                        assertNull(right.database.lanConversationTombstoneDao().time(first.id.toString()))
                        assertEquals(LanSyncReport() to LanSyncReport(), round(endpoint, left, right))
                    }
                }
            } finally {
                attachment.delete()
                fixture.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store -> aliases.forEach(store::deleteEntry) }
                stopKoin()
            }
        }
    }

    private suspend fun round(
        address: Inet4Address,
        left: RoomPeer,
        right: RoomPeer,
        interrupt: Boolean = false,
        budget: Long = LAN_OBJECT_MAX_BYTES,
    ) =
        coroutineScope {
            val token = newPairToken()
            val now = System.currentTimeMillis()
            left.data.capture(left.credentials.fingerprint, "Left", now).use { local ->
                right.data.capture(right.credentials.fingerprint, "Right", now).use { remote ->
                    val applyFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                    val uploads = LanSyncUploadSession(remote.manifest, right.store, JsonInstant, { now }, remote.files) { manifest, plan ->
                        try {
                            right.data.apply(remote, manifest, plan, right.store)
                        } catch (failure: Throwable) {
                            applyFailure.set(failure)
                            throw failure
                        }
                    }
                    LanSyncDownloadServer(right.credentials, address, remote.manifest, remote.files, JsonInstant,
                        tokenForPeer = { token.takeIf { _ -> it == left.credentials.fingerprint } }, uploads = uploads).use { server ->
                        val listening = launch(Dispatchers.IO) { server.serve() }
                        try {
                            val client = LanSyncDownloadClient(left.credentials, address, server.port, right.credentials.fingerprint, token, JsonInstant)
                            val offer = client.exchange(local.manifest)
                            val plan = planLanMerge(local.manifest, offer.manifest, offer.plannedAt)
                            val outgoing = plan.objectsFrom(local.manifest)
                            if (interrupt) {
                                assertTrue(client.push(outgoing, local.files, 1).remainingObjects.isNotEmpty())
                                assertEquals(409, (runCatching { client.finish() }.exceptionOrNull() as LanSyncHttpException).status)
                            }
                            val sent = client.push(outgoing, local.files, budget)
                            val received = client.pull(plan.objectsFrom(remote.manifest), left.store, budget, local.files)
                            if (budget == LAN_OBJECT_MAX_BYTES) {
                                assertTrue(sent.remainingObjects.isEmpty() && received.remainingObjects.isEmpty())
                            }
                            assertTrue(sent.transferredBytes < LAN_CHUNK_BYTES && received.transferredBytes < LAN_CHUNK_BYTES)
                            val ready = plan.completedKeys(local.manifest, remote.manifest, sent, received)
                            val other = try { client.finish(ready) } catch (failure: Throwable) {
                                throw (applyFailure.get() ?: failure)
                            }
                            assertEquals(other, client.finish(ready))
                            left.data.apply(local, offer.manifest, plan.restrictTo(ready), left.store) to other
                        } finally {
                            server.close()
                            listening.cancel()
                        }
                    }
                }
            }
        }

    private fun roomPeer(app: Application, root: File, alias: String): RoomPeer {
        check(root.mkdir())
        val factory = RequerySQLiteOpenHelperFactory(listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
            options.customExtensions.add(SQLiteCustomExtension(app.applicationInfo.nativeLibraryDir + "/libsimple", null))
            options
        }))
        val database = Room.databaseBuilder(app, AppDatabase::class.java, File(root, "room.db").path)
            .openHelperFactory(factory).build()
        createMessageFtsTables(database.openHelper.writableDatabase)
        val scope = AppScope()
        val peerApp = PeerApplication(app, root)
        val originalUploads = File(app.filesDir, "upload")
        originalUploads.listFiles().orEmpty().filter { it.name.startsWith("lan-roundtrip-") }.forEach {
            val target = File(peerApp.filesDir, "upload/${it.name}")
            target.parentFile!!.mkdirs()
            it.copyTo(target)
        }
        val settings = SettingsStore(peerApp, scope, File(root, "settings.preferences_pb"))
        val manager = FilesManager(peerApp, FilesRepository(database.managedFileDao()), scope, settings)
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, manager, MessageFtsManager(database), database.compressionCheckpointDao(), database.messageFileRefDao())
        val data = LanSyncData(peerApp, database, repository, settings, BackupArchiveEngine(settings, JsonInstant, peerApp, database), { false }, {})
        return RoomPeer(LanIdentity.load(alias), database, repository, data, LanObjectStore(File(root, "staging")), scope, peerApp, settings)
    }

    private class RoomPeer(
        val credentials: LanCredentials,
        val database: AppDatabase,
        val repository: ConversationRepository,
        val data: LanSyncData,
        val store: LanObjectStore,
        private val scope: AppScope,
        val app: Application,
        val settings: SettingsStore,
    ) : AutoCloseable {
        override fun close() {
            database.close()
            scope.cancel()
        }
    }

    private class PeerApplication(base: Application, private val root: File) : Application() {
        init { attachBaseContext(base) }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
