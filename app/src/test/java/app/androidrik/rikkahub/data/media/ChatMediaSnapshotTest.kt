package app.androidrik.rikkahub.data.media

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.rikkahub.data.ai.tools.imageToolMediaResult
import app.androidrik.rikkahub.data.ai.tools.parseGeneratedImagePaths
import app.androidrik.rikkahub.data.export.collectAttachmentUrls
import app.androidrik.rikkahub.data.export.rewriteAttachmentUrls
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ChatMediaSnapshotTest {
    @Test
    fun `pending chat snapshots materialize final artifacts and preserve export references`() = runBlocking {
        val pending = MediaCreationRecord(sessionId = Uuid.random(), providerId = Uuid.random(), providerName = "fixture",
            modelId = "image", kind = MediaKind.IMAGE, prompt = "draw")
        val tool = UIMessagePart.Tool("call", "generate_image", "{}", listOf(UIMessagePart.Text(imageToolMediaResult(pending) { it })))
        val node = MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node), updateAt = Instant.EPOCH)
        val completed = pending.copy(status = MediaCreationStatus.SUCCEEDED,
            updateAt = Instant.parse("2026-11-28T00:00:00Z"), outputs = listOf(MediaCreationOutput("media_creation/output.png", "image/png")))
        val galleryPath = "/data/user/0/fixture/files/images/result.png"
        val snapshot = materializeChatMediaSnapshot(conversation, { completed }) { _, _, _ -> galleryPath }
        assertEquals(completed.updateAt, snapshot.updateAt)
        assertEquals(listOf("file://$galleryPath"), snapshot.collectAttachmentUrls())
        val portable = snapshot.rewriteAttachmentUrls(mapOf("file://$galleryPath" to "file:///target/files/images/result.png"))
        val rewritten = portable.messageNodes.single().messages.single().parts.single() as UIMessagePart.Tool
        val content = JsonInstant.parseToJsonElement((rewritten.output.single() as UIMessagePart.Text).text)
        assertEquals(listOf("file:///target/files/images/result.png"), parseGeneratedImagePaths(content))
        assertEquals(listOf("file:///target/files/images/result.png"), portable.collectAttachmentUrls())
        assertTrue(rewritten.output.all { it is UIMessagePart.Text })
    }

    @Test
    fun `a remote snapshot remains usable when its independent media record is absent`() = runBlocking {
        val tool = UIMessagePart.Tool("call", "generate_image", "{}", listOf(UIMessagePart.Text(
            """{"ok":true,"record_id":"${Uuid.random()}","images":[{"path":"file:///target/files/images/result.png"}]}""")))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))))))
        assertEquals(conversation, materializeChatMediaSnapshot(conversation, { null }) { _, _, _ -> error("no local record") })
    }
}
