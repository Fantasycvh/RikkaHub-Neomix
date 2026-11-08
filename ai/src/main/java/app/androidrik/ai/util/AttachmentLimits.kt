package app.androidrik.ai.util

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException

class AttachmentLimitException(message: String) : IOException(message)

/** Limits apply to local bytes before base64 expansion. Remote URLs remain provider-managed. */
object AttachmentLimits {
    const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
    const val IMAGE_MAX_DIMENSION = 4096
    const val IMAGE_MAX_PIXELS = 8_000_000L

    fun requireSize(bytes: Long, limit: Long = MAX_ATTACHMENT_BYTES) {
        if (bytes > limit) throw AttachmentLimitException("Attachment exceeds ${limit / 1024 / 1024} MiB limit")
    }

    /** Counts actual bytes; SAF metadata may be absent or wrong. Never silently truncate. */
    fun bounded(input: InputStream, limit: Long = MAX_ATTACHMENT_BYTES): InputStream = object : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) requireSize(++count, limit) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, minOf(length.toLong(), limit - count + 1).toInt()).also {
                if (it > 0) { count += it; requireSize(count, limit) }
            }
        override fun skip(n: Long): Long {
            val buffer = ByteArray(8192)
            var skipped = 0L
            while (skipped < n) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }
        override fun markSupported() = false
        override fun mark(readlimit: Int) = Unit
        override fun reset(): Unit = throw IOException("Attachment stream does not support reset")
    }
}

/** Existing encoders may skip unreadable old attachments, but size failures must reach the user. */
fun <T> Result<T>.checkAttachmentLimit(): Result<T> = onFailure { if (it is AttachmentLimitException) throw it }
