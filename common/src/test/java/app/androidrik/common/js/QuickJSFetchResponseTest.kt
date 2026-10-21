package app.androidrik.common.js

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJSFetchResponseTest {
    private fun shortReads(bytes: ByteArray, chunkSize: Int): InputStream =
        object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, chunkSize))
        }

    @Test fun `reads complete response across short network reads`() {
        val content = "0123456789".repeat(6000)
        assertEquals(content, readFetchResponse(shortReads(content.toByteArray(), 127), Charsets.UTF_8))
    }

    @Test fun `decodes multibyte characters after collecting all chunks`() {
        val content = "中文🙂".repeat(4000)
        assertEquals(content, readFetchResponse(shortReads(content.toByteArray(), 1), Charsets.UTF_8))
    }

    @Test fun `empty response stays empty`() {
        assertEquals("", readFetchResponse(ByteArrayInputStream(byteArrayOf()), Charsets.UTF_8))
    }

    @Test fun `exact byte limit does not report truncation`() {
        assertEquals("abcd", readFetchResponse(shortReads("abcd".toByteArray(), 2), Charsets.UTF_8, 4))
    }

    @Test fun `over limit response gets explicit truncation marker`() {
        assertEquals("abcd\n[TRUNCATED]", readFetchResponse(shortReads("abcdef".toByteArray(), 2), Charsets.UTF_8, 4))
    }

    @Test fun `unbounded source consumes only byte limit plus probe`() {
        var bytesRead = 0
        val input = object : InputStream() {
            override fun read(): Int { bytesRead++; return 'x'.code }
        }
        assertEquals("x".repeat(42) + "\n[TRUNCATED]", readFetchResponse(input, Charsets.UTF_8, 42))
        assertEquals(43, bytesRead)
    }

    @Test fun `zero length short read does not spin`() {
        val input = object : ByteArrayInputStream("data".toByteArray()) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(buffer, offset, length)
            }
        }
        assertEquals("data", readFetchResponse(input, Charsets.UTF_8))
    }

    @Test fun `response charset is respected`() {
        val charset = Charsets.ISO_8859_1
        val content = "café"
        assertEquals(content, readFetchResponse(shortReads(content.toByteArray(charset), 1), charset))
    }

    @Test fun `read failures propagate instead of returning partial success`() {
        val input = object : InputStream() {
            var count = 0
            override fun read(): Int {
                if (count++ < 3) return 'a'.code
                throw IOException("read failed")
            }
        }
        try {
            readFetchResponse(input, Charsets.UTF_8)
            throw AssertionError("expected read failure")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("read failed"))
        }
    }
}
