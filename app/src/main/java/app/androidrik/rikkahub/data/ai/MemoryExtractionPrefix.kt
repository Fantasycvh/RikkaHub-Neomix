package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Short-lived, bounded request snapshots. Never persisted or shared between conversations. */
internal class MemoryExtractionPrefixes(private val now: () -> Long = System::currentTimeMillis) {
    data class Prefix(
        val provider: ProviderSetting.OpenAI,
        val params: TextGenerationParams,
        val messages: List<UIMessage>,
        val replyId: Uuid?,
        val createdAt: Long,
    )

    private val entries = linkedMapOf<String, Prefix>()

    @Synchronized
    fun capture(sessionId: String, prefix: Prefix) {
        entries.entries.removeAll { now() - it.value.createdAt >= 240_000L }
        entries.remove(sessionId)
        entries[sessionId] = prefix
        while (entries.size > 4) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun bindReply(sessionId: String, replyId: Uuid) {
        entries[sessionId]?.let { entries[sessionId] = it.copy(replyId = replyId) }
    }

    fun takeEligible(sessionId: String, answer: UIMessage, evidenceIds: Set<Uuid>): Prefix? {
        val prefix = take(sessionId) ?: return null
        if (answer.id != prefix.replyId || answer.finishIncomplete || answer.modelId != prefix.params.model.id) return null
        if (answer.getTools().any { !it.isExecuted }) return null
        val coveredIds = prefix.messages.mapTo(hashSetOf()) { it.id } + answer.id
        return prefix.takeIf { coveredIds.containsAll(evidenceIds) }
    }

    @Synchronized
    fun take(sessionId: String): Prefix? {
        val prefix = entries.remove(sessionId) ?: return null
        return prefix.takeIf { now() - it.createdAt in 0 until 240_000L }
    }
}
