package app.androidrik.rikkahub.data.sync.lan

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.export.collectAttachmentUrls
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class LanConversationObjectTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `portable conversation keeps branch and referenced file across device roots`() = runBlocking {
        val sourceRoot = temp.newFolder("source")
        val destinationRoot = temp.newFolder("destination")
        val file = File(File(sourceRoot, "upload").apply { mkdir() }, "photo.png").apply {
            writeText("fixture bytes")
            check(setLastModified(1_700_000_000_000))
        }
        val first = MessageNode(messages = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image(url = "file://${file.absolutePath.replace(File.separatorChar, '/')}")))))
        val branch = MessageNode(parentId = first.id, messages = listOf(UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")))))
        val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, branch),
            activeLeafNodeId = branch.id, workspaceCwd = "/device-private-workspace")
        val objectFile = temp.newFile()
        val capture = captureLanConversation(original, sourceRoot, objectFile, JsonInstant)
        assertFalse(objectFile.readText().contains("device-private-workspace"))
        assertFalse(objectFile.readText().contains("photo.png"))
        val received = LanObjectStore(temp.newFolder("incoming"))
        val result = receiveLanObjects(listOf(capture.manifest), received,
            sourceSize = { capture.objectFiles.getValue(it).length() },
            readRange = { digest, start, size -> readLanFileRange(capture.objectFiles.getValue(digest), start, size) },
        )
        assertTrue(result.remainingObjects.isEmpty())
        val imported = materializeLanConversation(capture.manifest, received, destinationRoot, JsonInstant)
        assertEquals(original.id, imported.id)
        assertEquals(branch.id, imported.activeLeafNodeId)
        assertEquals(first.id, imported.messageNodes.last().parentId)
        val destination = File(imported.collectAttachmentUrls().single().removePrefix("file://"))
        assertTrue(destination.canonicalFile.toPath().startsWith(destinationRoot.canonicalFile.toPath()))
        assertEquals(file.readText(), destination.readText())
        assertEquals(file.lastModified(), destination.lastModified())
        assertEquals(capture.manifest.sha256,
            captureLanConversation(imported, destinationRoot, temp.newFile(), JsonInstant).manifest.sha256)
    }
}
