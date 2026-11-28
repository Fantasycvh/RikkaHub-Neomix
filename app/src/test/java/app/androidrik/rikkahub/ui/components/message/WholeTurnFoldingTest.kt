package app.androidrik.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.DEEPSEEK_RAW_CALL_METADATA_KEY
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    fun `1 plain text before and after a tool remains body content`() {
        val before = text("我先查一下")
        val answer = text("最终回答")
        val blocks = listOf(before, tool("search_web"), answer).groupMessageParts(wholeTurnFolding = true)
        assertEquals(3, blocks.size)
        assertEquals(listOf(before, answer), blocks.filterIsInstance<MessagePartBlock.ContentBlock>().map { it.part })
        val chain = blocks.onlyThinking()
        assertEquals(1, chain.steps.size)
        assertTrue(chain.steps.single() is ThinkingStep.ToolStep)
        assertTrue(chain.canCollapse)
        assertFalse(chain.forceExpand)
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
    fun `3 enabling folding never changes plain text ownership`() {
        val parts = listOf(text("过程解说或正文，无法确定"), tool("search_web"), text("回答"))
        val folded = parts.groupMessageParts(wholeTurnFolding = true)
        val expanded = parts.groupMessageParts(wholeTurnFolding = false)
        assertEquals(expanded.filterIsInstance<MessagePartBlock.ContentBlock>(), folded.filterIsInstance<MessagePartBlock.ContentBlock>())
        assertEquals(2, folded.count { it is MessagePartBlock.ContentBlock })
    }

    @Test
    fun `Vertex search after model output keeps final answer visible and still folds client tool narration`() {
        val grounding = serverTool(name = "google_search").copy(
            metadata = ServerToolMetadata(protocol = ServerToolProtocol.GOOGLE_INTERACTIONS).toMetadata(),
        )
        val answer = text("The current year is 2026.")
        val blocks = listOf(text("Let me check"), tool("lookup"), answer, grounding)
            .groupMessageParts(wholeTurnFolding = true)

        val content = blocks.filterIsInstance<MessagePartBlock.ContentBlock>().last()
        assertEquals(answer, content.part)
        assertEquals(2, content.index)
        val (clientChain, groundingChain) = blocks.thinkingBlocks()
        assertTrue(clientChain.canCollapse)
        assertTrue(clientChain.steps.single() is ThinkingStep.ToolStep)
        assertEquals(listOf(ThinkingStep.ServerToolStep(grounding)), groundingChain.steps)
    }

    /** DeepSeek 内置工具块：`server_tool_calls` 解析后原始 JSON 存在 `deepseek_raw_call`（配料 01 §8） */
    private fun deepSeekSearchTool(name: String = "deep_search") = serverTool(name = name).copy(
        metadata = buildJsonObject {
            put(DEEPSEEK_RAW_CALL_METADATA_KEY, buildJsonObject { put("kind", JsonPrimitive(name)) })
        },
    )

    @Test
    fun `DeepSeek search steps after the answer keep it visible and still fold client tool narration`() {
        val grounding = deepSeekSearchTool()
        val answer = text("The current year is 2026.")
        val blocks = listOf(text("Let me check"), tool("lookup"), answer, grounding)
            .groupMessageParts(wholeTurnFolding = true)

        val content = blocks.filterIsInstance<MessagePartBlock.ContentBlock>().last()
        assertEquals(answer, content.part)
        assertEquals(2, content.index)
        val (clientChain, groundingChain) = blocks.thinkingBlocks()
        assertTrue(clientChain.canCollapse)
        assertTrue(clientChain.steps.single() is ThinkingStep.ToolStep)
        assertEquals(listOf(ThinkingStep.ServerToolStep(grounding)), groundingChain.steps)
    }

    @Test
    fun `DeepSeek trailing tool steps keep the streamed answer as content`() {
        // DeepSeek 实测线形（配料 04）：正文先流完，末帧 reasoning + server_tool_calls 整块下发
        val search = deepSeekSearchTool()
        val answer = text("最终回答")
        val blocks = listOf(answer, reasoning("最后一步思考"), search)
            .groupMessageParts(wholeTurnFolding = true)

        val content = blocks.filterIsInstance<MessagePartBlock.ContentBlock>().single()
        assertEquals(answer, content.part)
        assertEquals(0, content.index)
        val chain = blocks.onlyThinking()
        assertEquals(2, chain.steps.size)
        assertTrue(chain.steps[0] is ThinkingStep.ReasoningStep)
        assertEquals(listOf<ThinkingStep>(ThinkingStep.ServerToolStep(search)), chain.steps.drop(1))
    }

    @Test
    fun `DeepSeek search step as the only tool keeps both texts as content`() {
        // DeepSeek 工具块不判定解说边界 ⇒ 前置解说不再是 NarrationStep（与 Vertex 修复同款取舍：
        // 宁可少折叠一段解说，也不能把最终回答吞进思考链；实测线形里工具块总在正文之后，配料 04）
        val blocks = listOf(text("正在搜索"), deepSeekSearchTool(), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessagePartBlock.ContentBlock)
        val chain = blocks.onlyThinking()
        assertEquals(1, chain.steps.size)
        assertTrue(chain.steps.single() is ThinkingStep.ServerToolStep)
        val answer = blocks[2] as MessagePartBlock.ContentBlock
        assertEquals("最终回答", (answer.part as UIMessagePart.Text).text)
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
    fun `8 unknown server steps cannot reclassify preceding text`() {
        val answer = text("正在搜索也可能是回答正文")
        val blocks = listOf(answer, serverTool(), text("最终回答")).groupMessageParts(wholeTurnFolding = true)
        assertEquals(3, blocks.size)
        assertEquals(answer, (blocks.first() as MessagePartBlock.ContentBlock).part)
        assertTrue(blocks.onlyThinking().steps.single() is ThinkingStep.ServerToolStep)
    }

    // ---- 9. 连续 Reasoning + Tool + Text 合成单块 ----

    @Test
    fun `9 tool calls on both sides of text keep text outside thinking chains`() {
        val middle = text("再读一下这个页面，或是一段已经完成的回答")
        val blocks = listOf(reasoning("想一想"), tool("search_web"), middle, tool("scrape_web"), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)
        assertEquals(4, blocks.size)
        assertEquals(2, blocks.thinkingBlocks().size)
        assertEquals(middle, (blocks[1] as MessagePartBlock.ContentBlock).part)
        assertEquals(2, blocks.thinkingBlocks().first().steps.size)
        assertTrue(blocks.thinkingBlocks().flatMap { it.steps }.none { it is ThinkingStep.NarrationStep })
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

        val summary = blocks.thinkingBlocks().flatMap { it.steps }.thinkingChainSummary()
        assertEquals(4, summary.stepCount)
        assertEquals(3, summary.toolCount)
        assertEquals(0, summary.narrationCount)
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

    // ---- 额外：纯思考回合（无工具）不折叠 ----

    @Test
    fun `pure thinking turn without any tool is not foldable`() {
        val blocks = listOf(reasoning("想一下再答"), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)

        // 分组不变：思考仍旧独立成块，回答仍是正文
        assertEquals(2, blocks.size)
        val chain = blocks.onlyThinking()
        assertEquals(1, chain.steps.size)
        assertTrue(chain.steps.single() is ThinkingStep.ReasoningStep)
        // 链里没有工具 ⇒ 不折（否则只剩「1 步 · 0 工具 · 0 段说明」的噪音头）
        assertEquals(0, chain.steps.thinkingChainSummary().toolCount)
        assertFalse("纯思考回合没有可折叠的过程", chain.canCollapse)
        assertFalse(chain.forceExpand)
    }

    // ---- 额外：含工具即折叠（与上一条成对，守住新规则的方向）----

    @Test
    fun `chain with at least one tool stays foldable`() {
        val viaTool = listOf(reasoning("想一想"), tool("search_web"), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)
            .onlyThinking()
        assertTrue("有工具调用 ⇒ 可以折", viaTool.canCollapse)

        // 服务端工具同样算「工具」
        val viaServerTool = listOf(reasoning("想一想"), serverTool(), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)
            .onlyThinking()
        assertTrue(viaServerTool.canCollapse)
    }

    // ---- 额外：交付物独占的 1-step 块不被“有工具才折”误伤 ----

    @Test
    fun `deliverable only block still never folds even though it holds a tool`() {
        val blocks = listOf(tool("generate_image", output = """{"images":["/files/a.png"]}"""), text("最终回答"))
            .groupMessageParts(wholeTurnFolding = true)

        val deliverable = blocks.onlyThinking()
        assertEquals(1, deliverable.steps.size)
        assertTrue(deliverable.steps.single() is ThinkingStep.ToolStep)
        assertFalse("交付物块本身是工具，但仍须始终可见", deliverable.canCollapse)
    }

    @Test
    fun `answer before charts remains body content during generation and after completion`() {
        val answer = text("下面是一篇技术长文。\n\n# KV Cache\n\n|方案|收益|\n|---|---|\n|缓存|节省计算|")
        val continuation = text("图表之后继续解释。")
        for (output in listOf(null, """{"success":true}""", """{"error":"INVALID_CHART"}""")) {
            val blocks = listOf(
                reasoning("plan the answer"), answer,
                tool("chart_display", output = output), continuation,
            ).groupMessageParts(wholeTurnFolding = true)

            assertEquals(listOf(answer, continuation), blocks.filterIsInstance<MessagePartBlock.ContentBlock>().map { it.part })
            assertTrue(blocks.thinkingBlocks().flatMap { it.steps }.none { it is ThinkingStep.NarrationStep })
        }
    }

    @Test
    fun `research tools fold without hiding text before or after them`() {
        val answer = text("# 研究结果\n\n下面用图片解释。")
        val before = text("先检索资料")
        val blocks = listOf(before, tool("search_web"), answer,
            tool("generate_image", output = """{"images":["/files/a.png"]}"""), text("图片说明"))
            .groupMessageParts(wholeTurnFolding = true)
        val research = blocks[1] as MessagePartBlock.ThinkingBlock
        assertTrue(research.steps.single() is ThinkingStep.ToolStep)
        assertTrue(research.canCollapse)
        assertEquals(before, (blocks[0] as MessagePartBlock.ContentBlock).part)
        assertEquals(answer, (blocks[2] as MessagePartBlock.ContentBlock).part)
    }

    // ---- helpers ----

    @Test
    fun `later housekeeping search and plugin tools cannot absorb previous answer segments`() {
        val intro = text("# 正文\n\n这是已经输出的回答。")
        val explanation = text("图表的说明和结论。")
        for (name in listOf("todo_write", "memory_tool", "search_web", "plugin__unknown_tool")) {
            val parts = listOf(
                intro, tool("chart_display", output = """{"success":true}"""),
                explanation, tool(name),
            )
            for (fold in listOf(false, true)) {
                val blocks = parts.groupMessageParts(wholeTurnFolding = fold)
                assertEquals(listOf(intro, explanation), blocks.filterIsInstance<MessagePartBlock.ContentBlock>().map { it.part })
                assertTrue(blocks.thinkingBlocks().flatMap { it.steps }.none { it is ThinkingStep.NarrationStep })
            }
        }
    }

    @Test
    fun `unrecognized trailing server tools keep all earlier answer text visible`() {
        val answer = text("已经完成的回答")
        val steps = listOf(serverTool("web_search"), serverTool("unknown_provider_tool"))
        for (step in steps) {
            val blocks = listOf(answer, step).groupMessageParts(wholeTurnFolding = true)
            assertEquals(answer, (blocks.first() as MessagePartBlock.ContentBlock).part)
            assertEquals(listOf(ThinkingStep.ServerToolStep(step)), blocks.onlyThinking().steps)
        }
    }

    private fun MessagePartBlock.ThinkingBlock.tools() =
        steps.filterIsInstance<ThinkingStep.ToolStep>()
}
