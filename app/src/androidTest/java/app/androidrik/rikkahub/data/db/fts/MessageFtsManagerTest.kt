package app.androidrik.rikkahub.data.db.fts

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * 消息索引增量更新（上游 `90e48420f` 移植）：**只重写变了的消息**、按会话删除不扫全表、
 * 旧库打开时自动补建 `message_fts_rowid` 映射表。
 *
 * 必须在真机/模拟器上跑：`message_fts` 用 `simple` 分词器，依赖打进 APK 的 `libsimple.so`。
 */
@RunWith(AndroidJUnit4::class)
class MessageFtsManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var fts: MessageFtsManager
    private val assistantId = Uuid.random()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "message-fts-test-").toFile()
        open()
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun open() {
        val factory = RequerySQLiteOpenHelperFactory(
            listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null,
                        )
                    )
                    options
                }
            )
        )
        database = Room.databaseBuilder(context, AppDatabase::class.java, File(directory, "db").path)
            .openHelperFactory(factory)
            .build()
        // 生产环境里由 DataSourceModule 的 onOpen 回调建表，测试里手动对齐
        createMessageFtsTables(database.openHelper.writableDatabase)
        fts = MessageFtsManager(database)
    }

    @Test
    fun savingOnlyRewritesChangedMessages() = runBlocking {
        val apple = UIMessage.user("apple pie")
        val banana = UIMessage.assistant("banana split")
        val cherry = UIMessage.user("cherry tart")
        val conversation = conversation("first", apple, banana, cherry)
        save(conversation, insert = true)
        val before = indexedRowids()

        val durian = banana.copy(parts = UIMessage.assistant("durian cake").parts)
        val elderberry = UIMessage.assistant("elderberry jam")
        save(
            conversation.copy(
                title = "renamed",
                messageNodes = conversation.messageNodes.take(1) +
                    MessageNode.of(durian).copy(id = conversation.messageNodes[1].id) +
                    MessageNode.of(elderberry),
            ),
            insert = false,
        )
        val after = indexedRowids()

        assertEquals(before[apple.id.toString()], after[apple.id.toString()])
        assertNotEquals(before[banana.id.toString()], after[banana.id.toString()])
        assertEquals(setOf(apple.id, banana.id, elderberry.id).map { it.toString() }.toSet(), after.keys)
        assertRowidTableMatches()
        assertEquals(emptyList<String>(), fts.search("banana").map { it.messageId })
        assertEquals(emptyList<String>(), fts.search("cherry").map { it.messageId })
        val result = fts.search("durian").single()
        assertEquals(banana.id.toString(), result.messageId)
        assertEquals("renamed", result.title)
    }

    @Test
    fun deletingConversationKeepsOthers() = runBlocking {
        val kept = conversation("kept", UIMessage.user("shared word kept"))
        val deleted = conversation("deleted", UIMessage.user("shared word deleted"))
        save(kept, insert = true)
        save(deleted, insert = true)

        fts.deleteConversation(deleted.id.toString())

        assertEquals(listOf(kept.id.toString()), fts.search("shared").map { it.conversationId })
        assertRowidTableMatches()
    }

    @Test
    fun existingIndexGetsRowidTableOnOpen() = runBlocking {
        val first = conversation("first", UIMessage.user("legacy apple"))
        val second = conversation("second", UIMessage.user("legacy banana"))
        save(first, insert = true)
        save(second, insert = true)
        database.openHelper.writableDatabase.execSQL("DROP TABLE message_fts_rowid")
        database.close()

        open()
        assertRowidTableMatches()
        fts.deleteConversation(first.id.toString())
        assertEquals(listOf(second.id.toString()), fts.search("legacy").map { it.conversationId })
    }

    private fun conversation(title: String, vararg messages: UIMessage) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = messages.map { MessageNode.of(it) },
    )

    private suspend fun save(conversation: Conversation, insert: Boolean) {
        val entity = ConversationEntity(
            id = conversation.id.toString(),
            assistantId = conversation.assistantId.toString(),
            title = conversation.title,
            nodes = "[]",
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            chatSuggestions = "[]",
            isPinned = false,
        )
        if (insert) {
            database.conversationDao().insert(entity)
        } else {
            // 本仓刻意不提供整行 @Update（会把 sort_order 写回内存态），标题走显式列更新
            database.conversationDao().updateTitle(conversation.id.toString(), conversation.title)
        }
        fts.indexConversation(conversation)
    }

    private fun indexedRowids(): Map<String, Long> =
        database.openHelper.writableDatabase.query("SELECT message_id, rowid FROM message_fts").use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getLong(1)) }
        }

    private fun assertRowidTableMatches() {
        val query = "SELECT rowid, conversation_id FROM message_fts ORDER BY rowid"
        val mapped = "SELECT fts_rowid, conversation_id FROM message_fts_rowid ORDER BY fts_rowid"
        assertEquals(rows(query), rows(mapped))
    }

    private fun rows(sql: String): List<Pair<Long, String>> =
        database.openHelper.writableDatabase.query(sql).use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to it.getString(1)) }
        }
}
