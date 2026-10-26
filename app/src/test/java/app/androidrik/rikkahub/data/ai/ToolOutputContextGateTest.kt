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

    /** 窗口=1 的模型：任何消息估算都 ≥80% ⇒ 收缩档 8K（比构造精确 65% 边界稳）。 */
    private val tinyWindowModel = bigWindowModel.copy(customContextLength = 1)

    private val providerSetting = ProviderSetting.OpenAI(
        id = Uuid.parse("22222222-2222-4222-8222-222222222222"),
        models = listOf(bigWindowModel, tinyWindowModel),
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
                messages = listOf(UIMessage.user("hi there, this is a reasonably long test message")),
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
            model = tinyWindowModel,
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
            model = tinyWindowModel,
            toolCallId = "t2",
        )
        val shrunkText = shrunkOutput.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue("收缩档 search_web 不再豁免", shrunkText.contains("[Tool output truncated:"))
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
