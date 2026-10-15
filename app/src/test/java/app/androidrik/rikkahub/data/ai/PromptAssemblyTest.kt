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
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.data.model.buildCardSystemPrompt
import app.androidrik.rikkahub.data.model.rebuildSystemPromptFromCardParts
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

    // 关掉渲染提示：块断言只关注人设/卡槽区，不受 dummy 设置的提示开关干扰
    private val settings = Settings.dummy().copy(
        displaySetting = Settings.dummy().displaySetting.copy(
            enableDiagramRendering = false,
            enableLatexRendering = false,
        ),
    )
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

    // ---- #1542 复核 Tier B：未漂移角色卡助手按槽渲染（输出逐字节不变，来源升级为卡槽块） ----

    private fun cardAssistant(
        systemPrompt: String,
        allowConversationSystemPrompt: Boolean = false,
    ) = Assistant(
        name = "帕斯卡",
        systemPrompt = systemPrompt,
        allowConversationSystemPrompt = allowConversationSystemPrompt,
        cardSystemPrompt = "You are a maid.",
        cardDescription = "cheerful",
        cardPersonality = "curious",
        cardScenario = "a quiet park",
        cardExampleDialogue = "Hi there!",
    )

    @Test
    fun `pristine card assistant renders system from slots with identical bytes`() {
        val a = cardAssistant(
            // 未漂移：systemPrompt == 按槽渲染结果（导入时刻的产物）
            buildCardSystemPrompt(
                name = "帕斯卡",
                cardSystemPrompt = "You are a maid.",
                description = "cheerful",
                personality = "curious",
                scenario = "a quiet park",
                exampleDialogue = "Hi there!",
            ),
        )
        val blocks = buildSystemBlocks(
            assistant = a, settings = settings, model = model,
            messages = emptyList(), memories = emptyList(), tools = emptyList(),
            conversationSystemPrompt = null,
        )
        // 来源是**逐槽**的卡槽块（默认顺序、未被关闭的槽）
        assertEquals(
            listOf(
                CardSlot.GREETING,
                CardSlot.SYSTEM_PROMPT,
                CardSlot.DESCRIPTION,
                CardSlot.PERSONALITY,
                CardSlot.SCENARIO,
                CardSlot.EXAMPLE,
            ),
            blocks.mapNotNull { it.cardSlot },
        )
        assertTrue("不应有 PERSONA 整段块（应拆成卡槽块）", blocks.none { it.source == SystemBlockSource.PERSONA })
        assertTrue(blocks.first().source == SystemBlockSource.CARD_SLOT)
        // 逐字节等价由下方 buildSystemPromptText 断言（连接逻辑是被测实现的一部分，测试不应自行复刻）
        assertEquals(a.systemPrompt, buildSystemPromptText(
            assistant = a, settings = settings, model = model,
            messages = emptyList(), memories = emptyList(), tools = emptyList(),
            conversationSystemPrompt = null,
        ))
    }

    @Test
    fun `slot toggle takes effect immediately for pristine card assistants`() {
        val rendered = buildCardSystemPrompt(
            name = "帕斯卡",
            cardSystemPrompt = "You are a maid.",
            description = "cheerful",
            personality = "curious",
            scenario = "a quiet park",
            exampleDialogue = "Hi there!",
        )
        // 关掉 Scenario 槽 + 自动重建（槽位视图回调的语义：改槽即重建，即时生效）
        val a = cardAssistant(rendered)
            .copy(disabledCardSlots = setOf(CardSlot.SCENARIO))
            .rebuildSystemPromptFromCardParts()
        val text = buildSystemPromptText(
            assistant = a, settings = settings, model = model,
            messages = emptyList(), memories = emptyList(), tools = emptyList(),
            conversationSystemPrompt = null,
        )
        assertEquals(
            buildCardSystemPrompt(name = "帕斯卡", assistant = a),
            text,
        )
        assertTrue("被关闭的槽不应出现", !text.contains("a quiet park"))
    }

    @Test
    fun `diverged card assistant keeps the hand-edited system prompt`() {
        // 漂移（用户手改过）：手改内容优先，整段作为单个 PERSONA 块，不做槽位拆分
        val a = cardAssistant("手改的自定义人设").copy(disabledCardSlots = setOf(CardSlot.SCENARIO))
        val blocks = buildSystemBlocks(
            assistant = a, settings = settings, model = model,
            messages = emptyList(), memories = emptyList(), tools = emptyList(),
            conversationSystemPrompt = null,
        )
        assertEquals(SystemBlockSource.PERSONA, blocks.first().source)
        assertEquals("手改的自定义人设", blocks.first().content)
    }

    @Test
    fun `conversation override wins over card slots`() {
        val a = cardAssistant(
            systemPrompt = buildCardSystemPrompt(
                name = "帕斯卡",
                cardSystemPrompt = "You are a maid.",
                description = "cheerful", personality = "curious",
                scenario = "s", exampleDialogue = "e",
            ),
            allowConversationSystemPrompt = true,
        )
        val text = buildSystemPromptText(
            assistant = a, settings = settings, model = model,
            messages = emptyList(), memories = emptyList(), tools = emptyList(),
            conversationSystemPrompt = "会话级人设",
        )
        assertEquals("会话级人设", text)
    }

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
    fun `memory data never enters system - only the L0 discipline does`() {
        val memory = AssistantMemory(id = "1", content = "MEMORY_MARKER")

        val system = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = listOf(memory),
            tools = emptyList(),
            conversationSystemPrompt = null,
        )
        // L0 纪律留在 system，且不含任何记忆数据（逐字节恒定 ⇒ 可缓存）
        assertTrue(system.contains("**Memories**"))
        assertFalse(
            "记忆数据不得进入 system：system 是第一条消息，改它会让前缀从位置 0 起全断",
            system.contains("MEMORY_MARKER"),
        )

        val internal = buildInternalMessages(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = listOf(userMessage("hi")),
            memories = listOf(memory),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        val texts = { m: UIMessage -> m.parts.filterIsInstance<UIMessagePart.Text>().map { it.text } }
        // 数据在**尾部**的合成 USER 消息里（照压缩检查点同款做法）
        val tail = internal.last()
        assertEquals(MessageRole.USER, tail.role)
        assertTrue("尾部记忆块必须是合成消息（不落库）", tail.isSynthetic)
        assertTrue(texts(tail).any { it.contains("MEMORY_MARKER") })
        assertTrue("尾部块须带可识别的前导标记", texts(tail).any { it.startsWith(MEMORY_TAIL_PREAMBLE) })
        // 且它确实在最后一条，前面没有任何一条含记忆数据
        assertFalse(internal.dropLast(1).any { m -> texts(m).any { it.contains("MEMORY_MARKER") } })
    }

    @Test
    fun `memory is not injected at all when the assistant disables it`() {
        val memory = AssistantMemory(id = "1", content = "MEMORY_MARKER")
        val texts = { m: UIMessage -> m.parts.filterIsInstance<UIMessagePart.Text>().map { it.text } }
        val internal = buildInternalMessages(
            assistant = assistant(enableMemory = false),
            settings = settings,
            model = model,
            messages = listOf(userMessage("hi")),
            memories = listOf(memory),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        // 数据不出现
        assertFalse(internal.any { m -> texts(m).any { it.contains("MEMORY_MARKER") } })
        // 外壳（L0 纪律）也不出现 —— 「这次对话不碰记忆库」的语义
        assertFalse(internal.any { m -> texts(m).any { it.contains("**Memories**") } })
    }

    @Test
    fun `L1 anchors go into system and are absent when nothing is anchored`() {
        val plain = AssistantMemory(id = "1", content = "PLAIN_MEMORY")
        val anchored = AssistantMemory(id = "2", content = "ANCHOR_MARKER", isAnchor = true)

        fun systemOf(memories: List<AssistantMemory>): String = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
        )

        // 无锚点 ⇒ **不产生 Anchors 块**：输出与改动前逐字节一致，缓存行为不变
        val noAnchor = systemOf(listOf(plain))
        assertFalse("无锚点不该产生 Anchors 块", noAnchor.contains("**Anchors**"))
        assertFalse(noAnchor.contains("ANCHOR_MARKER"))

        // 有锚点 ⇒ 进 system —— 这正是 L1 的意义（拿到 system 级权重）
        val withAnchor = systemOf(listOf(plain, anchored))
        assertTrue(withAnchor.contains("**Anchors**"))
        assertTrue(withAnchor.contains("ANCHOR_MARKER"))

        // 且普通记忆的数据**仍不在** system（它在尾部）：锚点是例外、不是把数据搬回来
        assertFalse("锚点外的记忆数据不得进 system", withAnchor.contains("PLAIN_MEMORY"))
    }

    @Test
    fun `anchor block is capped to the quota and ordered by importance`() {
        val many = (1..8).map { i ->
            AssistantMemory(id = "$i", content = "ANCHOR_$i", isAnchor = true, importance = i / 10f)
        }
        val block = buildMemoryAnchorPrompt(many)
        // 配额 5 ⇒ 只有 importance 最高的 5 条（8..4）
        assertTrue(block.contains("ANCHOR_8"))
        assertTrue(block.contains("ANCHOR_4"))
        assertFalse("超配额的不应出现", block.contains("ANCHOR_3"))
        assertFalse(block.contains("ANCHOR_1"))
        // 降序（同等重要度时按写入先后，保证集合不变则字节稳定）
        assertTrue(block.indexOf("ANCHOR_8") < block.indexOf("ANCHOR_4"))
    }

    @Test
    fun `anchor prompt is empty without anchors`() {
        assertEquals("", buildMemoryAnchorPrompt(emptyList()))
        assertEquals("", buildMemoryAnchorPrompt(listOf(AssistantMemory(id = "1", content = "x"))))
    }

    // region L1 冻结集合（会话开始冻结 / 记忆页手动刷新；设计稿 §5）

    @Test
    fun `frozen anchor set wins over newly pinned anchors`() {
        // 会话开始时冻的是 OLD（id="0"）；此后用户又固化了一条 NEW（id="1"）。
        // 本会话必须继续只渲染 OLD —— 这正是冻结的意义（system 不变 ⇒ 前缀缓存不失效）。
        val memories = listOf(
            AssistantMemory(id = "0", content = "OLD_ANCHOR", isAnchor = true),
            AssistantMemory(id = "1", content = "NEW_ANCHOR", isAnchor = true),
        )
        val system = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
            frozenAnchorIds = setOf("0"),
        )
        assertTrue(system.contains("OLD_ANCHOR"))
        assertFalse("冻结后新固化的锚点不得挤进本会话", system.contains("NEW_ANCHOR"))
    }

    @Test
    fun `frozen null means live rendering`() {
        val memories = listOf(AssistantMemory(id = "1", content = "LIVE_ANCHOR", isAnchor = true))
        val system = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
            frozenAnchorIds = null,
        )
        assertTrue("未冻结（null）时必须实时渲染，行为与加 L1 之前一致", system.contains("LIVE_ANCHOR"))
    }

    @Test
    fun `empty frozen set suppresses the anchor block entirely`() {
        // 空集 = 冻结那一刻没有锚点。此后即使用户固化了锚点，本会话也**不产生** Anchors 块
        // ——「无锚点 ⇒ 无块」的不变式在冻结态同样成立（否则空块也会改变 system 字节）。
        val memories = listOf(AssistantMemory(id = "1", content = "LATER_ANCHOR", isAnchor = true))
        val system = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
            frozenAnchorIds = emptySet(),
        )
        assertFalse(system.contains("**Anchors**"))
        assertFalse(system.contains("LATER_ANCHOR"))
        // L0 纪律照常在（冻结的是锚点层，不是整个记忆子系统）
        assertTrue(system.contains("**Memories**"))
    }

    /**
     * **为什么冻 id 而不是冻渲染好的文本** —— 这条用例就是那个理由的可执行版本。
     *
     * 记忆库有两条**自动**删除路径（做梦整理 `MemoryConsolidationGenerationAgent`、
     * 200 条上限淘汰 `MemoryRepository.enforceMaxCount`），它们都不会去刷新各会话的快照。
     * 若快照存的是文本，删掉的锚点会**永远**留在 system 里：既忘不掉，也改不动。
     */
    @Test
    fun `deleted anchor disappears and edited anchor takes effect`() {
        fun systemOf(memories: List<AssistantMemory>): String = buildSystemPromptText(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = emptyList(),
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
            frozenAnchorIds = setOf("0", "1"),
        )

        val both = listOf(
            AssistantMemory(id = "0", content = "DOOMED_ANCHOR", isAnchor = true),
            AssistantMemory(id = "1", content = "KEPT_ANCHOR", isAnchor = true),
        )
        // ① 删掉 id="0"（模拟做梦整理 / 上限淘汰把它删了）
        val afterDelete = systemOf(both.drop(1))
        assertFalse("已删除的锚点必须立刻从 system 消失（冻的是集合，不是文本）", afterDelete.contains("DOOMED_ANCHOR"))
        assertTrue(afterDelete.contains("KEPT_ANCHOR"))

        // ② 改写 id="1" 的内容（模拟用户在记忆页改了锚点文本）
        val afterEdit = systemOf(
            listOf(AssistantMemory(id = "1", content = "REWRITTEN_ANCHOR", isAnchor = true))
        )
        assertTrue("锚点文本改写必须立刻生效", afterEdit.contains("REWRITTEN_ANCHOR"))
        assertFalse(afterEdit.contains("KEPT_ANCHOR"))
    }

    @Test
    fun `frozen set does not disturb the tail memory data - it stays live`() {
        // 冻结只作用于 L1（system）。尾部 L2/L3 是「每次写入都会变」的那一层，
        // 它必须继续按实时记忆渲染——冻结它会毁掉尾部注入的全部意义。
        val memories = listOf(AssistantMemory(id = "1", content = "TAIL_MARKER", isAnchor = true))
        val internal = buildInternalMessages(
            assistant = assistant(enableMemory = true),
            settings = settings,
            model = model,
            messages = listOf(userMessage("hi")),
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
            frozenAnchorIds = emptySet(),
        )
        val texts = { m: UIMessage -> m.parts.filterIsInstance<UIMessagePart.Text>().map { it.text } }
        assertTrue(
            "尾部记忆数据必须照旧实时渲染",
            texts(internal.last()).any { it.contains("TAIL_MARKER") },
        )
        assertFalse(
            "冻结了锚点层 ⇒ system 里不应出现这条记忆的文本",
            texts(internal.first()).any { it.contains("TAIL_MARKER") },
        )
    }

    @Test
    fun `anchorIdsOf mirrors what an unfrozen render would show`() {
        // 冻结那一刻的集合必须与同一批记忆的实时渲染一致，否则会出现
        // 「冻结前显示 5 条、冻结后只剩 3 条」的自相矛盾瞬间。
        val many = (1..8).map { i ->
            AssistantMemory(id = "$i", content = "ANCHOR_$i", isAnchor = true, importance = i / 10f)
        } + AssistantMemory(id = "9", content = "PLAIN", isAnchor = false)

        assertEquals(listOf("8", "7", "6", "5", "4"), anchorIdsOf(many))
        assertEquals(emptyList<String>(), anchorIdsOf(listOf(AssistantMemory(id = "1", content = "x"))))
    }

    // endregion
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
        // 渲染提示开启时属于 system 的固定开销，预览必须如实反映
        // （类级 settings 关掉了渲染开关，这里局部开启）
        val renderOn = settings.copy(
            displaySetting = settings.displaySetting.copy(
                enableDiagramRendering = true,
                enableLatexRendering = true,
            ),
        )
        val internal = buildInternalMessages(
            assistant = assistant(systemPrompt = "SYS"),
            settings = renderOn,
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
        assertTrue(systemText.contains("For TikZ diagrams"))
        assertTrue(systemText.contains("For math formulas"))
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
