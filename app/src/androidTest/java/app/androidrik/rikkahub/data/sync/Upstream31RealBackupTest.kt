package app.androidrik.rikkahub.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.datastore.migration.SettingsCompatDecoder
import app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/** Opt-in private fixture. Never checked into git; never prints user content or credentials. */
@RunWith(AndroidJUnit4::class)
class Upstream31RealBackupTest {
    @Test fun convertsAndReloadsPrivateBackupWithoutLosingCheckpointSemantics() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = InstrumentationRegistry.getArguments().getString("upstream31Backup")
        assumeTrue("Supply upstream31Backup to run private fixture verification", path != null)
        val input = File(requireNotNull(path))
        assertTrue("Private fixture missing", input.isFile)
        val staged = File(context.cacheDir, "upstream31-private-source.db")
        var converted: File? = null
        try {
            ZipFile(input).use { zip ->
                val raw = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use { it.readText() }
                val decoded = SettingsCompatDecoder.decode(JsonInstant, SettingsJsonMigrator.migrate(raw))
                val root = JsonInstant.parseToJsonElement(raw).jsonObject
                val legacy = root["modeInjections"]?.jsonArray
                if (legacy != null && "promptPresets" !in root) {
                    assertEquals(legacy.size, decoded.settings.promptPresets.sumOf { it.entries.size })
                }
                zip.getInputStream(zip.getEntry("rikka_hub.db")).use { source ->
                    staged.outputStream().use { source.copyTo(it) }
                }
            }
            val source = android.database.sqlite.SQLiteDatabase.openDatabase(
                staged.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            val ids = mutableListOf<String>()
            val expectedConfigs = mutableMapOf<String, app.androidrik.rikkahub.data.model.UpstreamConversationConfig?>()
            var expectedCheckpoints = 0
            val expectedCheckpointText = mutableMapOf<String, String>()
            source.use { sqlite ->
                sqlite.rawQuery("select id, config from ConversationEntity", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        ids += id
                        expectedConfigs[id] = app.androidrik.rikkahub.data.model.UpstreamConversationConfig
                            .fromLegacyJson(if (cursor.isNull(1)) null else cursor.getString(1))
                    }
                }
                sqlite.rawQuery("select messages from message_node", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val checkpoints = JsonInstant.parseToJsonElement(cursor.getString(0)).jsonArray.filter {
                            it.jsonObject["isContextCheckpoint"]?.jsonPrimitive?.content == "true"
                        }
                        expectedCheckpoints += checkpoints.size
                        checkpoints.forEach {
                            val obj = it.jsonObject
                            expectedCheckpointText[obj.getValue("id").jsonPrimitive.content] =
                                obj.getValue("parts").jsonArray.joinToString("\n") { part ->
                                    part.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                                }
                        }
                    }
                }
            }
            assertTrue("Expected checkpoint fixture", expectedCheckpoints > 0)
            converted = LegacyBackupConverter(context).convert(staged)
            val db = Room.databaseBuilder(context, AppDatabase::class.java, converted!!.path).build()
            try {
                // Production creates FTS5 via its custom tokenizer callback. This isolated framework
                // SQLite fixture only needs the index write contract, not tokenizer/search behavior.
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TABLE IF NOT EXISTS message_fts (text TEXT, node_id TEXT, message_id TEXT, " +
                        "conversation_id TEXT, title TEXT, update_at INTEGER)",
                )
                val repository = ConversationRepository(
                    db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
                    GlobalContext.get().get<FilesManager>(), MessageFtsManager(db), db.compressionCheckpointDao(),
                )
                var loadedCheckpoints = 0
                var configCount = 0
                for (id in ids) {
                    val conversation = requireNotNull(repository.getConversationById(Uuid.parse(id)))
                    loadedCheckpoints += conversation.messageNodes.sumOf { node -> node.messages.count { it.isContextCheckpoint } }
                    conversation.messageNodes.flatMap { it.messages }.filter { it.isContextCheckpoint }.forEach {
                        assertEquals("Checkpoint text changed", expectedCheckpointText[it.id.toString()], it.toText())
                    }
                    if (conversation.upstreamConfig != null) configCount++
                    assertEquals("Conversation configuration changed", expectedConfigs[id], conversation.upstreamConfig)
                    val checkpointIndex = conversation.currentMessages.indexOfLast { it.isContextCheckpoint }
                    val expectedWindow = if (checkpointIndex < 0) conversation.currentMessages
                        else conversation.currentMessages.drop(checkpointIndex)
                    assertEquals("Imported request window differs", expectedWindow, conversation.requestWindowMessages())
                    repository.updateConversation(conversation)
                    val reloaded = requireNotNull(repository.getConversationById(Uuid.parse(id)))
                    assertEquals(conversation.upstreamConfig, reloaded.upstreamConfig)
                    assertEquals(conversation.messageNodes, reloaded.messageNodes)
                    assertEquals(expectedWindow, reloaded.requestWindowMessages())
                }
                assertEquals(expectedCheckpoints, loadedCheckpoints)
                assertTrue("Expected conversation config fixture", configCount > 0)
            } finally {
                db.close()
            }
        } finally {
            staged.delete()
            converted?.let { context.deleteDatabase(it.name) }
        }
    }
}
