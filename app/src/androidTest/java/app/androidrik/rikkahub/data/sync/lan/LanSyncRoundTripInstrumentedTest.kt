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

    private suspend fun round(address: Inet4Address, left: RoomPeer, right: RoomPeer, interrupt: Boolean = false) =
        coroutineScope {
            val token = newPairToken()
            val now = System.currentTimeMillis()
            left.data.capture(left.credentials.fingerprint, "Left", now).use { local ->
                right.data.capture(right.credentials.fingerprint, "Right", now).use { remote ->
                    val uploads = LanSyncUploadSession(remote.manifest, right.store, JsonInstant, { now }, remote.files) { manifest, plan ->
                        right.data.apply(remote, manifest, plan, right.store)
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
                            val sent = client.push(outgoing, local.files)
                            val received = client.pull(plan.objectsFrom(remote.manifest), left.store, localObjects = local.files)
                            assertTrue(sent.remainingObjects.isEmpty() && received.remainingObjects.isEmpty())
                            assertTrue(sent.transferredBytes < LAN_CHUNK_BYTES && received.transferredBytes < LAN_CHUNK_BYTES)
                            val other = client.finish()
                            assertEquals(other, client.finish())
                            left.data.apply(local, offer.manifest, plan, left.store) to other
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
        val settings = SettingsStore(app, scope, File(root, "settings.preferences_pb"))
        val manager = FilesManager(app, FilesRepository(database.managedFileDao()), scope, settings)
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, manager, MessageFtsManager(database), database.compressionCheckpointDao(), database.messageFileRefDao())
        val data = LanSyncData(app, database, repository, settings, BackupArchiveEngine(settings, JsonInstant, app, database), { false }, {})
        return RoomPeer(LanIdentity.load(alias), database, repository, data, LanObjectStore(File(root, "staging")), scope)
    }

    private class RoomPeer(
        val credentials: LanCredentials,
        val database: AppDatabase,
        val repository: ConversationRepository,
        val data: LanSyncData,
        val store: LanObjectStore,
        private val scope: AppScope,
    ) : AutoCloseable {
        override fun close() {
            database.close()
            scope.cancel()
        }
    }
}
