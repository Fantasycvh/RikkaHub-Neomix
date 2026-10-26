package app.androidrik.rikkahub.data.ai

import android.content.Context
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
import app.androidrik.ai.ui.terminalChunk
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.JsonInstant
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 上下文闸 A（design-tool-context-gate §4）的回归用例：
 *
 * 1. **截断恒生效** —— 原 `|| !hasShellAccess` 短路让无 shell 会话的超大输出原样进历史；
 *    废除后无恢复通道的会话得到「缩窄重跑」提示而不是 30K+ 无界输出。
 * 2. **per-call 动态限幅** —— 窗口占用 ≥65%/≥80% 时单条预算收到 16K/8K。
 * 3. **恢复指引按能力三分支** —— workspace 分页读 / shell cat+grep / 缩窄重跑。
 * 4. **search_web 豁免只在基础档生效**。
 * 5. **失败调用的错误文本同样受限**（堆栈可以非常长）。
 *
 * 跑整条生成流（同 [GenerationToolLoopTest] 的脚本化 provider 模式）；与它不同的是
 * 截断路径**真的写 `filesDir`**，因此 Context 用临时目录兜底。
 */
class ToolOutputContextGateTest {

    // ---- 假 provider（同 GenerationToolLoopTest） ----

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

    // ---- 夹具 ----

    private val tempDir: File = Files.createTempDirectory("tool-gate-test").toFile()

    /** filesDir 指到临时目录：截断路径要把全文写进 tool_outputs/（ContextWrapper(null) 会炸）。 */
    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = tempDir
    }

    /** 大窗口模型：占用率低 ⇒ 基础档 32K。 */
    private val bigWindowModel = Model(
        modelId = "test-model",
        displayName = "Test Model",
        id = Uuid.parse("11111111-1111-4111-8111-111111111111"),
        abilities = listOf(ModelAbility.TOOL),
    )

    /**
     * A 收缩档专用模型：窗口 40000。用户文本 128K 字符（启发式 ≈32K token）⇒ 占用率 0.80
     * 恰好落进收缩档；截断后的再请求 ≈33075 token ≤ 85% 软限（34000），不会误触 B 闸硬限。
     */
    private val shrinkModel = bigWindowModel.copy(customContextLength = 40_000)

    private fun longTextMessages(): List<UIMessage> = listOf(UIMessage.user("A".repeat(128_000)))

    private val providerSetting = ProviderSetting.OpenAI(
        id = Uuid.parse("22222222-2222-4222-8222-222222222222"),
        models = listOf(bigWindowModel, shrinkModel),
        apiKey = "test-key",
    )

    private fun handler(provider: Provider<ProviderSetting.OpenAI>): GenerationHandler {
        val manager = ProviderManager(OkHttpClient(), context)
        manager.registerProvider("openai", provider)
        return GenerationHandler(
            context = context,
            providerManager = manager,
            json = JsonInstant,
            usageRecorder = noopUsageRecorder(),
        )
    }

    private fun bigTool(name: String, outputChars: Int) = Tool(
        name = name,
        description = "",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        needsApproval = { false },
        executionTimeoutMillis = null,
        execute = { listOf(UIMessagePart.Text("x".repeat(outputChars))) },
    )

    private val failTool = Tool(
        name = "fail_tool",
        description = "",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        needsApproval = { false },
        executionTimeoutMillis = null,
        execute = { error("E".repeat(40 * 1024)) },
    )

    private fun toolCall(id: String, name: String, input: String = "{}") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
    )

    private fun toolCallStep(vararg calls: UIMessagePart.Tool): List<MessageChunk> = listOf(
        MessageChunk(
            id = "chunk-tool",
            model = bigWindowModel.modelId,
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
            model = bigWindowModel.modelId,
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

    /** 跑完一轮（调用工具 → 文本收尾），返回**最后一次**消息快照里的工具结果 part。 */
    private fun runAndGetToolOutput(
        provider: ScriptedProvider,
        tools: List<Tool>,
        model: Model = bigWindowModel,
        toolCallId: String = "t1",
    ): UIMessagePart.Tool {
        val finalMessages = AtomicRef<List<UIMessage>>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                model = model,
                // 足够长：窗口=1 的收缩档用例里 estimate 必须非零，比率判定才稳定
                messages = longTextMessages(),
                assistant = Assistant(chatModelId = model.id),
                tools = tools,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) finalMessages.value = chunk.messages
            }
        }
        val tool = finalMessages.value
            ?.asSequence()
            ?.flatMap { it.parts }
            ?.filterIsInstance<UIMessagePart.Tool>()
            ?.firstOrNull { it.toolCallId == toolCallId }
        assertTrue("消息里找不到工具结果 $toolCallId", tool != null)
        return tool!!
    }

    private class AtomicRef<T> {
        @Volatile
        var value: T? = null
    }

    private fun toolOutputsDir(): File = File(tempDir, FileFolders.TOOL_OUTPUTS)

    // ---- 三档边界（纯函数核） ----

    @Test
    fun `toolOutputBudgetFor picks the three tiers by fill ratio`() {
        assertEquals(32 * 1024, toolOutputBudgetFor(estimatedTokens = 640, window = 1_000))
        assertEquals(16 * 1024, toolOutputBudgetFor(estimatedTokens = 650, window = 1_000))
        assertEquals(16 * 1024, toolOutputBudgetFor(estimatedTokens = 799, window = 1_000))
        assertEquals(8 * 1024, toolOutputBudgetFor(estimatedTokens = 800, window = 1_000))
        // 窗口非法值不炸（coerceAtLeast(1)）
        assertEquals(8 * 1024, toolOutputBudgetFor(estimatedTokens = 10, window = 0))
    }

    // ---- 截断恒生效 + 能力三分支 ----

    @Test
    fun `huge output is truncated even without shell and offloads to file`() {
        val provider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t1", "big_tool")) else textStep("done")
        }
        val output = runAndGetToolOutput(
            provider,
            tools = listOf(bigTool("big_tool", outputChars = 40 * 1024)),
        )
        val text = output.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue("无 shell 的 40K 输出应被截断", text.length < 40 * 1024)
        assertTrue(text.contains("[Tool output truncated:"))
        // 两者皆无 → 诚实告知缩窄重跑，不给假指引
        assertTrue(text.contains("Re-run the tool with narrower arguments"))
        assertFalse(text.contains("cat /tool_outputs/"))
        assertFalse(text.contains("workspace_read_file"))
        // 全文落盘且回环一致
        val full = File(toolOutputsDir(), "t1.txt")
        assertTrue(full.exists())
        assertEquals(40 * 1024, full.readText().length)
    }

    @Test
    fun `workspace-bound session gets the paged-read hint`() {
        val provider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t1", "big_tool")) else textStep("done")
        }
        val output = runAndGetToolOutput(
            provider,
            tools = listOf(bigTool("big_tool", outputChars = 40 * 1024), bigTool("workspace_read_file", 1)),
        )
        val text = output.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("workspace_read_file(path=\"/tool_outputs/t1.txt\", offset=1, limit=200)"))
        assertFalse(text.contains("cat /tool_outputs/"))
    }

    @Test
    fun `shell-only session keeps the cat and grep hint`() {
        val provider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t1", "big_tool")) else textStep("done")
        }
        val output = runAndGetToolOutput(
            provider,
            tools = listOf(bigTool("big_tool", outputChars = 40 * 1024), bigTool("workspace_shell", 1)),
        )
        val text = output.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("cat /tool_outputs/t1.txt"))
        assertFalse(text.contains("workspace_read_file"))
    }

    // ---- 动态限幅 + 豁免语义 ----

    @Test
    fun `tiny window shrinks the per-call budget below the base tier`() {
        val provider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t1", "big_tool")) else textStep("done")
        }
        val output = runAndGetToolOutput(
            provider,
            tools = listOf(bigTool("big_tool", outputChars = 10 * 1024)),
            // 10K < 32K 基础档 ⇒ 不触发；窗口=1 ⇒ ≥80% ⇒ 预算 8K ⇒ 必须截断
            model = shrinkModel,
        )
        val text = output.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("[Tool output truncated:"))
        assertTrue("预览 + 头部应远小于 10K", text.length < 10 * 1024)
    }

    @Test
    fun `search_web exemption holds at base tier but collapses at shrunk tier`() {
        // 基础档：10K 输出豁免（不截断、不落盘）
        val baseProvider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t1", "search_web")) else textStep("done")
        }
        val baseOutput = runAndGetToolOutput(
            baseProvider,
            tools = listOf(bigTool("search_web", outputChars = 10 * 1024)),
        )
        val baseText = baseOutput.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertFalse("基础档 search_web 应保持豁免", baseText.contains("[Tool output truncated:"))
        assertEquals(10 * 1024, baseText.length)

        // 收缩档：豁免失效，走截断
        val shrunkProvider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t2", "search_web")) else textStep("done")
        }
        val shrunkOutput = runAndGetToolOutput(
            shrunkProvider,
            tools = listOf(bigTool("search_web", outputChars = 10 * 1024)),
            model = shrinkModel,
            toolCallId = "t2",
        )
        val shrunkText = shrunkOutput.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue("收缩档 search_web 不再豁免", shrunkText.contains("[Tool output truncated:"))
    }

    // ---- 上下文闸 B：85% 紧急卸载 + 90% 硬限 ----

    /** 窗口 6000：40K 字符 ≈ 10K token ⇒ 必然超 85%（5100）；卸载后预览 ≈ 500 token ⇒ 低于硬限（5400）。 */
    private val gateModel = bigWindowModel.copy(customContextLength = 6_000)

    private fun historyWithHugeToolOutput(toolCallId: String, at: Int, size: Int = 6): List<UIMessage> {
        val hugePart = toolCall(toolCallId, "big_tool")
            .copy(output = listOf(UIMessagePart.Text("x".repeat(40 * 1024))))
        return List(size) { index ->
            when {
                index == at -> UIMessage(role = MessageRole.ASSISTANT, parts = listOf(hugePart))
                index % 2 == 0 -> UIMessage.user("msg-$index")
                else -> UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("ok-$index")))
            }
        }
    }

    @Test
    fun `old tool outputs beyond 85 percent are offloaded to guard files at request time`() {
        val sentLists = mutableListOf<List<UIMessage>>()
        val provider = ScriptedProvider { index, messages ->
            sentLists += messages
            if (index == 0) textStep("done") else error("只应发一次请求")
        }
        // 6 条消息，huge 在 index 1（< keepFrom=2）⇒ 可卸载；最近 4 条里没有大输出
        val input = historyWithHugeToolOutput("old1", at = 1)
        val chunks = mutableListOf<GenerationChunk>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = gateModel.id),
                model = gateModel,
                messages = input,
                assistant = Assistant(chatModelId = gateModel.id),
                tools = emptyList(),
            ).collect { chunks += it }
        }
        val sentTool = sentLists[0]
            .asSequence()
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .first { it.toolCallId == "old1" }
        val sentText = sentTool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue("发送面应替换为卸载投影", sentText.contains("[Context guard: output offloaded]"))
        assertTrue(sentText.contains("/tool_outputs/guard-old1.txt"))
        // guard 文件保存全文
        val guard = File(toolOutputsDir(), "guard-old1.txt")
        assertTrue(guard.exists())
        assertEquals(40 * 1024, guard.readText().length)
        // 投影不落库：调用方传入的列表保持全文
        val inputTool = input[1].parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertEquals(40 * 1024, inputTool.output.filterIsInstance<UIMessagePart.Text>().first().text.length)
        // 最近 4 条未被改写：发送面里带卸载标记的工具 part 只有 old1 一个
        // （不能按位置断言：装配产物与输入列表的索引不一一对应）
        val markedCalls = sentLists[0]
            .asSequence()
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.output.filterIsInstance<UIMessagePart.Text>().any { t -> t.text.contains("offloaded") } }
            .map { it.toolCallId }
            .toList()
        assertEquals(listOf("old1"), markedCalls)
        // 没有硬限通知
        assertTrue(chunks.none { it is GenerationChunk.Notice })
    }

    @Test
    fun `offload skips the recent four messages and hard-stops at 90 percent`() {
        val sentLists = mutableListOf<List<UIMessage>>()
        val provider = ScriptedProvider { _, messages ->
            sentLists += messages
            textStep("done")
        }
        // huge 在 index 4（落在最近 4 条保护区内）⇒ 无法卸载 ⇒ 仍超 90% ⇒ 优雅停止
        val input = historyWithHugeToolOutput("recent1", at = 4)
        val chunks = mutableListOf<GenerationChunk>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = gateModel.id),
                model = gateModel,
                messages = input,
                assistant = Assistant(chatModelId = gateModel.id),
                tools = emptyList(),
            ).collect { chunks += it }
        }
        val notice = chunks.filterIsInstance<GenerationChunk.Notice>().singleOrNull()
        assertTrue("应有硬限停止通知", notice?.notice is GenerationNotice.ContextBudgetExhausted)
        // 保护区内的大输出没有被卸载
        assertFalse(File(toolOutputsDir(), "guard-recent1.txt").exists())
        // 硬停发生在请求发出之前 ⇒ 没有任何请求到达 provider；投影不落库，输入保持全文
        assertTrue("硬停前不应发出请求", sentLists.isEmpty())
        val stillFull = input[4].parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertEquals(
            40 * 1024,
            stillFull.output.filterIsInstance<UIMessagePart.Text>().first().text.length,
        )
    }

    @Test
    fun `offload is stable across tool loop steps`() {
        val sentLists = mutableListOf<List<UIMessage>>()
        val provider = ScriptedProvider { index, messages ->
            sentLists += messages
            when (index) {
                0 -> toolCallStep(toolCall("t1", "small_tool"))
                1 -> textStep("done")
                else -> error("只应发两次请求")
            }
        }
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = gateModel.id),
                model = gateModel,
                messages = historyWithHugeToolOutput("old1", at = 1),
                assistant = Assistant(chatModelId = gateModel.id),
                tools = listOf(
                    Tool(
                        name = "small_tool",
                        description = "",
                        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
                        needsApproval = { false },
                        executionTimeoutMillis = null,
                        execute = { listOf(UIMessagePart.Text("tiny")) },
                    )
                ),
            ).collect { /* 消费完即可 */ }
        }
        assertEquals(2, sentLists.size)
        // 两次请求的发送面都有卸载投影（runLoop 内存态保持全文，每步重新投影）
        sentLists.forEach { sent ->
            val marker = sent.asSequence()
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>()
                .first { it.toolCallId == "old1" }
                .output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            assertTrue(marker.contains("[Context guard: output offloaded]"))
        }
        assertEquals(40 * 1024, File(toolOutputsDir(), "guard-old1.txt").readText().length)
    }

    // ---- 上下文闸 C：聊天路径接入 ContextBudgetGuard（执行中评估） ----

    @Test
    fun `chat-side context guard stops the turn when remaining budget is exhausted`() {
        // 同 shrinkModel（窗口 40000）：历史 128K 字符 ≈32K token（80%，B 闸快速路径放行）。
        // 每个工具输出 30K 字符 → A 闸收到 8K（≈2K token）。三个结果后占用 ≈38K，
        // 第四个工具执行前剩余 <4K ⇒ C 守卫判定 Exhausted ⇒ 优雅停止通知。
        val tools = (1..4).map { i ->
            bigTool("chat_tool_$i", outputChars = 30 * 1024)
        }
        val provider = ScriptedProvider { index, _ ->
            when (index) {
                0 -> toolCallStep(*tools.mapIndexed { i, _ -> toolCall("ct$i", "chat_tool_${i + 1}") }.toTypedArray())
                else -> textStep("done")
            }
        }
        val chunks = mutableListOf<GenerationChunk>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = shrinkModel.id),
                model = shrinkModel,
                messages = longTextMessages(),
                assistant = Assistant(chatModelId = shrinkModel.id),
                tools = tools,
                contextGuard = ContextBudgetGuard(windowTokens = 40_000),
            ).collect { chunks += it }
        }
        val notice = chunks.filterIsInstance<GenerationChunk.Notice>().singleOrNull()
        assertTrue("C 守卫应在剩余 <4K 时优雅停止", notice?.notice is GenerationNotice.ContextBudgetExhausted)
        // B 闸没有硬停（80% 起步 + 每步只 +2K，未越过 90%）；结果已全部回填
        val committed = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages
        assertEquals(
            listOf("ct0", "ct1", "ct2", "ct3"),
            committed
                .asSequence()
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>()
                .map { it.toolCallId }
                .sorted()
                .toList(),
        )
    }

    // ---- 错误文本限幅 ----

    @Test
    fun `failed call error text is capped at the per-call budget`() {
        val provider = ScriptedProvider { index, _ ->
            if (index == 0) toolCallStep(toolCall("t1", "fail_tool")) else textStep("done")
        }
        val output = runAndGetToolOutput(provider, tools = listOf(failTool))
        val text = output.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("[... error text truncated at"))
        // JSON {"error": "..."} 包裹 + 40K 截到 32K（大窗口基础档）
        assertTrue("错误文本应被硬切到预算内", text.length < 33 * 1024)
    }
}
