package app.androidrik.rikkahub.data.ai

import android.content.ContextWrapper
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageChoice
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.terminalChunk
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

    private class ScriptedProvider(private val script: (Int) -> List<MessageChunk>) : Provider<ProviderSetting.OpenAI> {
        var calls = 0
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
        ): MessageChunk = script(calls++).first()
        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
        ): Flow<MessageChunk> = flow { script(calls++).forEach { emit(it) } }
    }

    private fun chunk(vararg parts: UIMessagePart, reason: String? = "stop") = MessageChunk(
        id = "test", model = "test", choices = listOf(UIMessageChoice(
            index = 0, delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
            message = null, finishReason = reason,
        )),
    )

    private fun run(
        provider: ScriptedProvider,
        stream: Boolean = true,
        input: List<UIMessage> = listOf(UIMessage.user("hello")),
        snapshots: MutableList<List<UIMessage>> = mutableListOf(),
        retry: RetrySetting = RetrySetting(enabled = false),
    ) = runBlocking {
        val context = ContextWrapper(null)
        val manager = ProviderManager(OkHttpClient(), context)
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
            val reasoning = UIMessagePart.Reasoning(reasoning = "").also {
                it.metadata = kotlinx.serialization.json.buildJsonObject {
                    put("data", kotlinx.serialization.json.JsonPrimitive("redacted-payload"))
                }
            }
            listOf(if (call == 0) chunk(reasoning) else chunk(UIMessagePart.Text("answer")), terminalChunk())
        }
        run(provider)
        assertEquals(2, provider.calls)
    }

    @Test fun `terminal only stream is not a successful reply`() {
        val provider = ScriptedProvider { listOf(terminalChunk()) }
        assertEmptyFailure { run(provider) }
        assertEquals(1, provider.calls)
    }

    @Test fun `empty assistant delta and whitespace fail`() {
        for (parts in listOf(emptyArray(), arrayOf<UIMessagePart>(UIMessagePart.Text("  \n")))) {
            val provider = ScriptedProvider { listOf(chunk(*parts), terminalChunk()) }
            assertEmptyFailure { run(provider) }
        }
    }

    @Test fun `old assistant content cannot hide empty continuation`() {
        val provider = ScriptedProvider { listOf(terminalChunk()) }
        assertEmptyFailure { run(provider, input = listOf(UIMessage.user("hello"), UIMessage.assistant("old output ".repeat(10)))) }
    }

    @Test fun `non streaming empty choices fail`() {
        val provider = ScriptedProvider { listOf(MessageChunk(id = "empty", model = "test", choices = emptyList())) }
        assertEmptyFailure { run(provider, stream = false) }
        assertEquals(1, provider.calls)
    }

    @Test fun `reasoning only preserves one visible answer recovery`() {
        val provider = ScriptedProvider { call ->
            listOf(if (call == 0) chunk(UIMessagePart.Reasoning(reasoning = "thinking"))
                else chunk(UIMessagePart.Text("answer")), terminalChunk())
        }
        val snapshots = mutableListOf<List<UIMessage>>()
        run(provider, snapshots = snapshots)
        assertEquals(2, provider.calls)
        assertTrue(snapshots.last().last().toText().contains("answer"))
    }

    @Test fun `audio output does not trigger visible text recovery`() {
        val provider = ScriptedProvider { listOf(chunk(UIMessagePart.Audio(url = "https://example.com/a.mp3")), terminalChunk()) }
        run(provider)
        assertEquals(1, provider.calls)
    }

    @Test fun `terminal metadata is not discarded`() {
        val provider = ScriptedProvider {
            listOf(chunk(UIMessagePart.Text("partial"), reason = null),
                chunk(reason = "max_output_tokens").copy(explicitEnd = true))
        }
        val snapshots = mutableListOf<List<UIMessage>>()
        run(provider, snapshots = snapshots)
        assertEquals(1, provider.calls)
        assertEquals("max_output_tokens", snapshots.last().last().finishReason)
        assertFalse(snapshots.last().last().finishIncomplete)
    }

    @Test fun `explicit safety refusal is not retried or recovered`() {
        val provider = ScriptedProvider { listOf(chunk(reason = "refusal"), terminalChunk()) }
        val snapshots = mutableListOf<List<UIMessage>>()
        run(provider, snapshots = snapshots)
        assertEquals(1, provider.calls)
        assertEquals("refusal", snapshots.last().last().finishReason)
    }
}
