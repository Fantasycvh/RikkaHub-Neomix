package app.androidrik.rikkahub.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 辅助生成单飞注册表：同键 cancel-and-replace、跨会话隔离、结束自动注销。 */
class AuxJobRegistryTest {

    private val registry = AuxJobRegistry()
    private val conversation = Uuid.random()
    private val otherConversation = Uuid.random()

    @Test
    fun `same key replaces and cancels previous job`() {
        val first = Job()
        val second = Job()

        assertFalse(registry.replace(conversation, AuxJobRegistry.Kind.TITLE, first))
        assertTrue(registry.isActive(conversation, AuxJobRegistry.Kind.TITLE))

        assertTrue(registry.replace(conversation, AuxJobRegistry.Kind.TITLE, second))
        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertTrue(registry.isActive(conversation, AuxJobRegistry.Kind.TITLE))
    }

    @Test
    fun `different conversations and kinds are isolated`() {
        val title = Job()
        val suggestion = Job()
        val other = Job()

        registry.replace(conversation, AuxJobRegistry.Kind.TITLE, title)
        registry.replace(conversation, AuxJobRegistry.Kind.SUGGESTION, suggestion)
        registry.replace(otherConversation, AuxJobRegistry.Kind.TITLE, other)

        assertFalse(title.isCancelled)
        assertFalse(suggestion.isCancelled)
        assertFalse(other.isCancelled)
        assertTrue(registry.isActive(conversation, AuxJobRegistry.Kind.TITLE))
        assertTrue(registry.isActive(conversation, AuxJobRegistry.Kind.SUGGESTION))
    }

    @Test
    fun `completed job unregisters itself`() {
        val job = Job()
        registry.replace(conversation, AuxJobRegistry.Kind.SUGGESTION, job)

        job.complete()
        assertFalse(registry.isActive(conversation, AuxJobRegistry.Kind.SUGGESTION))
    }

    @Test
    fun `cancelAll only cancels the given conversation`() {
        val title = Job()
        val suggestion = Job()
        val other = Job()
        registry.replace(conversation, AuxJobRegistry.Kind.TITLE, title)
        registry.replace(conversation, AuxJobRegistry.Kind.SUGGESTION, suggestion)
        registry.replace(otherConversation, AuxJobRegistry.Kind.TITLE, other)

        registry.cancelAll(conversation)

        assertTrue(title.isCancelled)
        assertTrue(suggestion.isCancelled)
        assertFalse(other.isCancelled)
        assertTrue(registry.isActive(otherConversation, AuxJobRegistry.Kind.TITLE))
    }
}
