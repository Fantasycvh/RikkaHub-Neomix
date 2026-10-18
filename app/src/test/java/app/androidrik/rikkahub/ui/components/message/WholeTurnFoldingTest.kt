package app.androidrik.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart

/**
 * 整轮折叠的分组 / 展示决策单测（`docs/design/design-whole-turn-folding.md` §9）。
 *
 * `groupMessageParts()` 是纯函数（不碰 Compose、不碰数据层），所以这些规则可以在 JVM 上直接跑。
 * 测试名对应设计稿的条目编号，注释写明「哪条决策被守住」。
 */
class WholeTurnFoldingTest {

    // ---- fixtures ----

    private fun text(value: String) = UIMessagePart.Text(value)

    private fun reasoning(value: String) = UIMessagePart.Reasoning(reasoning = value)

    private fun tool(
        name: String,
        id: String = "call_$name",
        output: String? = """{"ok":true}""",
        approval: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = "{}",
        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList(),
        approvalState = approval,
    )

    private fun serverTool(
        name: String = "googleSearch",
        id: String = "server_$name",
        status: ServerToolStatus = ServerToolStatus.COMPLETED,
    ) = UIMessagePart.ServerTool(toolCallId = id, toolName = name, status = status)

    private fun List<MessagePartBlock>.thinkingBlocks() =
        filterIsInstance<MessagePartBlock.ThinkingBlock>()

    /** 整个消息里唯一的那条思考链 */
    private fun List<MessagePartBlock>.onlyThinking() = thinkingBlocks().single()

    // ---- 1. 单工具 + 前后文本 ----

    @Test
    fun `1 single tool with text before and after folds narration and keeps the answer`() {
        val blocks = listOf(text("我先查一下"), tool("search_web"), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)

        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertEquals(2, chain.steps.size)
        assertTrue(chain.steps[0] is ThinkingStep.NarrationStep)
        assertTrue(chain.steps[1] is ThinkingStep.ToolStep)
        // 决策 1 / 6：链可整块收起，因为后面还有回答
        assertTrue(chain.canCollapse)
        assertFalse(chain.forceExpand)

        val answer = blocks[1] as MessagePartBlock.ContentBlock
        assertEquals("最终回答", (answer.part as UIMessagePart.Text).text)
    }

    // ---- 2. 无工具纯文本 ----

    @Test
    fun `2 text without tools stays in content blocks`() {
        val blocks = listOf(text("第一段"), text("第二段"))
            .groupMessageParts(wholeTurnFolding = true)

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MessagePartBlock.ContentBlock })
        assertTrue(blocks.thinkingBlocks().isEmpty())
    }

    // ---- 3. 工具前后各一段文本 ----

    @Test
    fun `3 text before the tool folds while text after the tool stays`() {
        val blocks = listOf(text("过程解说"), tool("search_web"), text("回答"))
            .groupMessageParts(wholeTurnFolding = true)

        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertTrue(chain.steps.any { it is ThinkingStep.NarrationStep })
        val answer = blocks[1] as MessagePartBlock.ContentBlock
        assertEquals("回答", (answer.part as UIMessagePart.Text).text)
    }

    // ---- 4. 空白文本中断（Qwen 场景）----

    @Test
    fun `4 blank text does not tear the chain apart`() {
        val blocks = listOf(
            reasoning("想一想"),
            tool("search_web", id = "call_a"),
            text(""),
            text("   \n\t "),
            tool("scrape_web", id = "call_b"),
            text("  "),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        // 空白文本既不渲染也不参与分组 ⇒ 只剩「一条链 + 一段回答」
        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertEquals(2, chain.tools().size)
        assertEquals(1, blocks.count { it is MessagePartBlock.ContentBlock })
    }

    // ---- 5. 顶层 Image 夹在工具之间（已知会撕链）----

    @Test
    fun `5 top level image between tools splits the chain`() {
        val blocks = listOf(
            reasoning("想一想"),
            tool("search_web", id = "call_a"),
            UIMessagePart.Image(url = "https://example.com/a.png"),
            tool("scrape_web", id = "call_b"),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        // 图片不是思考步骤 ⇒ 前链被 flush，图片回到正文，后链另起一块（已知行为）
        assertEquals(4, blocks.size)
        assertTrue(blocks[1] is MessagePartBlock.ContentBlock)
        assertTrue(blocks[1].let { (it as MessagePartBlock.ContentBlock).part is UIMessagePart.Image })
        assertEquals(2, blocks.thinkingBlocks().size)
    }

    // ---- 6. chart_display 成功 → ChartBlock 且切断 ----

    @Test
    fun `6 successful chart display becomes its own chart block and cuts the chain`() {
        val blocks = listOf(
            reasoning("画个图"),
            tool("chart_display", output = """{"success":true,"message":"Chart displayed to the user."}"""),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessagePartBlock.ThinkingBlock)
        assertTrue(blocks[1] is MessagePartBlock.ChartBlock)
        // 图表本身绝不能被折进思考链
        val beforeChart = blocks[0] as MessagePartBlock.ThinkingBlock
        assertTrue(beforeChart.tools().isEmpty())
    }

    // ---- 7. chart_display 失败 / 生成中 → 仍是 ToolStep ----

    @Test
    fun `7 failed or in flight chart display stays a tool step`() {
        val failed = listOf(
            tool("chart_display", id = "call_failed", output = """{"error":"INVALID_CHART","message":"bad"}"""),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)
        assertEquals(2, failed.size)
        val failedChain = failed.onlyThinking()
        assertEquals(1, failedChain.steps.count { it is ThinkingStep.ToolStep })
        assertTrue(failed.none { it is MessagePartBlock.ChartBlock })

        val inFlight = listOf(
            tool("chart_display", id = "call_running", output = null),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)
        assertTrue(inFlight.none { it is MessagePartBlock.ChartBlock })
        assertEquals(1, inFlight.onlyThinking().steps.count { it is ThinkingStep.ToolStep })
    }

    // ---- 8. ServerTool 入链且参与 lastToolIndex ----

    @Test
    fun `8 server tool joins the chain and counts as a tool for the split`() {
        val blocks = listOf(text("正在搜索"), serverTool(), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)

        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertEquals(2, chain.steps.size)
        assertTrue(chain.steps[0] is ThinkingStep.NarrationStep)
        assertTrue(chain.steps[1] is ThinkingStep.ServerToolStep)
        val answer = blocks[1] as MessagePartBlock.ContentBlock
        assertEquals("最终回答", (answer.part as UIMessagePart.Text).text)
    }

    // ---- 9. 连续 Reasoning + Tool + Text 合成单块 ----

    @Test
    fun `9 reasoning tool narration and tool merge into a single block`() {
        val blocks = listOf(
            reasoning("想一想"),
            tool("search_web", id = "call_a"),
            text("再读一下这个页面"),
            tool("scrape_web", id = "call_b"),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertEquals(4, chain.steps.size)
        assertTrue(chain.steps[0] is ThinkingStep.ReasoningStep)
        assertTrue(chain.steps[1] is ThinkingStep.ToolStep)
        assertTrue(chain.steps[2] is ThinkingStep.NarrationStep)
        assertTrue(chain.steps[3] is ThinkingStep.ToolStep)
    }

    // ---- 10. todo_write 作为最后工具 ----

    @Test
    fun `10 todo_write as the last tool folds behind the answer`() {
        val blocks = listOf(
            reasoning("拆一下任务"),
            tool("todo_write", output = """{"ok":true}"""),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertEquals(1, chain.tools().size)
        assertTrue(chain.canCollapse)
    }

    // ---- 11. isPending 存在 ⇒ canCollapse = false ----

    @Test
    fun `11 pending step forbids collapsing and forces expansion`() {
        val blocks = listOf(
            reasoning("先做 A"),
            tool("workspace_shell", id = "call_a", output = null, approval = ToolApprovalState.Pending),
            tool("search_web", id = "call_b"),
            tool("scrape_web", id = "call_c"),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        val chain = blocks.onlyThinking()
        assertEquals(4, chain.steps.size)
        // 决策 8：审批入口藏在 takeLast(2) 之外 ⇒ 整链不折且强制展开
        assertFalse(chain.canCollapse)
        assertTrue(chain.forceExpand)
    }

    // ---- 12. generate_image 成功 ⇒ 切断链 ----

    @Test
    fun `12 successful generate_image cuts the chain so the picture stays visible`() {
        val blocks = listOf(
            reasoning("找参考"),
            tool("search_web", id = "call_a"),
            tool("generate_image", id = "call_b", output = """{"images":["/files/a.png"]}"""),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        assertEquals(3, blocks.size)
        val before = blocks[0] as MessagePartBlock.ThinkingBlock
        val image = blocks[1] as MessagePartBlock.ThinkingBlock
        assertEquals(1, image.steps.size)
        assertTrue((image.steps[0] as ThinkingStep.ToolStep).tool.toolName == "generate_image")
        // 交付物独占一块 ⇒ 永不折叠，本次生成的图始终可见
        assertFalse(image.canCollapse)
        assertTrue(before.canCollapse)
    }

    // ---- 13. USER 消息含 Tool ⇒ 不折叠（角色守卫）----

    @Test
    fun `13 user message with tool parts is never folded`() {
        val blocks = listOf(text("用户自己的话"), tool("search_web"), text("另一句话"))
            .groupMessageParts(role = MessageRole.USER, wholeTurnFolding = true)

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is MessagePartBlock.ContentBlock })
        assertTrue(blocks.thinkingBlocks().isEmpty())
    }

    // ---- 14. 摘要统计：步数 / 工具数 / 说明数 ----

    @Test
    fun `14 summary counts steps tools and narrations`() {
        val blocks = listOf(
            reasoning("想一想"),
            tool("search_web", id = "call_a"),
            text("解说一"),
            serverTool(),
            text("解说二"),
            tool("scrape_web", id = "call_b"),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        val summary = blocks.onlyThinking().steps.thinkingChainSummary()
        assertEquals(6, summary.stepCount)
        assertEquals(3, summary.toolCount)
        assertEquals(2, summary.narrationCount)
    }

    // ---- 额外：§5.1 审批入口不能被藏起来（与整轮折叠开关无关）----

    @Test
    fun `pending step forces expansion even when whole turn folding is off`() {
        val blocks = listOf(
            tool("workspace_shell", id = "call_a", output = null, approval = ToolApprovalState.Pending),
            tool("search_web", id = "call_b"),
            tool("scrape_web", id = "call_c"),
        ).groupMessageParts(wholeTurnFolding = false)

        val chain = blocks.onlyThinking()
        assertEquals(3, chain.steps.size)
        assertTrue(chain.forceExpand)
        assertFalse(chain.canCollapse)
    }

    // ---- 额外：决策 2 模型以工具调用结尾 ⇒ 不折叠 ----

    @Test
    fun `decision 2 reply ending with a tool call is not folded`() {
        val blocks = listOf(reasoning("想一想"), tool("todo_write"), tool("search_web", id = "call_b"))
            .groupMessageParts(wholeTurnFolding = true)

        val chain = blocks.onlyThinking()
        assertFalse("没有末尾回答可留时折叠只会把全部内容藏起来", chain.canCollapse)
        assertFalse(chain.forceExpand)
        assertTrue(blocks.none { it is MessagePartBlock.ContentBlock })
    }

    // ---- 额外：决策 7 错误常驻红色标记但不强制展开 ----

    @Test
    fun `decision 7 errors are flagged but do not force expansion`() {
        val blocks = listOf(
            tool("search_web", id = "call_a", output = """{"error":"rate_limited"}"""),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)

        val chain = blocks.onlyThinking()
        assertTrue(chain.steps.any { it.hasError() })
        assertFalse("错误只标记，不强制展开", chain.forceExpand)
        assertTrue(chain.canCollapse)

        val denied = listOf(
            tool("workspace_shell", id = "call_b", output = null, approval = ToolApprovalState.Denied("不批")),
            text("最终回答"),
        ).groupMessageParts(wholeTurnFolding = true)
        assertTrue(denied.onlyThinking().steps.any { it.hasError() })
    }

    // ---- 额外：关闭整轮折叠时保持旧行为 ----

    @Test
    fun `folding disabled keeps narration in the message body`() {
        val blocks = listOf(text("过程解说"), tool("search_web"), text("回答"))
            .groupMessageParts(wholeTurnFolding = false)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessagePartBlock.ContentBlock)
        assertTrue(blocks[1].let { (it as MessagePartBlock.ThinkingBlock).steps.single() is ThinkingStep.ToolStep })
        assertFalse(blocks[1].let { (it as MessagePartBlock.ThinkingBlock).canCollapse })
        assertTrue(blocks[2] is MessagePartBlock.ContentBlock)
    }

    // ---- 额外：ServerTool 的完成态判定（本仓无 isFinished/FAILED）----

    @Test
    fun `server tool status distinguishes running from completed`() {
        val running = listOf(serverTool(status = ServerToolStatus.IN_PROGRESS), text("回答"))
            .groupMessageParts(wholeTurnFolding = true)
            .onlyThinking()
        assertEquals(ServerToolStatus.IN_PROGRESS, (running.steps.single() as ThinkingStep.ServerToolStep).tool.status)
    }

    // ---- helpers ----

    private fun MessagePartBlock.ThinkingBlock.tools() =
        steps.filterIsInstance<ThinkingStep.ToolStep>()
}
