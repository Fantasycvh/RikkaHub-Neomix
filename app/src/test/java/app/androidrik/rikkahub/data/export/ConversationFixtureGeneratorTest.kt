package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.pathNodes
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Test
import java.io.File
import java.time.Instant as JavaInstant
import kotlin.uuid.Uuid

/**
 * 生成长会话测试夹具（不进入 APK，仅测试源集）。
 *
 * 运行：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*ConversationFixtureGeneratorTest*"
 * ```
 * 输出：`app/build/test-fixtures/`。设计见
 * `docs/design/in-progress/design-conversation-lazy-load.md` §十。
 */
class ConversationFixtureGeneratorTest {

    @Test
    fun generateFixtures() {
        val outDir = File(System.getProperty("fixture.out") ?: "build/test-fixtures")
        outDir.mkdirs()
        write(outDir, "conversation-linear-7000.json", linearConversation(title = "Fixture · 线性 7000"))
        write(outDir, "conversation-branched-7000.json", branchedConversation())
        write(outDir, "conversation-compressed-7000.json", compressedConversation())
    }

    private fun write(dir: File, name: String, conversation: Conversation) {
        val file = File(dir, name)
        val json = ConversationSerializer.toJson(conversation)
        // 导入侧走同一套 serializer：生成即校验，避免夹具到真机才发现不可解析。
        val parsed = ConversationSerializer.parse(json)
        check(parsed.messageNodes.size == conversation.messageNodes.size) { "$name node count mismatch" }
        check(parsed.pathNodes().size == conversation.pathNodes().size) { "$name path mismatch" }
        file.writeText(json, Charsets.UTF_8)
        println("fixture: ${file.absolutePath} (${file.length()} bytes, ${conversation.messageNodes.size} nodes)")
    }

    private fun linearConversation(title: String): Conversation {
        val nodes = ArrayList<MessageNode>(NODE_COUNT)
        var parent: Uuid? = null
        repeat(NODE_COUNT) { index ->
            val node = node(index, parent, branch = 0)
            nodes += node
            parent = node.id
        }
        return conversation(title, nodes, activeLeaf = nodes.last().id)
    }

    /** 每 [BRANCH_EVERY] 个节点挂一个兄弟分支节点，主链继续走原节点。 */
    private fun branchedConversation(): Conversation {
        val nodes = ArrayList<MessageNode>(NODE_COUNT + NODE_COUNT / BRANCH_EVERY)
        var parent: Uuid? = null
        repeat(NODE_COUNT) { index ->
            val main = node(index, parent, branch = 0)
            nodes += main
            if (index > 0 && index % BRANCH_EVERY == 0) {
                nodes += node(index, parent, branch = 1)
            }
            parent = main.id
        }
        return conversation("Fixture · 分支 7000", nodes, activeLeaf = nodes.last().id)
    }

    /** 前 6800 条被压缩，保留最后 200 条；检查点边界指向第 6800 条。 */
    private fun compressedConversation(): Conversation {
        val nodes = ArrayList<MessageNode>(NODE_COUNT)
        var parent: Uuid? = null
        repeat(NODE_COUNT) { index ->
            val node = node(index, parent, branch = 0)
            nodes += node
            parent = node.id
        }
        val conversationId = Uuid.random()
        val boundary = nodes[COMPRESSED_PREFIX - 1]
        val summary = CompressionSummary(
            conversationId = conversationId,
            content = """
                # 历史摘要（夹具）
                - 对话共 $COMPRESSED_PREFIX 条早期消息，主题为长会话加载性能验证。
                - 保留了任务目标、关键结论与未决问题，供懒加载默认窗口使用。
            """.trimIndent(),
            messageCount = COMPRESSED_PREFIX,
            boundaryNodeId = boundary.id.toString(),
            areaFingerprint = null,
        )
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            title = "Fixture · 压缩 7000",
            messageNodes = nodes,
            activeLeafNodeId = nodes.last().id,
            compressionSummaries = listOf(summary),
            createAt = BASE_TIME,
            updateAt = BASE_TIME.plusSeconds(NODE_COUNT.toLong()),
            lastMessageAt = BASE_TIME.plusSeconds(NODE_COUNT.toLong()),
        )
        check(conversation.activeCompression()?.messageCount == COMPRESSED_PREFIX) {
            "compressed fixture checkpoint is not active"
        }
        check(conversation.compressionWindow() != null) { "compressed fixture has no window" }
        return conversation
    }

    private fun conversation(
        title: String,
        nodes: List<MessageNode>,
        activeLeaf: Uuid,
    ) = Conversation(
        assistantId = Uuid.random(),
        title = title,
        messageNodes = nodes,
        activeLeafNodeId = activeLeaf,
        createAt = BASE_TIME,
        updateAt = BASE_TIME.plusSeconds(nodes.size.toLong()),
        lastMessageAt = BASE_TIME.plusSeconds(nodes.size.toLong()),
    )

    private fun node(index: Int, parent: Uuid?, branch: Int): MessageNode {
        val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
        val text = buildString {
            append("第 $index 条消息（branch=$branch）：")
            append("这是用于验证长会话懒加载与压缩窗口的占位内容，包含中英文混排与标点，长度保持稳定。")
            if (index % 100 == 0) {
                append("\n\n```kotlin\nfun fixture$index() = \"node $index\"\n```\n")
                append("\n| 字段 | 值 |\n|---|---|\n| node | $index |\n| branch | $branch |\n")
            }
        }
        val message = UIMessage(
            role = role,
            parts = listOf(UIMessagePart.Text(text)),
            createdAt = Instant.fromEpochSeconds(BASE_TIME.epochSecond + index, 0)
                .toLocalDateTime(TimeZone.UTC),
        )
        return MessageNode(
            parentId = parent,
            messages = listOf(message),
        )
    }

    private companion object {
        const val NODE_COUNT = 7000
        const val BRANCH_EVERY = 250
        const val COMPRESSED_PREFIX = 6800
        // 固定到 2026-12，保证导入后排在会话列表顶部，便于测试定位。
        val BASE_TIME: JavaInstant = JavaInstant.parse("2026-12-01T09:00:00Z")
    }
}
