package app.androidrik.rikkahub.ui.pages.share.handler

import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedAttachmentsTest {
    @Test
    fun `a failed image does not change the following PDF into an image`() = runBlocking {
        val result = importSharedAttachments(
            listOf("broken", "pdf", "pdf", "image"),
            describe = { SharedFileInfo("$it.${if (it == "pdf") "pdf" else "png"}", if (it == "pdf") "application/pdf" else "image/png") },
            copy = { if (it == "broken") error("grant expired") else "file:///$it" },
        )
        assertEquals(listOf(SharedFileFailure("broken.png", false)), result.failures)
        assertEquals(UIMessagePart.Document(url = "file:///pdf", fileName = "pdf.pdf", mime = "application/pdf"), result.parts[0])
        assertEquals(UIMessagePart.Image("file:///image"), result.parts[1])
    }

    @Test
    fun `unsupported files are reported without copying`() = runBlocking {
        val result = importSharedAttachments(listOf("archive"), { SharedFileInfo("a.zip", "application/zip") }, { error("must not copy") })
        assertTrue(result.parts.isEmpty())
        assertEquals(listOf(SharedFileFailure("a.zip", true)), result.failures)
    }

    @Test
    fun `metadata read failure is visible and does not drop the next file`() = runBlocking {
        val result = importSharedAttachments(listOf("denied", "text"), {
            if (it == "denied") throw SecurityException()
            SharedFileInfo("note.md", "text/markdown")
        }, { "file:///$it" })
        assertEquals(1, result.failures.size)
        assertEquals(1, result.parts.size)
        assertTrue(result.parts.single() is UIMessagePart.Document)
    }

    @Test
    fun `cancellation aborts rather than being reported as a failed attachment`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                importSharedAttachments(listOf("one"), { SharedFileInfo("a.png", "image/png") }, { throw CancellationException() })
            }
        }
    }
}
