package app.androidrik.ai.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AttachmentLimitsTest {
    @Test fun `stream rejects actual bytes past limit including unknown length sources`() {
        val stream = AttachmentLimits.bounded(ByteArrayInputStream(ByteArray(11)), 10)
        val buffer = ByteArray(8)
        assertEquals(8, stream.read(buffer))
        assertThrows(AttachmentLimitException::class.java) { stream.read(buffer) }
    }

    @Test fun `exact limit and zero byte reads preserve stream semantics`() {
        val stream = AttachmentLimits.bounded(ByteArrayInputStream(ByteArray(10)), 10)
        assertEquals(0, stream.read(ByteArray(0)))
        assertEquals(10, stream.readBytes().size)
    }

    @Test fun `single byte reads cannot bypass limit`() {
        val stream = AttachmentLimits.bounded(ByteArrayInputStream(byteArrayOf(1, 2)), 1)
        assertEquals(1, stream.read())
        assertThrows(AttachmentLimitException::class.java) { stream.read() }
    }

    @Test fun `base64 limit accounts for padding and rejects oversize without decoding`() {
        requireImagePayloadSize("a".repeat((AttachmentLimits.MAX_IMAGE_BYTES * 4 / 3).toInt()))
        assertThrows(AttachmentLimitException::class.java) {
            requireImagePayloadSize("a".repeat((AttachmentLimits.MAX_IMAGE_BYTES * 4 / 3 + 4).toInt()))
        }
    }

    @Test fun `provider cannot silently discard a size rejection`() {
        assertThrows(AttachmentLimitException::class.java) {
            Result.failure<String>(AttachmentLimitException("too large")).checkAttachmentLimit().getOrNull()
        }
        assertNull(Result.failure<String>(IllegalArgumentException("old missing file")).checkAttachmentLimit().getOrNull())
    }
}
