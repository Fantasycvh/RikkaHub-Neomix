package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.data.model.cardPartsDivergeFromSystemPrompt
import app.androidrik.rikkahub.data.model.hasCardParts
import app.androidrik.rikkahub.data.model.buildCardSystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1542 Tier B：system 块化后，预览对 system 的**逐块归因**。
 *
 * 核心保证：块列表与 `buildSystemPromptText` 同源（同参调用同一函数）⇒
 * 预览里看到的每段「角色卡·Description」等，就是真实请求 system 里的那段。
 */
class SystemBlocksAttributionTest {

    private val settings = Settings.dummy().copy(
        displaySetting = Settings.dummy().displaySetting.copy(
            enableDiagramRendering = false,
            enableLatexRendering = false,
        ),
    )
    private val model = app.androidrik.ai.provider.Model(modelId = "m", displayName = "M")

    private val labels = PromptInspectionLabels(
        system = "系统提示词",
        checkpoint = "检查点",
        injection = "注入",
        history = "历史",
        memory = "注入的记忆",
        toolSchema = "工具 Schema",
        renderHint = "渲染提示",
        cardSlotNames = mapOf(
            CardSlot.GREETING to "开场白",
            CardSlot.SYSTEM_PROMPT to "系统提示",
            CardSlot.DESCRIPTION to "描述",
            CardSlot.PERSONALITY to "性格",
            CardSlot.SCENARIO to "场景",
            CardSlot.EXAMPLE to "示例对话",
        ),
    )

    private fun pristineCardAssistant(): Assistant {
        val cardSystemPrompt = "You are a maid."
        val description = "cheerful"
        val personality = "curious"
        val scenario = "park"
        val exampleDialogue = "hi"
        // 未漂移的完整形态：card* 字段在位，且 systemPrompt == 按槽渲染结果（导入时刻的产物）
        return Assistant(
            name = "帕斯卡",
            systemPrompt = buildCardSystemPrompt(
                name = "帕斯卡",
                cardSystemPrompt = cardSystemPrompt,
                description = description,
                personality = personality,
                scenario = scenario,
                exampleDialogue = exampleDialogue,
            ),
            cardSystemPrompt = cardSystemPrompt,
            cardDescription = description,
            cardPersonality = personality,
            cardScenario = scenario,
            cardExampleDialogue = exampleDialogue,
        )
    }

    private fun blocks(assistant: Assistant) = buildSystemBlocks(
        assistant = assistant,
        settings = settings,
        model = model,
        messages = emptyList(),
        memories = emptyList(),
        tools = emptyList(),
        conversationSystemPrompt = null,
    )

    @Test
    fun `pristine card assistant attributes each slot`() {
        val a = pristineCardAssistant()
        // 漂移判定自检：rendered 应与 systemPrompt 逐字节一致（ComparisonFailure 会显示全文本差异）
        assertEquals(
            buildCardSystemPrompt(name = a.name, assistant = a),
            a.systemPrompt,
        )
        val segments = systemBlocksToSegments(blocks(pristineCardAssistant()), labels, role = "system")
        assertEquals(
            listOf("开场白", "系统提示", "描述", "性格", "场景", "示例对话"),
            segments.map { it.label },
        )
        assertTrue(segments.all { it.kind == PromptSegmentKind.CARD_SLOT })
        assertTrue(segments.all { it.isSynthetic })
        // 同源证明：块连接（共享 joinSystemBlocks）== 发送文本 == a.systemPrompt
        assertEquals(
            a.systemPrompt,
            joinSystemBlocks(blocks(pristineCardAssistant())),
        )
    }

    @Test
    fun `diverged assistant is one persona segment`() {
        val segments = systemBlocksToSegments(
            blocks(Assistant(name = "帕斯卡", systemPrompt = "手改人设")),
            labels,
            role = "system",
        )
        assertEquals(1, segments.size)
        assertEquals(PromptSegmentKind.SYSTEM, segments.single().kind)
        assertEquals("系统提示词", segments.single().label)
        assertEquals("手改人设", segments.single().content)
    }

    @Test
    fun `unknown slot falls back to the system label`() {
        val block = SystemBlock(SystemBlockSource.CARD_SLOT, cardSlot = null, content = "x")
        val segments = systemBlocksToSegments(listOf(block), labels, role = "system")
        assertEquals("系统提示词", segments.single().label)
        assertEquals(PromptSegmentKind.CARD_SLOT, segments.single().kind)
    }

    @Test
    fun `memory tool and hints map to their own kinds`() {
        val blocks = listOf(
            SystemBlock(SystemBlockSource.PERSONA, content = "p"),
            SystemBlock(SystemBlockSource.MEMORY, content = "m"),
            SystemBlock(SystemBlockSource.TOOL, content = "t"),
            SystemBlock(SystemBlockSource.DIAGRAM_HINT, content = "d"),
            SystemBlock(SystemBlockSource.MATH_HINT, content = "h"),
        )
        val segments = systemBlocksToSegments(blocks, labels, role = "system")
        assertEquals(
            listOf(
                PromptSegmentKind.SYSTEM,
                PromptSegmentKind.MEMORY,
                PromptSegmentKind.TOOL_SCHEMA,
                PromptSegmentKind.RENDER_HINT,
                PromptSegmentKind.RENDER_HINT,
            ),
            segments.map { it.kind },
        )
        assertEquals(listOf("系统提示词", "注入的记忆", "工具 Schema", "渲染提示", "渲染提示"), segments.map { it.label })
    }

    @Test
    fun `segment roles carry the system role`() {
        val segments = systemBlocksToSegments(blocks(pristineCardAssistant()), labels, role = "system")
        assertTrue(segments.all { it.role == "system" })
        // 非卡对照：role 原样传递
        val plain = systemBlocksToSegments(
            blocks(Assistant(systemPrompt = "x")), labels, role = "system",
        )
        assertEquals("system", plain.single().role)
        assertEquals(MessageRole.SYSTEM, UIMessage(role = MessageRole.SYSTEM, parts = emptyList()).role)
    }

}
