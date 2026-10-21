package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.limitContext
import app.androidrik.rikkahub.data.ai.buildSendWindowMessages
import app.androidrik.rikkahub.data.ai.prompts.buildCompactionCheckpointText
import app.androidrik.rikkahub.data.repository.repairLegacyCheckpointCandidates
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class UpstreamCheckpointCompatTest {
    private fun message(text: String, checkpoint: Boolean = false) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        isContextCheckpoint = checkpoint,
    )

    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { MessageNode.of(it) },
    )

    private fun nativeAt(conversation: Conversation, index: Int, fingerprint: String? = null) =
        CompressionSummary(
            conversationId = conversation.id,
            content = "native summary",
            messageCount = index + 1,
            boundaryNodeId = conversation.messageNodes[index].id.toString(),
            areaFingerprint = fingerprint,
        )

    @Test
    fun `persisted flag defaults false and true round trips`() {
        val ordinary = message("ordinary")
        val encoded = JsonInstant.encodeToString(ordinary)
        assertFalse(JsonInstant.decodeFromString<UIMessage>(encoded).isContextCheckpoint)
        val checkpoint = message("checkpoint", checkpoint = true)
        assertEquals(checkpoint, JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(checkpoint)))
        assertTrue(JsonInstant.encodeToString(checkpoint).contains("\"isContextCheckpoint\":true"))
    }

    @Test
    fun `head checkpoint is retained exactly without synthetic wrapper`() {
        val checkpoint = message("  original\n<compressed-summary>verbatim</compressed-summary>\n", true)
            .copy(translation = "original translation", finishReason = "stop")
        val tail = message("tail")
        val conversation = conversation(checkpoint, tail)
        val window = conversation.compressionWindow()!!
        assertEquals(0, window.boundaryIndex)
        assertSame(checkpoint, window.checkpointMessage)
        assertFalse(window.checkpointMessage.isSynthetic)
        assertEquals(listOf(checkpoint, tail), conversation.requestWindowMessages())
        assertSame(checkpoint, conversation.messageNodes.first().currentMessage)
        assertTrue(conversation.compressionSummaries.isEmpty())
    }

    @Test
    fun `last selected checkpoint is chosen by node position not timestamps`() {
        val first = message("first", true).copy(createdAt = LocalDateTime(2030, 1, 1, 0, 0))
        val last = message("last", true).copy(createdAt = LocalDateTime(2000, 1, 1, 0, 0))
        val tail = message("tail")
        val conversation = conversation(message("old"), first, message("middle"), last, tail)
        assertEquals(3, conversation.compressionWindow()!!.boundaryIndex)
        assertEquals(listOf(last, tail), conversation.requestWindowMessages())
    }

    @Test
    fun `mixed alternatives only activate selected flagged candidate and keep all candidates`() {
        val earlier = message("earlier", true)
        val ordinary = message("ordinary")
        val candidate = message("candidate", true)
        val tail = message("tail")
        val base = conversation(earlier, ordinary, tail)
        val mixed = base.messageNodes[1].copy(messages = listOf(ordinary, candidate), selectIndex = 0)
        val conversation = base.copy(messageNodes = listOf(base.messageNodes[0], mixed, base.messageNodes[2]))
        assertEquals(listOf(earlier, ordinary, tail), conversation.requestWindowMessages())
        val selected = conversation.copy(messageNodes = conversation.messageNodes.mapIndexed { index, node ->
            if (index == 1) node.copy(selectIndex = 1) else node
        })
        assertEquals(listOf(candidate, tail), selected.requestWindowMessages())
        assertEquals(listOf(ordinary, candidate), selected.messageNodes[1].messages)
    }

    @Test
    fun `deleting last checkpoint falls back to earlier flag and deleting all returns full history`() {
        val old = message("old")
        val first = message("first", true)
        val last = message("last", true)
        val tail = message("tail")
        val base = conversation(old, first, last, tail)
        val oneDeleted = base.copy(messageNodes = base.messageNodes.filterIndexed { index, _ -> index != 2 })
        assertEquals(listOf(first, tail), oneDeleted.requestWindowMessages())
        val allDeleted = oneDeleted.copy(messageNodes = oneDeleted.messageNodes.filterIndexed { index, _ -> index != 1 })
        assertNull(allDeleted.compressionWindow())
        assertEquals(listOf(old, tail), allDeleted.requestWindowMessages())
    }

    @Test
    fun `live append stays after original checkpoint including tool loop messages`() {
        val checkpoint = message("checkpoint", true)
        val base = conversation(message("old"), checkpoint, message("tail"))
        val append = listOf(message("live tool call"), message("live tool result"))
        assertEquals(listOf(checkpoint, base.currentMessages.last()) + append,
            base.compressionWindow()!!.applyTo(base.currentMessages + append))
    }

    @Test
    fun `context limit keeps original checkpoint and limits suffix only`() {
        val checkpoint = message("unchanged", true)
        val suffix = (1..8).map { message("tail $it") }
        val full = listOf(message("excluded"), checkpoint) + suffix
        assertEquals(listOf(checkpoint) + suffix.limitContext(2), full.limitContext(2))
        assertEquals(listOf(checkpoint) + suffix, full.limitContext(0))
        val conversation = conversation(*full.toTypedArray())
        val assembled = buildSendWindowMessages(full, conversation.compressionWindow(), 2)
        assertEquals(listOf(checkpoint) + suffix.limitContext(2), assembled)
        assertSame(checkpoint, assembled.first())
        // Preview/callers without a precomputed window must use the same checkpoint semantics.
        assertEquals(assembled, buildSendWindowMessages(full, null, 2))
    }

    @Test
    fun `newer native boundary supersedes imported checkpoint but older or equal does not`() {
        val checkpoint = message("imported", true)
        val base = conversation(message("old"), checkpoint, message("new"), message("tail"))
        for (index in listOf(0, 1)) {
            val older = base.copy(compressionSummaries = listOf(nativeAt(base, index)))
            assertSame(checkpoint, older.compressionWindow()!!.checkpointMessage)
        }
        val newer = base.copy(compressionSummaries = listOf(nativeAt(base, 2)))
        assertTrue(newer.compressionWindow()!!.checkpointMessage.isSynthetic)
        assertEquals(listOf(base.currentMessages.last()), newer.requestWindowMessages().drop(1))
        val invalid = base.copy(compressionSummaries = listOf(nativeAt(base, 2, "stale fingerprint")))
        assertSame(checkpoint, invalid.compressionWindow()!!.checkpointMessage)
    }

    @Test
    fun `loader preserves flagged known preamble and mixed candidates exactly`() {
        val text = buildCompactionCheckpointText("  exact original body\n")
        val checkpoint = message(text, true).copy(role = MessageRole.USER)
        val ordinary = message("other candidate")
        val node = MessageNode(messages = listOf(ordinary, checkpoint), selectIndex = 1)
        assertEquals(listOf(node), listOf(node).repairLegacyCheckpointCandidates())
        // Even corroborating duplicate IDs must not rewrite authoritative imported alternatives.
        val duplicate = MessageNode.of(ordinary)
        val repaired = listOf(node, duplicate).repairLegacyCheckpointCandidates()
        assertEquals(node, repaired.first())
        assertEquals(text, repaired.first().currentMessage.toText())
    }

    @Test
    fun `loader keeps ordinary known prefix collision without structural corruption evidence`() {
        val ordinary = UIMessage.user(buildCompactionCheckpointText("a user quoting a checkpoint"))
        val node = MessageNode.of(ordinary)
        assertFalse(ordinary.isContextCheckpoint)
        assertEquals(listOf(node), listOf(node).repairLegacyCheckpointCandidates())
        assertNull(conversation(ordinary).compressionWindow())
    }

    @Test
    fun `loader still repairs corroborated native synthetic ghosts`() {
        val original = message("original")
        val ghost = UIMessage.user(buildCompactionCheckpointText("legacy ghost"))
        val copied = message("copied suffix")
        val corrupted = MessageNode(messages = listOf(original, ghost, copied), selectIndex = 1)
        val suffixNode = MessageNode.of(copied)
        val repaired = listOf(corrupted, suffixNode).repairLegacyCheckpointCandidates()
        assertEquals(listOf(original), repaired.first().messages)
        assertEquals(0, repaired.first().selectIndex)
        assertEquals(suffixNode, repaired.last())
    }
}
