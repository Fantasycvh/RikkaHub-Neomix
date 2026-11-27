package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.content.ContextWrapper
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.service.RunOutcome
import app.androidrik.rikkahub.service.evaluateCompletion
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * 超窗撤回最大工具结果重发（NMoi 11-17 轮）测试：
 * 1. 两档分类（DEFINITE vs POSSIBLE vs NONE）：413/payload too large 归 POSSIBLE，不盲目摘工具结果；
 * 2. 无压缩回调或压缩返回 false 时，确定超窗摘掉最大工具结果并在同轮重发，原始消息不变且全文落盘；
 * 3. 与反应式压缩互补：先压缩，若压完仍确定超窗，再摘最大工具结果重发；
 * 4. 多个大工具结果按体积从大到小逐个撤回，超过上限后透传原错；
 * 5. 已截断带 `/tool_outputs/` 指针的结果沿用原路径，不覆盖磁盘全文；
 * 6. `ToolOutputEvictedOnOverflow` 为非终止通知，不影响子代理完成判定。
 */
class OverflowToolEvictionTest {

    private fun definiteOverflowError() = Exception(
        "Failed to get response: 400 This model's maximum context length is 8192 tokens. " +
            "Please reduce the length of the messages."
    )

    private fun possibleOverflowError() = Exception(
        "Failed to get response: 413 Payload Too Large"
    )

    private class ScriptedProvider(
        private val script: suspend (callIndex: Int, messages: List<UIMessage>, emit: suspend (StreamChunk) -> Unit) -> Unit,
    ) : Provider<ProviderSetting.OpenAI> {
        val calls = AtomicInteger(0)
        val receivedMessages = mutableListOf<List<UIMessage>>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = error("stream only")

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = flow {
            receivedMessages += messages
            script(calls.getAndIncrement(), messages) { chunk -> emit(chunk) }
        }
    }

    private val tempDir: File = Files.createTempDirectory("overflow-evict-test").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = tempDir
    }

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
        val manager = ProviderManager(OkHttpClient(), context, accountCredentials = { _, _ -> error("no account") })
        manager.registerProvider("openai", provider)
        return GenerationHandler(
            context = context,
            providerManager = manager,
            json = JsonInstant,
            usageRecorder = noopUsageRecorder(),
        )
    }

    private fun textStep(text: String): List<StreamChunk> = listOf(
        StreamChunk.TextStart("text"),
        StreamChunk.TextDelta("text", text),
        StreamChunk.TextEnd("text"),
        StreamChunk.Finish(finishReason = "stop"),
    )

    private fun messageWithTools(vararg tools: Pair<String, String>): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = tools.map { (id, body) ->
            UIMessagePart.Tool(
                toolCallId = id,
                toolName = "read_file_$id",
                input = "{}",
                output = listOf(UIMessagePart.Text(body)),
            )
        },
    )

    @Test
    fun `overflow classifier distinguishes definite vs possible vs none`() {
        assertEquals(OverflowConfidence.DEFINITE, classifyInputContextOverflow(definiteOverflowError()))
        assertEquals(
            OverflowConfidence.DEFINITE,
            classifyInputContextOverflow(Exception("400 prompt is too long: 150000 > 128000")),
        )
        assertEquals(
            OverflowConfidence.DEFINITE,
            classifyInputContextOverflow(Exception("too many tokens in request")),
        )
        assertEquals(OverflowConfidence.POSSIBLE, classifyInputContextOverflow(possibleOverflowError()))
        assertEquals(
            OverflowConfidence.POSSIBLE,
            classifyInputContextOverflow(Exception("Request Entity Too Large")),
        )
        assertEquals(
            OverflowConfidence.NONE,
            classifyInputContextOverflow(Exception("400 invalid_request_error: unknown parameter")),
        )
    }

    @Test
    fun `possible overflow 413 does not evict tool results`() {
        val provider = ScriptedProvider { _, _, _ -> throw possibleOverflowError() }
        val messages = listOf(
            UIMessage.user("analyze"),
            messageWithTools("big" to "X".repeat(20_000)),
        )
        val notices = mutableListOf<GenerationNotice>()
        val thrown = runCatching {
            runBlocking {
                handler(provider).generateText(
                    settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                    model = model,
                    messages = messages,
                    assistant = Assistant(chatModelId = model.id),
                ).collect { chunk ->
                    if (chunk is GenerationChunk.Notice) notices += chunk.notice
                }
            }
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals(1, provider.calls.get())
        assertTrue("POSSIBLE 413 不得盲目撤回工具结果", notices.isEmpty())
    }

    @Test
    fun `definite overflow evicts the largest tool result and retries without mutating history`() {
        val provider = ScriptedProvider { index, _, emit ->
            if (index == 0) throw definiteOverflowError()
            textStep("done after eviction").forEach { emit(it) }
        }
        val messages = listOf(
            UIMessage.user("inspect files"),
            messageWithTools(
                "small" to "S".repeat(2_000),
                "huge" to "H".repeat(24_000),
                "medium" to "M".repeat(8_000),
            ),
        )
        val notices = mutableListOf<GenerationNotice>()
        val finalSnapshots = mutableListOf<List<UIMessage>>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                model = model,
                messages = messages,
                assistant = Assistant(chatModelId = model.id),
                contextOverflowRecover = null,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Notice -> notices += chunk.notice
                    is GenerationChunk.Messages -> finalSnapshots += chunk.messages
                }
            }
        }
        assertEquals(2, provider.calls.get())
        val evictedNotice = notices.single() as GenerationNotice.ToolOutputEvictedOnOverflow
        assertEquals("huge", evictedNotice.toolCallId)
        assertEquals(24_000, evictedNotice.originalChars)
        assertTrue(evictedNotice.shrunkChars < 1_000)
        assertFalse(evictedNotice.isTerminalStop)

        // 重发请求中仅 huge 被替换为句柄桩，small/medium 原样保留
        val resentTools = provider.receivedMessages[1]
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .associateBy { it.toolCallId }
        val hugeSentText = resentTools.getValue("huge").output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(hugeSentText.contains(OVERFLOW_EVICTED_MARKER))
        assertTrue(hugeSentText.contains("/tool_outputs/overflow-huge.txt"))
        assertEquals(2_000, resentTools.getValue("small").output.filterIsInstance<UIMessagePart.Text>().single().text.length)
        assertEquals(8_000, resentTools.getValue("medium").output.filterIsInstance<UIMessagePart.Text>().single().text.length)

        // 全文无损落盘，且调用方持有的原始历史消息保持全文不动
        val savedFile = File(File(tempDir, FileFolders.TOOL_OUTPUTS), "overflow-huge.txt")
        assertTrue(savedFile.exists())
        assertEquals(24_000, savedFile.readText().length)
        val persistedHuge = finalSnapshots.last()
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .first { it.toolCallId == "huge" }
        assertEquals(24_000, persistedHuge.output.filterIsInstance<UIMessagePart.Text>().single().text.length)
    }

    @Test
    fun `complements reactive compaction by evicting largest tool result if retry still overflows`() {
        var compressCalls = 0
        val provider = ScriptedProvider { index, _, emit ->
            when (index) {
                0 -> throw definiteOverflowError() // triggers reactive compaction
                1 -> throw definiteOverflowError() // still overflows -> evicts largest tool result
                else -> textStep("succeeded on attempt 3").forEach { emit(it) }
            }
        }
        val messages = listOf(
            UIMessage.user("run heavy command"),
            messageWithTools("cmd1" to "L".repeat(16_000)),
        )
        val notices = mutableListOf<GenerationNotice>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                model = model,
                messages = messages,
                assistant = Assistant(chatModelId = model.id),
                contextOverflowRecover = {
                    compressCalls++
                    true
                },
            ).collect { chunk ->
                if (chunk is GenerationChunk.Notice) notices += chunk.notice
            }
        }
        assertEquals(1, compressCalls)
        assertEquals(3, provider.calls.get())
        assertEquals(1, notices.filterIsInstance<GenerationNotice.ToolOutputEvictedOnOverflow>().size)
    }

    @Test
    fun `evicts up to max attempts in descending size order then rethrows`() {
        val provider = ScriptedProvider { _, _, _ -> throw definiteOverflowError() }
        val messages = listOf(
            UIMessage.user("many outputs"),
            messageWithTools(
                "t1" to "1".repeat(4_000),
                "t2" to "2".repeat(12_000),
                "t3" to "3".repeat(8_000),
                "t4" to "4".repeat(6_000),
            ),
        )
        val notices = mutableListOf<GenerationNotice.ToolOutputEvictedOnOverflow>()
        val thrown = runCatching {
            runBlocking {
                handler(provider).generateText(
                    settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                    model = model,
                    messages = messages,
                    assistant = Assistant(chatModelId = model.id),
                ).collect { chunk ->
                    if (chunk is GenerationChunk.Notice && chunk.notice is GenerationNotice.ToolOutputEvictedOnOverflow) {
                        notices += chunk.notice
                    }
                }
            }
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals(MAX_OVERFLOW_TOOL_EVICTIONS, notices.size)
        // 按体积降序：t2(12K) -> t3(8K) -> t4(6K)；达上限 3 次后不再摘 t1
        assertEquals(listOf("t2", "t3", "t4"), notices.map { it.toolCallId })
        assertEquals(1 + MAX_OVERFLOW_TOOL_EVICTIONS, provider.calls.get())
    }

    @Test
    fun `preserves existing tool_outputs pointer instead of overwriting with preview`() {
        val existing = buildString {
            appendLine("[Tool output truncated: 50000 characters total]")
            appendLine("Full output saved to: /tool_outputs/orig-1.txt")
            appendLine("Use workspace_read_file(...)")
            append("P".repeat(4_000))
        }
        val messages = listOf(
            UIMessage.user("q"),
            messageWithTools("orig-1" to existing),
        )
        val candidate = findLargestEvictableToolResult(messages, emptySet())
        assertNotNull(candidate)
        assertEquals("/tool_outputs/orig-1.txt", candidate!!.existingFilePath)

        val (stub, record) = evictToolResultForOverflow(
            context = context,
            candidate = candidate,
            workspaceBound = true,
            hasShellAccess = false,
        )
        assertEquals("/tool_outputs/orig-1.txt", record.savedFilePath)
        assertTrue(stub.contains("Full output saved to: /tool_outputs/orig-1.txt"))
        assertFalse(File(File(tempDir, FileFolders.TOOL_OUTPUTS), "overflow-orig-1.txt").exists())
    }

    @Test
    fun `non-terminal eviction notice does not fail subagent completion evaluation`() {
        val outcome = evaluateCompletion(
            messages = listOf(UIMessage.assistant("completed answer")),
            stopNotice = GenerationNotice.ToolOutputEvictedOnOverflow(
                toolName = "workspace_shell",
                toolCallId = "c1",
                originalChars = 20_000,
                shrunkChars = 220,
                attempt = 1,
            ),
        )
        assertTrue("信息性撤回通知不应导致子代理判失败: $outcome", outcome is RunOutcome.Completed)
    }
}
