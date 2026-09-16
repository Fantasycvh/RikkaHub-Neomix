package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 导入会话的 id 重映射与压缩检查点迁移（`docs/design-conversation-import-export.md` §三/§5.1）。
 *
 * 两条铁律：
 * - **id 全换**：会话/节点/消息都换（FTS 存 node_id 与 message_id），但 **toolCallId 不换**
 *   （批内自洽、整批搬即自洽）；
 * - **检查点迁移**：`boundaryNodeId` 必须换到新节点、`areaFingerprint` 必须在**新节点序列上重算**
 *   （只换其一 → 检查点失效）。
 */
class ConversationImportRemapTest {

    private val oldAssistant = Uuid.random()
    private val targetAssistant = Uuid.random()

    private fun textMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun toolMessage(toolCallId: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = "memory_tool",
                input = "{}",
                output = listOf(UIMessagePart.Text("ok")),
            )
        ),
    )

    /** 三节点会话：text / tool / text（tool 用于验证 toolCallId 不被改写）。 */
    private fun conversation(): Conversation = Conversation(
        id = Uuid.random(),
        assistantId = oldAssistant,
        title = "测试会话",
        messageNodes = listOf(
            MessageNode.of(textMessage("u1")),
            MessageNode.of(toolMessage("call_abc")),
            MessageNode.of(textMessage("u2")),
        ),
    )

    // ---- id 换新 ----

    @Test
    fun `conversation node and message ids are all regenerated`() {
        val source = conversation()
        val result = source.remapForImport(targetAssistant)

        assertNotEquals("会话 id 必须换", source.id, result.id)
        source.messageNodes.zip(result.messageNodes).forEach { (old, new) ->
            assertNotEquals("节点 id 必须换", old.id, new.id)
            assertNotEquals(
                "消息 id 必须换（FTS 存 message_id）",
                old.currentMessage.id,
                new.currentMessage.id,
            )
        }
    }

    @Test
    fun `all ids remain unique after remap`() {
        val source = conversation()
        val result = source.remapForImport(targetAssistant)
        val nodeIds = result.messageNodes.map { it.id }
        val messageIds = result.messageNodes.flatMap { node -> node.messages.map { it.id } }
        assertEquals("节点 id 不应重复", nodeIds.size, nodeIds.toSet().size)
        assertEquals("消息 id 不应重复", messageIds.size, messageIds.toSet().size)
    }

    @Test
    fun `tool call id is preserved so the batch stays self-consistent`() {
        val source = conversation()
        val result = source.remapForImport(targetAssistant)

        val oldTool = source.messageNodes[1].currentMessage.parts.first() as UIMessagePart.Tool
        val newTool = result.messageNodes[1].currentMessage.parts.first() as UIMessagePart.Tool
        assertEquals("toolCallId 属于批内引用，必须保持不变", oldTool.toolCallId, newTool.toolCallId)
    }

    @Test
    fun `content is carried over verbatim`() {
        val source = conversation()
        val result = source.remapForImport(targetAssistant)

        val oldTexts = source.messageNodes.map { node ->
            node.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        }
        val newTexts = result.messageNodes.map { node ->
            node.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        }
        assertEquals(oldTexts, newTexts)
        assertEquals(source.title, result.title)
    }

    // ---- 助手：以用户选择为准 ----

    @Test
    fun `assistant is replaced by the chosen target`() {
        val result = conversation().remapForImport(targetAssistant)
        assertEquals(targetAssistant, result.assistantId)
        assertNotEquals(oldAssistant, result.assistantId)
    }

    // ---- 压缩检查点迁移（方案 A） ----

    private fun conversationWithCheckpoint(): Pair<Conversation, CompressionSummary> {
        val base = conversation()
        // 边界 = 第 2 个节点（index 1）：被摘要区域 = nodes[0..1]
        val boundaryIndex = 1
        val fingerprint = base.areaFingerprintFor(boundaryIndex)
        assertNotNull("前置：基准会话应能算出指纹", fingerprint)
        val summary = CompressionSummary(
            conversationId = base.id,
            content = "## 摘要\n- 早期对话",
            messageCount = 2,
            boundaryNodeId = base.messageNodes[boundaryIndex].id.toString(),
            areaFingerprint = fingerprint,
        )
        return base.copy(compressionSummaries = listOf(summary)) to summary
    }

    @Test
    fun `checkpoint boundary is remapped to the new node id`() {
        val (source, summary) = conversationWithCheckpoint()
        val result = source.remapForImport(targetAssistant)

        assertEquals(1, result.compressionSummaries.size)
        val migrated = result.compressionSummaries.single()
        val newBoundaryNode = result.messageNodes[1]

        assertEquals(
            "boundaryNodeId 必须指向新节点",
            newBoundaryNode.id.toString(),
            migrated.boundaryNodeId,
        )
        assertNotEquals(
            "不能沿用旧节点 id（新会话里不存在）",
            summary.boundaryNodeId,
            migrated.boundaryNodeId,
        )
    }

    @Test
    fun `checkpoint fingerprint is recomputed so it stays valid`() {
        val (source, _) = conversationWithCheckpoint()
        val result = source.remapForImport(targetAssistant)

        val migrated = result.compressionSummaries.single()
        val expected = result.areaFingerprintFor(boundaryNodeIndex = 1)

        assertNotNull("重算后指纹不应为 null", migrated.areaFingerprint)
        assertEquals("指纹必须在新节点序列上重算", expected, migrated.areaFingerprint)
        assertNotEquals(
            "旧指纹（在旧 id 上算的）必然失配，不能沿用",
            source.compressionSummaries.single().areaFingerprint,
            migrated.areaFingerprint,
        )
    }

    @Test
    fun `migrated checkpoint is actually active on the imported conversation`() {
        // 这是 A 方案的最终目的：导入后检查点【立即有效】→ 发送上下文是压缩后的大小
        val (source, _) = conversationWithCheckpoint()
        assertNotNull("前置：原会话检查点有效", source.activeCompression())

        val result = source.remapForImport(targetAssistant)

        assertNotNull("导入后检查点必须仍然有效（方案 A 的目的）", result.activeCompression())
    }

    @Test
    fun `checkpoint conversation id points at the new conversation`() {
        val (source, _) = conversationWithCheckpoint()
        val result = source.remapForImport(targetAssistant)
        assertEquals(
            "检查点的 conversationId 必须指向新会话（独立表 + CASCADE 外键）",
            result.id,
            result.compressionSummaries.single().conversationId,
        )
    }

    @Test
    fun `checkpoint display fields are carried over`() {
        val (source, summary) = conversationWithCheckpoint()
        val result = source.remapForImport(targetAssistant)
        val migrated = result.compressionSummaries.single()

        assertEquals("摘要正文不受换 id 影响", summary.content, migrated.content)
        assertEquals("messageCount 仅供展示/累计，原样保留", summary.messageCount, migrated.messageCount)
        assertEquals(summary.createdAt, migrated.createdAt)
    }

    @Test
    fun `checkpoint is dropped when its boundary node is missing`() {
        // 导出文件与检查点不一致（边界节点不在节点列表里）→ 丢弃，避免留无效行
        val base = conversation()
        val orphan = CompressionSummary(
            conversationId = base.id,
            content = "orphan",
            messageCount = 1,
            boundaryNodeId = Uuid.random().toString(),
            areaFingerprint = "deadbeef",
        )
        val source = base.copy(compressionSummaries = listOf(orphan))

        val result = source.remapForImport(targetAssistant)
        assertTrue("找不到边界的检查点应被丢弃", result.compressionSummaries.isEmpty())
    }

    @Test
    fun `conversation without checkpoints stays without checkpoints`() {
        val result = conversation().remapForImport(targetAssistant)
        assertTrue(result.compressionSummaries.isEmpty())
    }

    @Test
    fun `remap is repeatable producing distinct copies`() {
        // 决策 1 的直接后果：同一文件导入两次得到两个独立副本
        val source = conversation()
        val first = source.remapForImport(targetAssistant)
        val second = source.remapForImport(targetAssistant)

        assertNotEquals("两次导入必须是不同会话", first.id, second.id)
        assertNotEquals(
            first.messageNodes.first().id,
            second.messageNodes.first().id,
        )
    }

    // ---- 序列化往返 ----

    @Test
    fun `export carries sourceId as the idempotency anchor`() {
        val source = conversation()
        val exported = ConversationSerializer.export(source)

        assertEquals("conversation", exported.type)
        assertEquals(
            "sourceId 必须是导出时刻的会话 id（重复导入检测的锚点）",
            source.id.toString(),
            exported.sourceId,
        )
        assertNotNull("导出应带时间戳", exported.exportedAt)
    }

    @Test
    fun `round trip preserves content and checkpoints before remap`() {
        val (source, _) = conversationWithCheckpoint()
        val json = ConversationSerializer.toJson(source)
        val parsed = ConversationSerializer.parse(json)

        assertEquals(source.id, parsed.id)
        assertEquals(source.title, parsed.title)
        assertEquals(source.messageNodes.size, parsed.messageNodes.size)
        assertEquals(
            "检查点应随文件带出（方案 A：携带而非丢弃）",
            1,
            parsed.compressionSummaries.size,
        )
        assertEquals(
            source.compressionSummaries.single().boundaryNodeId,
            parsed.compressionSummaries.single().boundaryNodeId,
        )
    }

    @Test
    fun `export of unknown type is rejected on parse`() {
        val bogus = """{"version":1,"type":"something-else","data":{}}"""
        val error = runCatching { ConversationSerializer.parse(bogus) }.exceptionOrNull()
        assertNotNull("类型不符必须报错而非静默解析", error)
        assertTrue(
            "错误信息应说明类型不符",
            error!!.message.orEmpty().contains("conversation"),
        )
    }

    @Test
    fun `sourceId is null-tolerant for legacy files`() {
        // 旧版导出文件没有 sourceId → 反序列化应为 null（导入侧据此跳过重复检测）
        val legacy = """{"version":1,"type":"conversation","data":{"assistantId":"${Uuid.random()}",
            "title":"t","messageNodes":[]}}""".trimIndent()
        val exportData = JsonInstant.decodeFromString(ExportData.serializer(), legacy)
        assertNull("旧文件的 sourceId 应为 null", exportData.sourceId)
        assertNull(exportData.exportedAt)
    }

    @Test
    fun `blank title is preserved as empty`() {
        val source = conversation().copy(title = "")
        val parsed = ConversationSerializer.parse(ConversationSerializer.toJson(source))
        assertEquals("", parsed.title)
        assertFalse(parsed.title.isNotBlank())
    }

    // ---- 重复导入检测（importedSources 映射，零迁移） ----

    @Test
    fun `imported source record survives settings round trip`() {
        // importedSources 是 @Serializable 顶层字段，随 Settings JSON 自动持久化（无需三件套读写字段）
        val record = ImportSourceRecord(importedAt = 1_700_000_000_000L, title = "逆向工作台")
        val encoded = JsonInstant.encodeToString(ImportSourceRecord.serializer(), record)
        val decoded = JsonInstant.decodeFromString(ImportSourceRecord.serializer(), encoded)
        assertEquals(record, decoded)
    }

    @Test
    fun `duplicate detection keys on sourceId not on conversation id`() {
        // 换新 id 后 existsConversationById 永不命中 —— 幂等锚点只能是导出时的 sourceId
        val source = conversation()
        val sourceId = source.id.toString()
        val imported = source.remapForImport(targetAssistant)
        assertNotEquals("导入后会话 id 必须换新", sourceId, imported.id.toString())

        val records = mapOf(
            sourceId to ImportSourceRecord(importedAt = 123L, title = imported.title),
        )
        assertNotNull("同一文件再次导入应能命中记录", records[sourceId])
        assertNull("新会话 id 不应命中（它不是锚点）", records[imported.id.toString()])
    }

    @Test
    fun `title is stored so the warning can name the original conversation`() {
        val source = conversation().copy(title = "逆向工作台")
        val imported = source.remapForImport(targetAssistant)
        assertEquals("逆向工作台", imported.title)
        val record = ImportSourceRecord(importedAt = 1L, title = imported.title.ifBlank { null })
        assertEquals("逆向工作台", record.title)
    }

    @Test
    fun `blank title degrades to null so the warning falls back to the file title`() {
        val imported = conversation().copy(title = "   ").remapForImport(targetAssistant)
        val record = ImportSourceRecord(importedAt = 1L, title = imported.title.ifBlank { null })
        assertNull(record.title)
    }

    @Test
    fun `legacy settings without importedSources decodes to an empty map`() {
        // 旧备份的 settings JSON 无该字段 → ignoreUnknownKeys/默认值使其为 emptyMap，不抛
        val legacy = """{"init":false}"""
        val settings = JsonInstant.decodeFromString(
            app.androidrik.rikkahub.data.datastore.Settings.serializer(),
            legacy,
        )
        assertTrue("缺少字段时应为空映射", settings.importedSources.isEmpty())
    }
}
