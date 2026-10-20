package app.androidrik.rikkahub.data.ai

import android.content.ContextWrapper
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageChoice
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.terminalChunk
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.service.withRestoredToolOutputs
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 多步工具循环的同源回归用例（上游 `e10e1043` 修掉、本仓 `GenerationHandler` 同源存在的三条）：
 *
 * 1. **空输出工具会被之后每一步重复执行** —— `UIMessagePart.Tool.isExecuted` 的定义是
 *    `output.isNotEmpty()`，工具返回 `emptyList()` 时回填后仍算「未执行」，下一轮又把它选出来。
 *    修法：回填前补 `EMPTY_TOOL_OUTPUT` 占位。
 * 2. **同批「需审批 + 自动」时自动工具被整批跳过** —— 审批阶段提前 `break`，且续跑筛
 *    `canResumeExecution`（`Auto → false`）。修法：审批阶段只标 `Pending`，执行阶段跑所有未执行的。
 * 3. **停止生成时已执行完的工具结果丢失** —— 结果攒在局部列表、只在整批之后统一回填，
 *    取消直接 rethrow。修法：每完成一个就回填并 emit。
 *
 * 为什么这里是**真跑循环**而不是纯逻辑单测：这三条都关乎「执行阶段怎么选、什么时候回填」，
 * 只有把 `GenerationHandler.generateText` 整条流跑起来才能证伪。循环唯一的 Android 依赖是
 * `Context`，只在两处用到（`Log`、超长输出的 `filesDir` 落盘），故用 `ContextWrapper(null)`
 * + `unitTests.returnDefaultValues` 兜住；provider 侧用脚本化的假实现（本仓无 Robolectric，
 * 也不需要真的发请求）。
 *
 * 用例互相独立：三处修复各自回滚一次都能让对应用例变红（见类尾注释里的实测记录）。
 */
class GenerationToolLoopTest {

    // ---- 假 provider ----

    private class ScriptedProvider(
        private val script: (callIndex: Int, messages: List<UIMessage>) -> List<MessageChunk>,
    ) : Provider<ProviderSetting.OpenAI> {
        val calls = AtomicInteger(0)

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("这些用例只走流式路径")

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            script(calls.getAndIncrement(), messages).forEach { emit(it) }
        }
    }

    // ---- 固定夹具 ----

    private val model = Model(
        modelId = "test-model",
        displayName = "Test Model",
        id = Uuid.parse("11111111-1111-4111-8111-111111111111"),
        abilities = listOf(ModelAbility.TOOL),
    )

    private val providerSetting = ProviderSetting.OpenAI(
        id = Uuid.parse("22222222-2222-4222-8222-222222222222"),
        models = listOf(model),
        apiKey = "test-key",
    )

    private fun handler(provider: Provider<ProviderSetting.OpenAI>): GenerationHandler {
        // ContextWrapper(null)：循环只把它交给 Log / filesDir，单测里两者都不该被真正用到
        val context = ContextWrapper(null)
        val manager = ProviderManager(OkHttpClient(), context)
        manager.registerProvider("openai", provider)
        return GenerationHandler(
            context = context,
            providerManager = manager,
            json = JsonInstant,
            // 空记录器：这些用例考的是生成循环，不需要真的往 usage_record 写行
            usageRecorder = noopUsageRecorder(),
        )
    }

    private fun tool(
        name: String,
        needsApproval: Boolean = false,
        execute: suspend () -> List<UIMessagePart>,
    ) = Tool(
        name = name,
        description = "",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        needsApproval = { needsApproval },
        executionTimeoutMillis = null,
        execute = { execute() },
    )

    private fun toolCall(id: String, name: String, input: String = "{}") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
    )

    /** 一次「模型只下发工具调用」的响应（必须带 [terminalChunk]，否则会被判裸断流并自动续写）。 */
    private fun toolCallStep(vararg calls: UIMessagePart.Tool): List<MessageChunk> = listOf(
        MessageChunk(
            id = "chunk-tool",
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = calls.toList()),
                    message = null,
                    finishReason = "tool_calls",
                )
            ),
        ),
        terminalChunk(),
    )

    private fun textStep(text: String): List<MessageChunk> = listOf(
        MessageChunk(
            id = "chunk-text",
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
                    message = null,
                    finishReason = "stop",
                )
            ),
        ),
        terminalChunk(),
    )

    /** 跑完一整轮生成，返回每次 `GenerationChunk.Messages` 的快照（末项即内存态）。 */
    private fun runGeneration(
        provider: ScriptedProvider,
        tools: List<Tool>,
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
    ): List<List<UIMessage>> {
        val sent = mutableListOf<List<UIMessage>>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                model = model,
                messages = messages,
                assistant = Assistant(chatModelId = model.id),
                tools = tools,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) sent += chunk.messages
            }
        }
        assertTrue("生成过程应当至少回写过一次消息", sent.isNotEmpty())
        return sent
    }

    // ---- Bug 4：空输出 ⇒ 之后每一步都被重新执行 ----

    @Test
    fun `a tool with empty output is executed once and counts as executed`() {
        val executions = AtomicInteger(0)
        val empty = tool("empty_tool") {
            executions.incrementAndGet()
            emptyList()
        }
        val provider = ScriptedProvider { callIndex, _ ->
            if (callIndex == 0) toolCallStep(toolCall("call-1", "empty_tool")) else textStep("done")
        }

        val sent = runGeneration(provider, listOf(empty))
        val toolPart = sent.last().last().getTools().single()

        // 只执行一次：修复前空输出使 isExecuted 仍为假，同一工具会被反复执行到
        // LoopGuard 拦下的第 3 次（并额外产生一条误导性的「工具循环」提示）
        assertEquals("工具只该执行一次", 1, executions.get())
        // 空输出补了占位 ⇒ isExecuted 为真 ⇒ 下一轮的 filter { !isExecuted } 不会再选出它
        assertTrue("空输出必须变成「已执行」（占位 part）", toolPart.isExecuted)
        assertEquals(
            listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)),
            toolPart.output,
        )
        // 只有两步：出工具调用 → 出文本。修复前会因重复执行而多调多次
        assertEquals(2, provider.calls.get())
    }

    // ---- Bug 1：同批「需审批 + 自动」时自动工具被跳过 ----

    @Test
    fun `auto tools in the same batch run even when a sibling needs approval`() {
        val autoExecutions = AtomicInteger(0)
        val dangerExecutions = AtomicInteger(0)
        val auto = tool("auto_tool") {
            autoExecutions.incrementAndGet()
            listOf(UIMessagePart.Text("auto-ok"))
        }
        val danger = tool("danger_tool", needsApproval = true) {
            dangerExecutions.incrementAndGet()
            listOf(UIMessagePart.Text("danger-ok"))
        }
        val provider = ScriptedProvider { _, _ ->
            toolCallStep(toolCall("call-auto", "auto_tool"), toolCall("call-danger", "danger_tool"))
        }

        val sent = runGeneration(provider, listOf(auto, danger))
        val tools = sent.last().last().getTools()

        // 自动的那个**本轮就执行**了
        assertEquals("自动工具必须在本轮执行", 1, autoExecutions.get())
        val autoPart = tools.single { it.toolCallId == "call-auto" }
        assertTrue(autoPart.isExecuted)
        assertEquals(listOf(UIMessagePart.Text("auto-ok")), autoPart.output)

        // 待审批的仍然挂起等待（未执行、状态 Pending）
        assertEquals("待审批的工具不该被执行", 0, dangerExecutions.get())
        val dangerPart = tools.single { it.toolCallId == "call-danger" }
        assertFalse(dangerPart.isExecuted)
        assertEquals(ToolApprovalState.Pending, dangerPart.approvalState)
        assertTrue("审批闸门要能认出它仍在等", dangerPart.isPending)
    }

    // ---- Bug 2：停止生成时已执行完的工具结果丢失 ----

    @Test
    fun `results of finished tools survive cancellation`() {
        val firstExecuted = AtomicInteger(0)
        val first = tool("first_tool") {
            firstExecuted.incrementAndGet()
            listOf(UIMessagePart.Text("first-ok"))
        }
        // 第二个工具挂住，模拟「用户在这时点了停止」
        val hang = tool("hang_tool") {
            delay(60_000)
            listOf(UIMessagePart.Text("never"))
        }
        val provider = ScriptedProvider { _, _ ->
            toolCallStep(toolCall("call-first", "first_tool"), toolCall("call-hang", "hang_tool"))
        }
        val sent = mutableListOf<List<UIMessage>>()

        runBlocking {
            val job = launch {
                handler(provider).generateText(
                    settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                    model = model,
                    messages = listOf(UIMessage.user("hi")),
                    assistant = Assistant(chatModelId = model.id),
                    tools = listOf(first, hang),
                ).collect { chunk ->
                    if (chunk is GenerationChunk.Messages) {
                        synchronized(sent) { sent += chunk.messages }
                    }
                }
            }
            try {
                // 等第一个工具的结果出现（修复后会在第二个工具开始前就 emit）
                withTimeout(10_000) {
                    while (synchronized(sent) { sent.isEmpty() } ||
                        sent.last().last().getTools().none { it.toolCallId == "call-first" && it.isExecuted }
                    ) {
                        delay(10)
                    }
                }
            } finally {
                job.cancel()
                job.join()
            }
        }

        assertEquals(1, firstExecuted.get())
        // 取消时已完成的结果必须还在（停止生成只读内存态，见 ChatService.persistStoppedGeneration）
        val snapshot = synchronized(sent) { sent.last() }
        val tools = snapshot.last().getTools()
        val firstPart = tools.single { it.toolCallId == "call-first" }
        assertTrue("已完成工具的结果必须已回填", firstPart.isExecuted)
        assertEquals(listOf(UIMessagePart.Text("first-ok")), firstPart.output)
        // 挂住的那个自然没有结果（本轮被取消）
        assertFalse(tools.single { it.toolCallId == "call-hang" }.isExecuted)
    }

    // ---- 核查项 ①：工具**已算完**、还没回填就被取消 ----

    /**
     * 「工具返回结果」与「结果并进消息」之间被取消时，结果不能丢。
     *
     * 现场（本仓 2026-10-18 核查）：每个工具执行完立刻 `applyToolOutput` + `emit`，但**取消请求
     * 可以在两次挂起点之间的任意时刻从别的线程到达**（用户点停止）。那一下如果落在
     * 「execute 已返回、emit 还没发出去」的窗口里，`emit` 会抛取消 ⇒ 这一份结果从来没进过
     * 会话内存态 ⇒ 停止生成落库的是缺了最后一个工具结果的旧内容。
     *
     * 为什么不能只把 emit 包进 `NonCancellable`（本仓实测，两种写法都不行）：
     * 1. `flow {}` 的 SafeCollector 按 Job 校验发射上下文 ⇒ 换 `NonCancellable` 直接抛
     *    `Flow invariant is violated`（coroutines 1.11）；
     * 2. 改 `channelFlow` 让发射合法也只是把值放进缓冲：collect 端已被取消 ⇒ 缓冲值不会被取走
     *    （实测「取消 100ms 后再 send」的结果永远收不到）。
     * 因此回填点在 emit **之前**同步写一份快照（`onMessagesCommitted`），停止路径据此补进内存态。
     *
     * 本用例用「工具在 NonCancellable 里等取消信号、拿到信号后才返回结果」精确复现该窗口：
     * 协程恢复到「返回结果 → 回填」时**已经在取消状态**。
     */
    @Test
    fun `a tool result computed right before cancellation still lands in the messages`() {
        val resultComputed = CompletableDeferred<Unit>()
        val cancellationArrived = CompletableDeferred<Unit>()
        val slow = tool("slow_tool") {
            // 结果早就有了：等「取消已经发生」再返回，确保回填发生在取消之后
            withContext(NonCancellable) {
                resultComputed.complete(Unit)
                cancellationArrived.await()
            }
            listOf(UIMessagePart.Text("computed"))
        }
        val provider = ScriptedProvider { _, _ -> toolCallStep(toolCall("call-slow", "slow_tool")) }
        val committed = AtomicReference<List<UIMessage>?>()
        val emitted = mutableListOf<List<UIMessage>>()

        runBlocking {
            val job = launch {
                handler(provider).generateText(
                    settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                    model = model,
                    messages = listOf(UIMessage.user("hi")),
                    assistant = Assistant(chatModelId = model.id),
                    tools = listOf(slow),
                    // ChatService 就是这么接的：把「已回填」的快照记到会话上
                    onMessagesCommitted = { committed.set(it) },
                ).collect { chunk ->
                    if (chunk is GenerationChunk.Messages) emitted += chunk.messages
                }
            }
            withTimeout(10_000) { resultComputed.await() }
            job.cancel()
            // 让取消先落到生成协程/collector 上——这正是「回填晚了一步」的现场
            delay(100)
            cancellationArrived.complete(Unit)
            job.join()
        }

        val landed = committed.get()
        assertTrue(
            "工具已算完的结果必须落进消息（取消刚好落在回填前）——null 表示" +
                "这条路径只依赖 emit，而 emit 在取消后必然送不到",
            landed != null,
        )
        val toolPart = landed!!.last().getTools().single { it.toolCallId == "call-slow" }
        assertTrue("结果必须已回填（isExecuted）", toolPart.isExecuted)
        assertEquals(listOf(UIMessagePart.Text("computed")), toolPart.output)
        // 报文层面这次回填确实没送出去（collector 已取消）⇒ 上面这条断言不是靠 emit 蒙过的
        val deliveredByEmit = emitted.any { snapshot ->
            snapshot.last().getTools().any { it.toolCallId == "call-slow" && it.isExecuted }
        }
        assertFalse(
            "前置：取消后的 emit 不该送达（否则本用例证明不了落点的作用）",
            deliveredByEmit,
        )
    }

    /** 停止路径把落点补进内存态的那一步（`ChatService.withRestoredToolOutputs`）。 */
    @Test
    fun `stop path restores a commit that never reached the collector`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        ).copy(
            messageNodes = listOf(
                UIMessage.user("hi").toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("半截正文"), toolCall("call-slow", "slow_tool")),
                ).toMessageNode(),
            ),
        )
        val baseline = conversation.currentMessages
        // 回填后的快照（落点里存的就是它）
        val committed = applyToolOutput(
            baseline,
            toolCall("call-slow", "slow_tool").copy(
                output = listOf(UIMessagePart.Text("computed")),
            ),
        )

        val restored = conversation.withRestoredToolOutputs(committed)

        val restoredTools = restored.currentMessages.last().getTools().single()
        assertTrue(
            "停止路径必须把落点的结果补进内存态（否则落库的是旧内容）",
            restoredTools.isExecuted,
        )
        assertEquals(listOf(UIMessagePart.Text("computed")), restoredTools.output)
        // 只补工具结果：正文一个字都不能被快照改动
        assertEquals(
            listOf(UIMessagePart.Text("半截正文")),
            restored.currentMessages.last().parts.filterIsInstance<UIMessagePart.Text>(),
        )
    }

    /**
     * 停止路径的补回必须**只增不改**：内存里比快照更新的正文不能被旧快照盖回去。
     *
     * 现场：回填发生在第 N 步，之后模型又流式吐了正文（内存更新），此时用户点停止。
     * 若停止路径整份替换成快照，那几段后来的正文就丢了——那是另一种丢内容。
     */
    @Test
    fun `stop path never rolls back newer streamed text`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        ).copy(
            messageNodes = listOf(
                UIMessage.user("hi").toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("第 N 步正文"), toolCall("call-slow", "slow_tool")),
                ).toMessageNode(),
            ),
        )
        val committed = applyToolOutput(
            conversation.currentMessages,
            toolCall("call-slow", "slow_tool").copy(output = listOf(UIMessagePart.Text("computed"))),
        )
        // 快照之后又收到的流式正文（内存态比快照新）
        val live = conversation.copy(
            messageNodes = conversation.messageNodes.map { node ->
                node.copy(messages = node.messages.map { message ->
                    message.copy(parts = message.parts + UIMessagePart.Text("之后的正文"))
                })
            },
        )

        val restored = live.withRestoredToolOutputs(committed)

        val texts = restored.currentMessages.last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals("较新的正文必须保留", listOf("第 N 步正文", "之后的正文"), texts)
        assertTrue(
            "同时把没送达的工具结果补上",
            restored.currentMessages.last().getTools().single().isExecuted,
        )
    }

    // ---- Bug 1 的另一半：续跑时也不能漏掉同批的 Auto ----

    @Test
    fun `resuming after approval also runs auto siblings left behind`() {
        val approvedRuns = AtomicInteger(0)
        val autoRuns = AtomicInteger(0)
        val approved = tool("approved_tool") {
            approvedRuns.incrementAndGet()
            listOf(UIMessagePart.Text("approved-ok"))
        }
        val auto = tool("auto_tool") {
            autoRuns.incrementAndGet()
            listOf(UIMessagePart.Text("auto-ok"))
        }
        val seenByProvider = mutableListOf<List<UIMessage>>()
        val provider = ScriptedProvider { _, messages ->
            seenByProvider += messages
            textStep("done")
        }
        // 旧数据形态：审批已通过，同批的 Auto 调用被留在「未执行」（历史 bug 的现场）
        val history = listOf(
            UIMessage.user("hi"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    toolCall("call-approved", "approved_tool")
                        .copy(approvalState = ToolApprovalState.Approved),
                    toolCall("call-auto", "auto_tool"),
                ),
            ),
        )

        runGeneration(provider, listOf(approved, auto), messages = history)

        assertEquals(1, approvedRuns.get())
        assertEquals("续跑必须把落下的自动调用一并补上", 1, autoRuns.get())
        val sentTools = seenByProvider.first().last().getTools()
        assertTrue("请求里不能出现没有结果的工具调用", sentTools.all { it.isExecuted })
        // 更关键：请求里的结果必须是**真结果**。漏执行时发送前的 ToolResultBalancingTransformer
        // 会补一条「结果拿不到了 / 别自动重跑」的合成结果（见 SYNTHESIZED_TOOL_RESULT_TEXT）
        // 兜底 —— 兜底在异常路径上是对的，但「同批 Auto 被漏掉」绝不能靠它兜。
        val autoInRequest = sentTools.single { it.toolCallId == "call-auto" }
        assertEquals(listOf(UIMessagePart.Text("auto-ok")), autoInRequest.output)
        assertEquals("续跑不该先空转一次生成", 1, provider.calls.get())
    }

    // ---- 审批判定的纯逻辑（`markPendingApprovals` / `toolsToExecute`）----

    @Test
    fun `approval phase only promotes brand-new unapproved calls`() {
        val approved = toolCall("a", "t_a").copy(approvalState = ToolApprovalState.Approved)
        val pending = toolCall("b", "t_b").copy(approvalState = ToolApprovalState.Pending)
        val auto = toolCall("c", "t_c")
        val executed = toolCall("d", "t_d").copy(output = listOf(UIMessagePart.Text("x")))

        val marked = markPendingApprovals(
            tools = listOf(approved, pending, auto, executed),
            requiresApproval = { it.toolName == "t_c" },
        )

        // 已批准的不能被「退回去重新等审批」——那会让用户在同一个工具上被问第二遍
        assertEquals(ToolApprovalState.Approved, marked[0].approvalState)
        assertEquals(ToolApprovalState.Pending, marked[1].approvalState)
        assertEquals("新出现且需要审批的才置 Pending", ToolApprovalState.Pending, marked[2].approvalState)
        assertTrue(marked[3].isExecuted)
        // 执行集合 = 所有尚未执行的（含 Auto；Pending 也在这里，由执行处的 when 分支跳过）
        assertEquals(
            listOf("a", "b", "c"),
            toolsToExecute(marked).map { it.toolCallId },
        )
    }

    @Test
    fun `headless runs deny instead of waiting for approval`() {
        val marked = markPendingApprovals(
            tools = listOf(toolCall("a", "t_a")),
            requiresApproval = { true },
            autoDeny = true,
        )

        // 无人可批 ⇒ 策略拒绝（走既有 Denied 通路把原因回给模型），不能挂在 Pending 上永远卡死
        assertTrue(marked.single().approvalState is ToolApprovalState.Denied)
        assertFalse(marked.single().isPending)
        assertEquals(1, toolsToExecute(marked).size)
    }

    private companion object {
        // ---- 证伪记录（把任一修复改回原样后跑本类，逐条实测）----
        // Bug 4 回滚：`a tool with empty output ...` 红 —— 空输出使 isExecuted 仍为假，
        //   同一工具被执行 3 次（LoopGuard 的阈值）并多出一次请求。
        // Bug 1 回滚：`auto tools in the same batch ...` 红 —— autoExecutions == 0；
        //   `resuming after approval ...` 红 —— 请求里带着没有结果的 tool call / 多一次生成。
        // Bug 2 回滚：`results of finished tools survive cancellation` 红 —— 等不到 call-first 的回填。
    }
}
