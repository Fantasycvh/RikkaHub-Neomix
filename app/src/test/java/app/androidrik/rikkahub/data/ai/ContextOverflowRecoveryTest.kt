package app.androidrik.rikkahub.data.ai

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
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * 输入超窗反应式兜底（design-context-boundaries-and-overflow-recovery §3.1）：
 * provider 报「上下文超限」+ 零增量 ⇒ 压缩恢复一次、重发同一步；非零增量 / 二次超窗 /
 * 恢复无收益 ⇒ 原错误透传。判据对照 SDK `test_agent_context_window_condensation.py` 的触发链。
 */
class ContextOverflowRecoveryTest {

    // ---- 溢出样例：OpenAI 官方 400 措辞（命中 "please reduce the length of"）----

    private fun overflowError() = Exception(
        "Failed to get response: 400 This model's maximum context length is 8192 tokens. " +
            "However, your messages resulted in 9000 tokens. Please reduce the length of the messages."
    )

    /** 脚本完全接管发射（suspend）：可「先发部分增量再抛错」，emit 直通 flow 构建器作用域 */
    private class ScriptedProvider(
        private val script: suspend (callIndex: Int, messages: List<UIMessage>, emit: suspend (StreamChunk) -> Unit) -> Unit,
    ) : Provider<ProviderSetting.OpenAI> {
        val calls = AtomicInteger(0)

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = error("这些用例只走流式路径")

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = flow {
            script(calls.getAndIncrement(), messages) { chunk -> emit(chunk) }
        }
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
        // ContextWrapper(null)：循环只把它交给 Log / filesDir，单测里两者都不该被真正用到
        val context = ContextWrapper(null)
        val manager = ProviderManager(OkHttpClient(), context, accountCredentials = { _, _ -> error("no account provider in tests") })
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

    /**
     * 跑一轮生成。`recoveriesSink` 由调用方持有：异常路径下 collect 中断，但 sink 里已记录的
     * 恢复请求仍可断言。异常原样抛给调用方。
     */
    private fun runGeneration(
        provider: ScriptedProvider,
        recover: (suspend (ContextOverflowRecoveryRequest) -> Boolean)?,
        recoveriesSink: MutableList<ContextOverflowRecoveryRequest> = mutableListOf(),
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
    ): List<UIMessage> {
        val snapshots = mutableListOf<List<UIMessage>>()
        runBlocking {
            handler(provider).generateText(
                settings = Settings(providers = listOf(providerSetting), chatModelId = model.id),
                model = model,
                messages = messages,
                assistant = Assistant(chatModelId = model.id),
                contextOverflowRecover = recover?.let { callback ->
                    { request ->
                        recoveriesSink += request
                        callback(request)
                    }
                },
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) snapshots += chunk.messages
            }
        }
        assertTrue("生成过程应当至少回写过一次消息", snapshots.isNotEmpty())
        return snapshots.last()
    }

    @Test
    fun `zero-increment overflow recovers once and retries the same step`() {
        // call 0：请求被 400 挡回（无任何增量 chunk）；恢复成功后 call 1 用同一步重试成功
        val recoveries = mutableListOf<ContextOverflowRecoveryRequest>()
        val provider = ScriptedProvider { index, _, emit ->
            if (index == 0) throw overflowError()
            textStep("recovered answer").forEach { emit(it) }
        }
        val final = runGeneration(provider, recover = { _ -> true }, recoveriesSink = recoveries)
        assertEquals(2, provider.calls.get())
        assertEquals(1, recoveries.size)
        // 请求快照 = 请求前基线（零增量 ⇒ 无本轮增量混入）；窗口口径为模型有效窗口。
        // 按内容比较（快照与 fixture 是不同实例，id/createdAt 必异）
        assertEquals(listOf("hi"), recoveries.single().messages.map { it.toText() })
        assertEquals(listOf(MessageRole.USER), recoveries.single().messages.map { it.role })
        assertTrue(recoveries.single().windowTokens > 0)
        assertTrue(
            final.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "recovered answer" }
        )
    }

    @Test
    fun `second overflow within the same generation is not recovered`() {
        // 恢复后再撞窗 ⇒ 保护区自身装不下，再压白烧摘要 token——原错误透传（一次性旗标）
        val recoveries = mutableListOf<ContextOverflowRecoveryRequest>()
        val provider = ScriptedProvider { _, _, _ -> throw overflowError() }
        val thrown = runCatching {
            runGeneration(provider, recover = { _ -> true }, recoveriesSink = recoveries)
        }.exceptionOrNull()
        assertTrue("expected the original overflow error to propagate", thrown != null)
        assertEquals(2, provider.calls.get())
        assertEquals(1, recoveries.size)
    }

    @Test
    fun `recovery returning false rethrows the original error`() {
        val recoveries = mutableListOf<ContextOverflowRecoveryRequest>()
        val provider = ScriptedProvider { index, _, emit ->
            if (index == 0) throw overflowError()
            textStep("unreachable").forEach { emit(it) }
        }
        val thrown = runCatching {
            runGeneration(provider, recover = { _ -> false }, recoveriesSink = recoveries)
        }.exceptionOrNull()
        assertTrue(thrown?.message?.contains("Please reduce the length of") == true)
        assertEquals(1, provider.calls.get())
        assertEquals(1, recoveries.size)
    }

    @Test
    fun `non overflow 400 does not trigger recovery`() {
        val recoveries = mutableListOf<ContextOverflowRecoveryRequest>()
        val provider = ScriptedProvider { _, _, _ ->
            throw Exception("Failed to get response: 400 invalid_request_error: unknown field")
        }
        val thrown = runCatching {
            runGeneration(provider, recover = { _ -> true }, recoveriesSink = recoveries)
        }.exceptionOrNull()
        assertTrue(thrown != null)
        assertEquals(1, provider.calls.get())
        assertEquals(0, recoveries.size)
    }

    @Test
    fun `partial delta before overflow suppresses recovery`() {
        // 有过部分输出（即便被回滚）⇒ 非零增量，保守不恢复
        val recoveries = mutableListOf<ContextOverflowRecoveryRequest>()
        val provider = ScriptedProvider { _, _, emit ->
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "partial"))
            throw overflowError()
        }
        val thrown = runCatching {
            runGeneration(provider, recover = { _ -> true }, recoveriesSink = recoveries)
        }.exceptionOrNull()
        assertTrue(thrown != null)
        assertEquals(1, provider.calls.get())
        assertEquals(0, recoveries.size)
    }

    @Test
    fun `null recover callback keeps the legacy failure path`() {
        val provider = ScriptedProvider { _, _, _ -> throw overflowError() }
        val thrown = runCatching {
            runGeneration(provider, recover = null)
        }.exceptionOrNull()
        assertTrue(thrown?.message?.contains("Please reduce the length of") == true)
        assertEquals(1, provider.calls.get())
    }

    // ---- 保护区起点 ----

    @Test
    fun `protected start is the last real user turn`() {
        val notice = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Notice(eventId = "n1", text = "workspace result")),
        )
        val checkpoint = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("imported summary")),
            isContextCheckpoint = true,
        )
        val messages = listOf(
            UIMessage.user("q1"),
            UIMessage.assistant("a1"),
            notice,
            checkpoint,
            UIMessage.user("q2"),
            UIMessage.assistant("a2 with pending tool"),
        )
        assertEquals(4, contextOverflowProtectedStart(messages))
        assertNull(contextOverflowProtectedStart(listOf(UIMessage.assistant("no user at all"))))
    }
}
