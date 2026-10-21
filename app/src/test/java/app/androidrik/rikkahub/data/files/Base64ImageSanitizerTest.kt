package app.androidrik.rikkahub.data.files

import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1-6 回归：base64 图片在**落库前**的清洗逻辑（`ConversationRepository` 的
 * `require(无 data: 图片)` 曾让整轮回复写不进库）。
 *
 * 被测的是抽出来的纯逻辑 [sanitizeBase64ImageParts]（[FilesManager] 需要 Android Context，
 * 本仓无 Robolectric，故把「超限判定 / 占位替换 / Tool.output 递归 / 取消透传」独立出来）。
 */
class Base64ImageSanitizerTest {

    private fun dataUrl(payload: String, mime: String = "image/png"): String = "data:$mime;base64,$payload"

    private fun text(part: UIMessagePart): String = (part as UIMessagePart.Text).text

    @Test
    fun `small data image is converted to the local file url`() = runBlocking {
        var convertedPayload: String? = null
        val parts = sanitizeBase64ImageParts(
            parts = listOf(UIMessagePart.Image(url = dataUrl("QUJD"))),
            convert = { payload ->
                convertedPayload = payload
                "file:///data/user/0/app/files/upload/a.png"
            },
        )

        assertEquals("QUJD", convertedPayload)
        val image = parts.single() as UIMessagePart.Image
        assertEquals("file:///data/user/0/app/files/upload/a.png", image.url)
    }

    @Test
    fun `oversized payload is omitted by url length without invoking the converter`() = runBlocking {
        // maxPayloadChars 让测试用几十个字符就能覆盖「先判长度、再 substring」的分支；
        // 若实现先 substring 再判长度，这里虽测不出内存复制，但能测出「超限时不该尝试转换」。
        var converterCalled = false
        var omitReason: Base64ImageOmitReason? = null
        val parts = sanitizeBase64ImageParts(
            parts = listOf(UIMessagePart.Image(url = dataUrl("A".repeat(64)))),
            convert = {
                converterCalled = true
                "file:///should-not-happen.png"
            },
            onOmit = { omitReason = it },
            maxPayloadChars = 10,
        )

        assertFalse("超限时不能先解码/落盘", converterCalled)
        assertEquals(Base64ImageOmitReason.TooLarge, omitReason)
        assertEquals(Base64ImageOmitReason.TooLarge.placeholder, text(parts.single()))
    }

    @Test
    fun `payload exactly at the limit is still converted`() = runBlocking {
        var converterCalled = false
        val parts = sanitizeBase64ImageParts(
            parts = listOf(UIMessagePart.Image(url = dataUrl("QUJD"))),
            convert = {
                converterCalled = true
                "file:///ok.png"
            },
            maxPayloadChars = 4,
        )

        assertTrue(converterCalled)
        assertEquals("file:///ok.png", (parts.single() as UIMessagePart.Image).url)
    }

    @Test
    fun `converter returning null becomes the visible placeholder`() = runBlocking {
        var omitReason: Base64ImageOmitReason? = null
        val parts = sanitizeBase64ImageParts(
            parts = listOf(UIMessagePart.Image(url = dataUrl("QUJD"))),
            convert = { null },
            onOmit = { omitReason = it },
        )

        assertEquals(Base64ImageOmitReason.ConversionFailed, omitReason)
        assertEquals(Base64ImageOmitReason.ConversionFailed.placeholder, text(parts.single()))
    }

    @Test
    fun `converter exception degrades to the visible placeholder instead of losing the message`() = runBlocking {
        val parts = sanitizeBase64ImageParts(
            parts = listOf(UIMessagePart.Image(url = dataUrl("QUJD"))),
            convert = { error("decode failed") },
        )

        assertEquals(Base64ImageOmitReason.ConversionFailed.placeholder, text(parts.single()))
    }

    @Test
    fun `cancellation is rethrown instead of being swallowed as a placeholder`() = runBlocking {
        try {
            sanitizeBase64ImageParts(
                parts = listOf(UIMessagePart.Image(url = dataUrl("QUJD"))),
                convert = { throw CancellationException("cancelled by caller") },
            )
            fail("CancellationException 必须原样上抛（不能被吞成占位）")
        } catch (e: CancellationException) {
            assertEquals("cancelled by caller", e.message)
        }
    }

    @Test
    fun `unsupported data uris are replaced with the placeholder`() = runBlocking {
        val cases = listOf(
            // 非 base64 的 data URI（URL-encoded）
            "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg'/>",
            // Image part 里出现非图片 data URI：同样会触发 require(无 base64)
            "data:application/pdf;base64,QUJD",
        )
        cases.forEach { url ->
            val parts = sanitizeBase64ImageParts(
                parts = listOf(UIMessagePart.Image(url = url)),
                convert = { fail("不受支持的 data URI 不该调用 converter"); null },
            )
            assertEquals(
                "url=$url",
                Base64ImageOmitReason.Unsupported.placeholder,
                text(parts.single()),
            )
        }
    }

    @Test
    fun `non data image urls are left untouched`() = runBlocking {
        val url = "https://example.com/a.png"
        var converterCalled = false
        val parts = sanitizeBase64ImageParts(
            parts = listOf(
                UIMessagePart.Image(url = url),
                UIMessagePart.Image(url = "file:///data/user/0/app/files/upload/b.png"),
            ),
            convert = {
                converterCalled = true
                null
            },
        )

        assertFalse(converterCalled)
        assertEquals(url, (parts[0] as UIMessagePart.Image).url)
        assertEquals("file:///data/user/0/app/files/upload/b.png", (parts[1] as UIMessagePart.Image).url)
    }

    @Test
    fun `nested tool output images are sanitized recursively`() = runBlocking {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-outer",
            toolName = "read_image",
            input = "{\"path\":\"a.png\"}",
            output = listOf(
                UIMessagePart.Image(url = dataUrl("QUJD")),
                UIMessagePart.Text(text = "ok"),
                UIMessagePart.Tool(
                    toolCallId = "call-inner",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(UIMessagePart.Image(url = dataUrl("QUJD", "image/jpeg"))),
                ),
            ),
        )

        val parts = sanitizeBase64ImageParts(
            parts = listOf(tool),
            convert = { "file:///data/user/0/app/files/upload/converted.png" },
        )

        val outer = parts.single() as UIMessagePart.Tool
        assertEquals("call-outer", outer.toolCallId)
        assertEquals("read_image", outer.toolName)
        assertEquals(
            "file:///data/user/0/app/files/upload/converted.png",
            (outer.output[0] as UIMessagePart.Image).url,
        )
        assertEquals("ok", (outer.output[1] as UIMessagePart.Text).text)
        val inner = outer.output[2] as UIMessagePart.Tool
        assertEquals("call-inner", inner.toolCallId)
        assertEquals(
            "file:///data/user/0/app/files/upload/converted.png",
            (inner.output.single() as UIMessagePart.Image).url,
        )
    }

    @Test
    fun `nested tool output oversized image is replaced by placeholder`() = runBlocking {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "tool",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = dataUrl("A".repeat(32)))),
        )

        val parts = sanitizeBase64ImageParts(
            parts = listOf(tool),
            convert = { fail("超限图片不该转换"); null },
            maxPayloadChars = 8,
        )

        val output = (parts.single() as UIMessagePart.Tool).output
        assertEquals(Base64ImageOmitReason.TooLarge.placeholder, text(output.single()))
    }
}
