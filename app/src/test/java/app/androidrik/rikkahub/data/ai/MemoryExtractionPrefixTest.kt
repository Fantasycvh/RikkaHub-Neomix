package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryExtractionPrefixTest {
    @Test
    fun `new-turn prefix binds only after an assistant response succeeds`() {
        val cache = MemoryExtractionPrefixes { 0 }
        val model = Model()
        val user = UIMessage.user("new preference")
        val answer = UIMessage(role = app.androidrik.ai.core.MessageRole.ASSISTANT, modelId = model.id, parts = emptyList())
        val pending = MemoryExtractionPrefixes.Prefix(ProviderSetting.OpenAI(), TextGenerationParams(model), listOf(user), null, 0)
        cache.capture("failed", pending)
        assertNull(cache.takeEligible("failed", answer, setOf(user.id)))
        cache.capture("succeeded", pending)
        cache.bindReply("succeeded", answer.id)
        assertEquals(answer.id, cache.takeEligible("succeeded", answer, setOf(user.id, answer.id))?.replyId)
    }

    @Test
    fun `prefix requires the matching complete reply and all new evidence`() {
        val cache = MemoryExtractionPrefixes { 0 }
        val model = Model()
        val input = UIMessage.user("new preference")
        val reply = UIMessage(role = app.androidrik.ai.core.MessageRole.ASSISTANT, modelId = model.id, parts = emptyList())
        val prefix = MemoryExtractionPrefixes.Prefix(ProviderSetting.OpenAI(), TextGenerationParams(model), listOf(input), reply.id, 0)
        cache.capture("session", prefix)
        assertNull(cache.takeEligible("session", reply, setOf(Uuid.random())))
        cache.capture("session", prefix)
        assertNull(cache.takeEligible("session", reply.copy(finishIncomplete = true), setOf(input.id)))
        cache.capture("session", prefix)
        assertNull(cache.takeEligible("session", reply.copy(id = Uuid.random()), setOf(input.id)))
        cache.capture("session", prefix)
        assertEquals(prefix, cache.takeEligible("session", reply, setOf(input.id, reply.id)))
    }

    @Test
    fun `snapshots expire and are consumed once`() {
        var now = 0L
        val cache = MemoryExtractionPrefixes { now }
        val prefix = MemoryExtractionPrefixes.Prefix(ProviderSetting.OpenAI(), TextGenerationParams(Model()), listOf(UIMessage.user("hello")), Uuid.random(), now)
        cache.capture("one", prefix)
        assertEquals(prefix, cache.take("one"))
        assertNull(cache.take("one"))
        cache.capture("one", prefix)
        now = 240_000
        assertNull(cache.take("one"))
    }

    @Test
    fun `bounded snapshots preserve session isolation`() {
        val cache = MemoryExtractionPrefixes { 0 }
        val prefix = MemoryExtractionPrefixes.Prefix(ProviderSetting.OpenAI(), TextGenerationParams(Model()), emptyList(), Uuid.random(), 0)
        for (index in 0..4) cache.capture(index.toString(), prefix.copy(replyId = Uuid.random()))
        assertNull(cache.take("0"))
        assertNotEquals(cache.take("1")?.replyId, cache.take("2")?.replyId)
    }
}
