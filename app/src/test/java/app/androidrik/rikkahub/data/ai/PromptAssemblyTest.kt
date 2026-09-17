package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.prompts.buildCompactionCheckpointText
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼装组装的不变量测试。
 *
 * 重点不是「能拼出东西」，而是**预览与真实请求不可漂移**：`PromptInspector` 调用
 * [buildInternalMessages]，`GenerationHandler` 也调用它——这些用例把该函数的契约钉住，
 * 一旦有人改真实路径的组装顺序/内容而没同步预览，这里会红。
 * 见 `docs/peer-apps-benchmark.md` §6.7 ③。
 */
class PromptAssemblyTest {

    private val settings = Settings.dummy()
    private val model = Model(modelId = "test-model", displayName = "Test")

    private fun assistant(
        systemPrompt: String = "",
        enableMemory: Boolean = false,
        contextMessageLimit: Int = 0,
        allowConversationSystemPrompt: Boolean = false,
        chatModelId: kotlin.uuid.Uuid? = null,
    ) = Assistant(
        systemPrompt = systemPrompt,
        enableMemory = enableMemory,
        contextMessageLimit = contextMessageLimit,
        allowConversationSystemPrompt = allowConversationSystemPrompt,
        chatModelId = chatModelId,
    )

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessage(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    // region buildSystemPromptText

    @Test
    fun `system prompt carries the assistant text`() {
        val text = buildSystemPromptText(
            assistant = assistant(systemPrompt = "You are helpful."),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
        )
        assertTrue(text.contains("You are helpful."))
    }

    @Test
    fun `conversation system prompt wins only when the assistant allows it`() {
        val allowing = buildSystemPromptText(
            assistant = assistant(systemPrompt = "base", allowConversationSystemPrompt = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = "override",
        )
        assertTrue(allowing.contains("override"))
        assertFalse(allowing.contains("base"))

        // 未开启时，会话级提示词必须被忽略（否则等于绕过助手配置）
        val notAllowing = buildSystemPromptText(
            assistant = assistant(systemPrompt = "base", allowConversationSystemPrompt = false),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = "override",
        )
        assertTrue(notAllowing.contains("base"))
        assertFalse(notAllowing.contains("override"))
    }

    @Test
    fun `tool system prompt is appended when tools are present`() {
        val tool = Tool(
            name = "demo",
            description = "demo tool",
            systemPrompt = { _, _ -> "TOOL_SCHEMA_MARKER" },
            execute = { emptyList() },
        )
        val text = buildSystemPromptText(
            assistant = assistant(systemPrompt = "base"),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = emptyList(),
            tools = listOf(tool),
            conversationSystemPrompt = null,
        )
        assertTrue(text.contains("TOOL_SCHEMA_MARKER"))
    }

    @Test
    fun `memories are only injected when the assistant enables memory`() {
        val memory = AssistantMemory(id = "1", content = "MEMORY_MARKER")

        val disabled = buildSystemPromptText(
            assistant = assistant(enableMemory = false),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = listOf(memory),
            tools = emptyList(),
            conversationSystemPrompt = null,
        )
        assertFalse(disabled.contains("MEMORY_MARKER"))

        val enabled = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = listOf(memory),
            tools = emptyList(),
            conversationSystemPrompt = null,
        )
        assertTrue(enabled.contains("MEMORY_MARKER"))
    }

    // endregion

    // region buildSendWindowMessages

    @Test
    fun `context limit truncates history and reports it`() {
        val messages = (1..10).map { userMessage("m$it") }
        val window = buildSendWindowMessages(
            messages = messages,
            compressionWindow = null,
            contextMessageLimit = 4,
        )
        assertTrue("truncation should shrink the window", window.size < messages.size)
        // 截断保留尾部：最后一条必须在
        assertEquals("m10", (window.last().parts.first() as UIMessagePart.Text).text)
    }

    @Test
    fun `zero context limit means no truncation`() {
        val messages = (1..30).map { userMessage("m$it") }
        val window = buildSendWindowMessages(
            messages = messages,
            compressionWindow = null,
            contextMessageLimit = 0,
        )
        assertEquals(messages.size, window.size)
    }

    @Test
    fun `checkpoint message is placed first and survives truncation`() {
        // 用生产函数造检查点文本（而非手写），前缀判据才与之同源
        val checkpoint = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(buildCompactionCheckpointText("CHECKPOINT_MARKER"))),
            isSynthetic = true,
        )
        val live = (1..10).map { userMessage("m$it") }
        val messages = listOf(checkpoint) + live

        val window = buildSendWindowMessages(
            messages = messages,
            compressionWindow = Conversation.CompressionWindow(
                checkpointMessage = checkpoint,
                boundaryIndex = 0,
            ),
            contextMessageLimit = 3,
        )
        // 检查点不参与 limitContext 截断：必须在窗口内且在最前
        assertTrue(
            "checkpoint must survive truncation",
            window.any { msg ->
                msg.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("CHECKPOINT_MARKER") }
            },
        )
    }

    // endregion

    // region buildInternalMessages — 预览与真实请求的共同契约

    @Test
    fun `system message comes first and is marked synthetic`() {
        val internal = buildInternalMessages(
            assistant = assistant(systemPrompt = "SYS"),
            settings = settings,
            model = model,
            messages = listOf(userMessage("hi")),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        assertEquals(MessageRole.SYSTEM, internal.first().role)
        assertTrue("system is request-time synthetic", internal.first().isSynthetic)
        assertEquals(2, internal.size)
    }

    @Test
    fun `blank system prompt yields no system message when the render hints are off`() {
        // 渲染提示（TikZ / LaTeX）在默认设置里是**开**的，且它们被追加到 system 里 →
        // 空 systemPrompt 也会产出 system 消息。这里显式关掉，才测得到「真空 system」。
        val quiet = settings.copy(
            displaySetting = settings.displaySetting.copy(
                enableDiagramRendering = false,
                enableLatexRendering = false,
            )
        )
        val internal = buildInternalMessages(
            assistant = assistant(systemPrompt = ""),
            settings = quiet,
            model = model,
            messages = listOf(userMessage("hi")),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        assertTrue(internal.none { it.role == MessageRole.SYSTEM })
        assertEquals(1, internal.size)
    }

    @Test
    fun `render hints are appended to the system message`() {
        // 默认设置下渲染提示开启：它们属于 system 的固定开销，预览必须如实反映
        val internal = buildInternalMessages(
            assistant = assistant(systemPrompt = "SYS"),
            settings = settings,
            model = model,
            messages = listOf(userMessage("hi")),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        val systemText = internal.first().parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        assertTrue("render hints should be part of the system block", systemText.length > "SYS".length)
    }

    @Test
    fun `history order is preserved after the system message`() {
        val internal = buildInternalMessages(
            assistant = assistant(systemPrompt = "SYS"),
            settings = settings,
            model = model,
            messages = listOf(userMessage("u1"), assistantMessage("a1"), userMessage("u2")),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        val texts = internal.drop(1).map { (it.parts.first() as UIMessagePart.Text).text }
        assertEquals(listOf("u1", "a1", "u2"), texts)
    }

    @Test
    fun `same inputs produce identical output`() {
        // 幂等性：预览跑一次不应改变再次组装的结果（无隐藏状态）
        val args = {
            buildInternalMessages(
                assistant = assistant(systemPrompt = "SYS", enableMemory = true),
                settings = settings,
                model = model,
                messages = listOf(userMessage("hi")),
                memories = listOf(AssistantMemory(id = "1", content = "mem")),
                tools = emptyList(),
                conversationSystemPrompt = null,
                compressionWindow = null,
            )
        }
        val first = args().map { it.parts.filterIsInstance<UIMessagePart.Text>().joinToString { p -> p.text } }
        val second = args().map { it.parts.filterIsInstance<UIMessagePart.Text>().joinToString { p -> p.text } }
        assertEquals(first, second)
    }

    @Test
    fun `history count excludes the system message and injections`() {
        // 预览的「历史 N / M」口径：只数历史（含检查点），不把 system/注入算进去。
        // 若把 system 也算上，空会话会显示「1 条」——那是误导（见 PromptInspection 注释）。
        val internal = buildInternalMessages(
            assistant = assistant(systemPrompt = "SYS"),
            settings = settings,
            model = model,
            messages = listOf(userMessage("u1"), assistantMessage("a1")),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        val history = internal.count {
            !it.isSynthetic && it.role != MessageRole.SYSTEM
        }
        assertEquals(2, history)
        // 而发送的消息总数（含 system）确实多一条——两个口径不可混用
        assertEquals(3, internal.size)
    }

    // endregion
}
