package app.androidrik.rikkahub.data.ai

import android.content.ContextWrapper
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class EmptyGenerationResponseTest {
    private val model = Model(modelId = "test", displayName = "test")
    private val providerSetting = ProviderSetting.OpenAI(models = listOf(model), apiKey = "test")

    private class ScriptedProvider(
        private val nonStreamingResult: ((Int) -> TextGenerationResult)? = null,
        private val script: (Int) -> List<StreamChunk>,
    ) : Provider<ProviderSetting.OpenAI> {
        var calls = 0
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
        ): TextGenerationResult = nonStreamingResult?.invoke(calls++) ?: error("这些用例只走流式路径")
        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
        ): Flow<StreamChunk> = flow { script(calls++).forEach { emit(it) } }
    }

    // ---- StreamChunk 脚本夹具（旧 chunk()/terminalChunk() 的新等价物） ----

    /** 旧 `terminalChunk()` + `finishReason` 的合并等价物：唯一的显式结束事件。 */
    private fun finish(finishReason: String? = null) = StreamChunk.Finish(finishReason = finishReason)

    private fun textEvents(text: String, id: String = "text"): List<StreamChunk> = listOf(
        StreamChunk.TextStart(id),
        StreamChunk.TextDelta(id, text),
        StreamChunk.TextEnd(id),
    )

    private fun reasoningEvents(
        reasoning: String,
        metadata: kotlinx.serialization.json.JsonObject? = null,
        id: String = "reasoning",
    ): List<StreamChunk> = listOf(
        StreamChunk.ReasoningStart(id, metadata),
        StreamChunk.ReasoningDelta(id, reasoning, metadata),
        StreamChunk.ReasoningEnd(id),
    )

    /** 非流式：一条空助手消息（旧 `MessageChunk(choices = emptyList())` 的等价物）。 */
    private fun emptyTextResult() = TextGenerationResult(
        id = "test",
        model = "test",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        finishReason = null,
    )

    private fun run(
        provider: ScriptedProvider,
        stream: Boolean = true,
        input: List<UIMessage> = listOf(UIMessage.user("hello")),
        snapshots: MutableList<List<UIMessage>> = mutableListOf(),
        retry: RetrySetting = RetrySetting(enabled = false),
    ) = runBlocking {
        val context = ContextWrapper(null)
        // 账号类提供商不参与这些用例；新增的必填回调只在真的取账号凭据时抛错
        val manager = ProviderManager(OkHttpClient(), context, accountCredentials = { _, _ -> error("no account provider in tests") })
        manager.registerProvider("openai", provider)
        GenerationHandler(context, manager, JsonInstant, usageRecorder = noopUsageRecorder()).generateText(
            settings = Settings(
                providers = listOf(providerSetting), chatModelId = model.id,
                retrySetting = retry,
            ),
            model = model, messages = input,
            assistant = Assistant(chatModelId = model.id, streamOutput = stream), tools = emptyList(),
        ).collect { if (it is GenerationChunk.Messages) snapshots += it.messages }
    }

    private fun assertEmptyFailure(action: () -> Unit) {
        try {
            action()
            fail("empty response must fail")
        } catch (_: EmptyGenerationResponseException) { }
    }

    @Test fun `zero event stream fails without silently accepting user text`() {
        val provider = ScriptedProvider { emptyList() }
        val snapshots = mutableListOf<List<UIMessage>>()
        assertEmptyFailure { run(provider, snapshots = snapshots) }
        assertEquals(1, provider.calls)
        assertTrue(snapshots.none { it.last().role == MessageRole.USER && it.last().finishedAt != null })
    }

    @Test fun `empty stream follows the configured retry budget`() {
        val provider = ScriptedProvider { emptyList() }
        assertEmptyFailure {
            run(provider, retry = RetrySetting(enabled = true, delaysSeconds = listOf(0, 0)))
        }
        assertEquals(3, provider.calls)
    }

    @Test fun `redacted reasoning metadata is not mistaken for zero output`() {
        val provider = ScriptedProvider { call ->
            if (call == 0) {
                // 旧语义：空 reasoning + 非空 metadata 视为「有输出」；新事件模型里 metadata 挂在
                // ReasoningStart/Delta 上，同样必须被算作内容（不能误判成零输出去重试）。
                reasoningEvents(
                    reasoning = "",
                    metadata = kotlinx.serialization.json.buildJsonObject {
                        put("data", kotlinx.serialization.json.JsonPrimitive("redacted-payload"))
                    },
                ) + finish("stop")
            } else {
                textEvents("answer") + finish("stop")
            }
        }
        run(provider)
        assertEquals(2, provider.calls)
    }

    @Test fun `terminal only stream is not a successful reply`() {
        val provider = ScriptedProvider { listOf(finish(finishReason = null)) }
        assertEmptyFailure { run(provider) }
        assertEquals(1, provider.calls)
    }

    @Test fun `empty assistant delta and whitespace fail`() {
        for (chunks in listOf(listOf(finish("stop")), textEvents("  \n") + finish("stop"))) {
            val provider = ScriptedProvider { chunks }
            assertEmptyFailure { run(provider) }
        }
    }

    @Test fun `old assistant content cannot hide empty continuation`() {
        val provider = ScriptedProvider { listOf(finish(finishReason = null)) }
        assertEmptyFailure { run(provider, input = listOf(UIMessage.user("hello"), UIMessage.assistant("old output ".repeat(10)))) }
    }

    @Test fun `non streaming empty choices fail`() {
        val provider = ScriptedProvider(
            nonStreamingResult = { emptyTextResult() },
            script = { emptyList() },
        )
        assertEmptyFailure { run(provider, stream = false) }
        assertEquals(1, provider.calls)
    }

    @Test fun `reasoning only preserves one visible answer recovery`() {
        val provider = ScriptedProvider { call ->
            if (call == 0) reasoningEvents("thinking") + finish("stop")
            else textEvents("answer") + finish("stop")
        }
        val snapshots = mutableListOf<List<UIMessage>>()
        run(provider, snapshots = snapshots)
        assertEquals(2, provider.calls)
        assertTrue(snapshots.last().last().toText().contains("answer"))
    }

    @Test fun `image output does not trigger visible text recovery`() {
        // 旧用例验证「非文本的可见输出（Audio）不算零可见正文」。新事件模型没有 Audio 事件，
        // 改用等价的可见非文本输出 Image：它同样满足 hasVisibleOutput，因此不该触发补答。
        val provider = ScriptedProvider {
            listOf(
                StreamChunk.ImageStart(id = "img", mimeType = "image/png"),
                StreamChunk.ImageDelta(id = "img", data = "iVBORw0KGgoAAAANSUhEUg=="),
                StreamChunk.ImageEnd(id = "img"),
                finish("stop"),
            )
        }
        run(provider)
        assertEquals(1, provider.calls)
    }

    @Test fun `terminal metadata is not discarded`() {
        val provider = ScriptedProvider {
            textEvents("partial") + finish("max_output_tokens")
        }
        val snapshots = mutableListOf<List<UIMessage>>()
        run(provider, snapshots = snapshots)
        assertEquals(1, provider.calls)
        assertEquals("max_output_tokens", snapshots.last().last().finishReason)
        assertFalse(snapshots.last().last().finishIncomplete)
    }

    @Test fun `explicit safety refusal is not retried or recovered`() {
        val provider = ScriptedProvider { listOf(finish("refusal")) }
        val snapshots = mutableListOf<List<UIMessage>>()
        run(provider, snapshots = snapshots)
        assertEquals(1, provider.calls)
        assertEquals("refusal", snapshots.last().last().finishReason)
    }
}
