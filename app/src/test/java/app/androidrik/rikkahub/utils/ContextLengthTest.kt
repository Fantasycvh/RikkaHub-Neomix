package app.androidrik.rikkahub.utils

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.Instant as JavaInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextLengthTest {

    // ===== parseContextLengthInput =====

    @Test
    fun `parses k and m suffixes and plain numbers`() {
        assertEquals(256_000, parseContextLengthInput("256k"))
        assertEquals(1_000_000, parseContextLengthInput("1M"))
        assertEquals(2_500_000, parseContextLengthInput("2.5m"))
        assertEquals(262_144, parseContextLengthInput("262144"))
        assertEquals(128_000, parseContextLengthInput("128 k"))
    }

    @Test
    fun `rejects invalid input`() {
        assertNull(parseContextLengthInput(""))
        assertNull(parseContextLengthInput("abc"))
        assertNull(parseContextLengthInput("0"))
        assertNull(parseContextLengthInput("-5k"))
        assertNull(parseContextLengthInput("100000001"))
    }

    // ===== formatContextLength =====

    @Test
    fun `formats compact token counts`() {
        assertEquals("1M", formatContextLength(1_000_000))
        assertEquals("2.5M", formatContextLength(2_500_000))
        assertEquals("256K", formatContextLength(256_000))
        assertEquals("999", formatContextLength(999))
        assertEquals("", formatContextLength(null))
    }

    // ===== effectiveContextLength =====

    @Test
    fun `effective context length falls back to default`() {
        assertEquals(DEFAULT_CONTEXT_LENGTH, Model(modelId = "m").effectiveContextLength())
        assertEquals(128_000, Model(modelId = "m", contextLength = 128_000).effectiveContextLength())
    }

    @Test
    fun `effective context length priority custom builtin override default`() {
        val model = Model(modelId = "m", contextLength = 128_000)
        // custom 优先于 built-in
        assertEquals(64_000, model.copy(customContextLength = 64_000).effectiveContextLength())
        // built-in 优先于全局 override
        assertEquals(128_000, model.effectiveContextLength(globalOverride = 32_000))
        // 全局 override 在无 built-in/custom 时生效
        assertEquals(
            32_000,
            Model(modelId = "m").effectiveContextLength(globalOverride = 32_000),
        )
        // 全部缺失走 256K
        assertEquals(DEFAULT_CONTEXT_LENGTH, Model(modelId = "m").effectiveContextLength())
    }

    // ===== estimateTokenCount =====

    @Test
    fun `estimates cjk heavier than latin`() {
        val latin = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("abcd")))
        val cjk = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好世界")))
        // 4 拉丁字符 -> ~1 token；4 CJK 字符 -> ~2.67 token
        assertEquals(1, estimateTokenCount(listOf(latin)))
        assertEquals(2, estimateTokenCount(listOf(cjk)))
    }

    @Test
    fun `includes tool output in estimate`() {
        val tool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "workspace_read_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("file content here")),
                )
            ),
        )
        // "file content here" = 18 个非 CJK 字符 -> 4 token
        assertEquals(4, estimateTokenCount(listOf(tool)))
    }

    @Test
    fun `empty messages estimate zero`() {
        assertEquals(0, estimateTokenCount(emptyList()))
    }

    // ===== estimateWindowTokens =====

    private fun conversationWith(messages: List<UIMessage>) = Conversation(
        id = kotlin.uuid.Uuid.random(),
        assistantId = kotlin.uuid.Uuid.random(),
        messageNodes = messages.map { MessageNode(messages = listOf(it)) },
    )

    @Test
    fun `uses real usage when present`() {
        val last = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(promptTokens = 12_345, completionTokens = 100, totalTokens = 12_445),
        )
        val conversation = conversationWith(
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
                last,
            )
        )
        assertEquals(12_345, conversation.estimateWindowTokens(null))
    }

    @Test
    fun `falls back to local estimate without usage`() {
        val conversation = conversationWith(
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello world"))),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))),
            )
        )
        // 11 + 2 个非 CJK 字符 -> 3 token
        assertEquals(3, conversation.estimateWindowTokens(null))
    }

    // ===== estimateWindowTokens：压缩感知（陈旧判定 + 窗口估算）=====

    private fun userNode(text: String) =
        MessageNode(messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))))

    /**
     * 五节点会话 + 边界在第 2 个节点（下标 1）的检查点 → 发送窗口 = 检查点合成消息 + 后 3 个节点。
     * `areaFingerprint = null` 使其退化为「仅边界存在性校验」，测试无需构造真实指纹。
     */
    private fun compressedConversation(
        checkpointCreatedAt: JavaInstant,
        lastAssistant: UIMessage? = null,
    ): Conversation {
        val nodes = listOf(
            userNode("aaaa"), userNode("bbbb"), userNode("cccc"), userNode("dddd"), userNode("eeee"),
        ).let { if (lastAssistant == null) it else it + MessageNode(messages = listOf(lastAssistant)) }
        return Conversation(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            messageNodes = nodes,
            compressionSummaries = listOf(
                CompressionSummary(
                    conversationId = kotlin.uuid.Uuid.random(),
                    content = "summary",
                    messageCount = 2,
                    boundaryNodeId = nodes[1].id.toString(),
                    createdAt = checkpointCreatedAt,
                    areaFingerprint = null,
                )
            ),
        )
    }

    /** 期望值 = 对**发送窗口**（检查点合成消息 + 边界后原文）的本地估算。 */
    private fun windowEstimate(c: Conversation) = estimateTokenCount(c.requestWindowMessages())

    @Test
    fun `estimation uses the compressed window not the full history`() {
        // 无 usage → 走本地估算。断言口径是**窗口**（含检查点合成消息），不是全量历史
        val conversation = compressedConversation(checkpointCreatedAt = JavaInstant.now())
        assertEquals(windowEstimate(conversation), conversation.estimateWindowTokens(null))
        assertNotEquals(
            "按全量历史估算会得到不同结果（正是要避免的口径）",
            estimateTokenCount(conversation.messageNodes.map { it.messages[it.selectIndex] }),
            conversation.estimateWindowTokens(null),
        )
    }

    @Test
    fun `stale usage recorded before the checkpoint falls back to the window estimate`() {
        // 压缩后、下一条回复到来前：最后一条 assistant 的 usage 仍是压缩前的全量值。
        // 直接用会虚高（表现为「手动压缩成功后占用条数字不动、像没生效」）→ 须判陈旧并回退。
        val stale = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(promptTokens = 99_999, completionTokens = 1, totalTokens = 100_000),
        )
        val conversation = compressedConversation(
            checkpointCreatedAt = JavaInstant.now().plusSeconds(3600),  // 检查点比该消息新
            lastAssistant = stale,
        )
        assertEquals(windowEstimate(conversation), conversation.estimateWindowTokens(null))
        assertNotEquals("陈旧 usage 不能被采信", 99_999, conversation.estimateWindowTokens(null))
    }

    @Test
    fun `usage recorded after the checkpoint is trusted`() {
        // 压缩之后又生成过回复 → usage 反映压缩后的上下文，可信
        val fresh = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(promptTokens = 12_345, completionTokens = 1, totalTokens = 12_346),
        )
        val conversation = compressedConversation(
            checkpointCreatedAt = JavaInstant.now().minusSeconds(3600),
            lastAssistant = fresh,
        )
        assertEquals(12_345, conversation.estimateWindowTokens(null))
    }

    @Test
    fun `without a checkpoint usage is always trusted`() {
        // 无压缩时不存在"陈旧"问题（回归保护）
        val last = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(promptTokens = 4_242, completionTokens = 1, totalTokens = 4_243),
        )
        val conversation = conversationWith(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))), last)
        )
        assertEquals(4_242, conversation.estimateWindowTokens(null))
    }
}
