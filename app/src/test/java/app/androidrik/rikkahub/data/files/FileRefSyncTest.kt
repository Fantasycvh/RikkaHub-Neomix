package app.androidrik.rikkahub.data.files

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.dao.MessageFileRefDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.entity.MessageFileRefEntity
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.model.MessageNode
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 文件→会话关联同步语义测试（design-file-conversation-link.md §7.3：回填幂等 + diff 重写幂等）。
 *
 * 惯例同 ScheduledTaskRepositoryTest：不引 Robolectric，Fake DAO 在内存里复刻 SQL 语义
 * （IGNORE 冲突去重 / 按键删除），SQL 本身由 Room 编译期校验 + 真机验证兜底。
 */
class FileRefSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private val refDAO = FakeMessageFileRefDAO()
    private val nodeDAO = FakeMessageNodeDAO()

    private fun newSync(): FileRefSync {
        filesDir = tmp.newFolder("files")
        File(filesDir, "upload").mkdirs()
        return FileRefSync(nodeDAO, refDAO, filesDir)
    }

    private fun fileUrl(name: String): String =
        "file://" + File(filesDir, "upload/$name").absolutePath.replace('\\', '/')

    private fun nodeWithFiles(vararg names: String): MessageNode = MessageNode(
        id = Uuid.random(),
        messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = names.map { UIMessagePart.Image(url = fileUrl(it)) },
            ),
        ),
    )

    private fun entityRowJson(files: List<String>): String =
        """[{"parts":[${files.joinToString(",") { """{"type":"image","url":"${fileUrl(it)}"}""" }}]}]"""

    // ---- 全量保存（isSparse=false）：diff 重写 ----

    @Test
    fun `full save rewrite is idempotent`() = runTest {
        val sync = newSync()
        val node = nodeWithFiles("a.png", "b.jpg")
        val convId = Uuid.random().toString()
        sync.syncOnSave(convId, listOf(node), emptyList(), sparse = false)
        val first = refDAO.snapshot()
        sync.syncOnSave(convId, listOf(node), emptyList(), sparse = false)
        val second = refDAO.snapshot()
        assertEquals(first.map { it.conversationId to it.relativePath }.toSet(),
            second.map { it.conversationId to it.relativePath }.toSet())
        assertEquals(setOf("upload/a.png", "upload/b.jpg"), second.map { it.relativePath }.toSet())
        // 每行唯一（无重复累积）
        assertEquals(second.size, second.map { it.conversationId to it.relativePath }.distinct().size)
    }

    @Test
    fun `full save removes stale refs after edit`() = runTest {
        val sync = newSync()
        val convId = Uuid.random().toString()
        sync.syncOnSave(convId, listOf(nodeWithFiles("a.png", "b.jpg")), emptyList(), sparse = false)
        // 编辑后只剩 a.png
        sync.syncOnSave(convId, listOf(nodeWithFiles("a.png")), emptyList(), sparse = false)
        assertEquals(setOf("upload/a.png"), refDAO.snapshot().filter { it.conversationId == convId }.map { it.relativePath }.toSet())
    }

    @Test
    fun `full save with no refs clears conversation refs but keeps others`() = runTest {
        val sync = newSync()
        val convA = Uuid.random().toString()
        val convB = Uuid.random().toString()
        sync.syncOnSave(convA, listOf(nodeWithFiles("a.png")), emptyList(), sparse = false)
        sync.syncOnSave(convB, listOf(nodeWithFiles("b.jpg")), emptyList(), sparse = false)
        // convA 编辑成纯文本
        sync.syncOnSave(convA, listOf(MessageNode(id = Uuid.random(), messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("no files"))),
        ))), emptyList(), sparse = false)
        assertTrue(refDAO.snapshot().none { it.conversationId == convA })
        assertTrue(refDAO.snapshot().any { it.conversationId == convB && it.relativePath == "upload/b.jpg" })
    }

    // ---- 稀疏保存（isSparse=true）：增量合并 + 被删节点精确移除 ----

    @Test
    fun `sparse save merges without clearing unloaded prefix refs`() = runTest {
        val sync = newSync()
        val convId = Uuid.random().toString()
        val prefixNode = nodeWithFiles("old.png")
        val tailNode = nodeWithFiles("new.png")
        // 第一次全量（模拟前缀已在库里）
        sync.syncOnSave(convId, listOf(prefixNode, tailNode), emptyList(), sparse = false)
        // 稀疏保存只带尾部节点
        sync.syncOnSave(convId, listOf(tailNode), emptyList(), sparse = true)
        val paths = refDAO.snapshot().filter { it.conversationId == convId }.map { it.relativePath }.toSet()
        assertEquals(setOf("upload/old.png", "upload/new.png"), paths)
    }

    @Test
    fun `sparse save removes only deleted nodes refs`() = runTest {
        val sync = newSync()
        val convId = Uuid.random().toString()
        val removed = nodeWithFiles("gone.png")
        val kept = nodeWithFiles("kept.png")
        sync.syncOnSave(convId, listOf(removed, kept), emptyList(), sparse = false)
        // 库里的被删节点行（syncOnSave 内部经 getNodesByIds 读出）
        nodeDAO.rows[removed.id.toString()] = MessageNodeEntity(
            id = removed.id.toString(),
            conversationId = convId,
            nodeIndex = 0,
            messages = entityRowJson(listOf("gone.png")),
            selectIndex = 0,
        )
        sync.syncOnSave(convId, listOf(kept), listOf(removed.id), sparse = true)
        val paths = refDAO.snapshot().filter { it.conversationId == convId }.map { it.relativePath }.toSet()
        assertEquals(setOf("upload/kept.png"), paths)
    }

    @Test
    fun `sparse save with unknown deleted nodes is a no-op`() = runTest {
        val sync = newSync()
        val convId = Uuid.random().toString()
        val node = nodeWithFiles("a.png")
        sync.syncOnSave(convId, listOf(node), emptyList(), sparse = false)
        sync.syncOnSave(convId, listOf(node), listOf(Uuid.random()), sparse = true)
        assertEquals(setOf("upload/a.png"), refDAO.snapshot().map { it.relativePath }.toSet())
    }

    // ---- 回填 / 兜底灌库 ----

    @Test
    fun `rewriteFromScan is idempotent`() = runTest {
        val sync = newSync()
        val scan = mapOf(
            "upload/a.png" to setOf("conv-1", "conv-2"),
            "upload/b.jpg" to setOf("conv-1"),
        )
        sync.rewriteFromScan(scan)
        val first = refDAO.snapshot().map { it.conversationId to it.relativePath }.toSet()
        sync.rewriteFromScan(scan)
        val second = refDAO.snapshot().map { it.conversationId to it.relativePath }.toSet()
        assertEquals(first, second)
        assertEquals(setOf(("conv-1" to "upload/a.png"), ("conv-2" to "upload/a.png"), ("conv-1" to "upload/b.jpg")), second)
    }

    @Test
    fun `rewriteFromScan replaces previous content`() = runTest {
        val sync = newSync()
        sync.rewriteFromScan(mapOf("upload/stale.png" to setOf("conv-x")))
        sync.rewriteFromScan(mapOf("upload/fresh.png" to setOf("conv-y")))
        assertEquals(setOf(("conv-y" to "upload/fresh.png")), refDAO.snapshot().map { it.conversationId to it.relativePath }.toSet())
    }

    @Test
    fun `mergeFromScan is idempotent and additive`() = runTest {
        val sync = newSync()
        val scan = mapOf("upload/a.png" to setOf("conv-1"))
        sync.mergeFromScan(scan)
        sync.mergeFromScan(scan)
        assertEquals(
            listOf(("conv-1" to "upload/a.png")),
            refDAO.snapshot().map { it.conversationId to it.relativePath },
        )
        // 合并不清表：已有行保留
        sync.mergeFromScan(mapOf("upload/b.jpg" to setOf("conv-2")))
        assertEquals(2, refDAO.snapshot().size)
    }

    // ---- Fakes ----

    private class FakeMessageFileRefDAO : MessageFileRefDAO {
        private val rows = LinkedHashMap<Pair<String, String>, MessageFileRefEntity>()
        private val flow = MutableStateFlow(0L)

        fun snapshot(): List<MessageFileRefEntity> = rows.values.toList()

        private fun bump() { flow.value += 1 }

        override fun observeAll(): Flow<List<MessageFileRefEntity>> =
            flow.map { rows.values.toList() }

        override suspend fun getByPath(relativePath: String): List<MessageFileRefEntity> =
            rows.values.filter { it.relativePath == relativePath }

        override suspend fun getConversationIdsOfPath(relativePath: String): List<String> =
            rows.values.filter { it.relativePath == relativePath }.map { it.conversationId }

        override suspend fun deleteByConversation(conversationId: String) {
            rows.keys.removeAll { it.first == conversationId }
            bump()
        }

        override suspend fun deleteRef(conversationId: String, relativePath: String) {
            rows.remove(conversationId to relativePath)
            bump()
        }

        /** 复刻 `INSERT OR IGNORE`：PK（conversation_id, relative_path）冲突静默跳过 */
        override suspend fun insertAll(refs: List<MessageFileRefEntity>) {
            refs.forEach { rows.putIfAbsent(it.conversationId to it.relativePath, it) }
            bump()
        }

        override suspend fun deleteAll() {
            rows.clear()
            bump()
        }
    }

    /** 仅 getNodesByIds 有实现；其余方法在 FileRefSync 路径上不可达 */
    private class FakeMessageNodeDAO : MessageNodeDAO {
        val rows = LinkedHashMap<String, MessageNodeEntity>()

        override suspend fun getNodesByIds(nodeIds: List<String>): List<MessageNodeEntity> =
            nodeIds.mapNotNull { rows[it] }

        override suspend fun countAll(): Int = error("unused")
        override suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity> = error("unused")
        override suspend fun getNodesOfConversationPaged(conversationId: String, limit: Int, offset: Int): List<MessageNodeEntity> = error("unused")
        override suspend fun getNodeSummaries(conversationId: String): List<app.androidrik.rikkahub.data.db.dao.MessageNodeSummaryRow> = error("unused")
        override suspend fun getNodeTreeRows(conversationId: String): List<app.androidrik.rikkahub.data.db.dao.MessageNodeTreeRow> = error("unused")
        override suspend fun getNodesBefore(conversationId: String, beforeIndex: Int, limit: Int): List<MessageNodeEntity> = error("unused")
        override suspend fun insertAll(nodes: List<MessageNodeEntity>) = error("unused")
        override suspend fun insert(node: MessageNodeEntity) = error("unused")
        override suspend fun update(node: MessageNodeEntity) = error("unused")
        override suspend fun deleteByConversation(conversationId: String) = error("unused")
        override suspend fun deleteById(nodeId: String) = error("unused")
        override suspend fun getNodeDiffRows(conversationId: String): List<app.androidrik.rikkahub.data.db.dao.MessageNodeDiffRow> = error("unused")
        override suspend fun deleteByIds(conversationId: String, nodeIds: List<String>) = error("unused")
        override suspend fun getNodeIndexOf(nodeId: String): Int? = error("unused")
        override suspend fun getMaxNodeIndex(conversationId: String): Int = error("unused")
        override suspend fun hasFileReference(encodedFileUrl: String): Boolean = error("unused")
        override suspend fun getAllRefRows(): List<app.androidrik.rikkahub.data.db.dao.MessageNodeRefRow> = error("unused")
    }
}
