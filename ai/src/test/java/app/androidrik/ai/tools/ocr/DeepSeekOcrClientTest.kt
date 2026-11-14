package app.androidrik.ai.tools.ocr

import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek OCR 客户端与 prompt 组装的契约守卫（配料 01 §4）。
 *
 * ## 为什么这些断言值得存在
 *
 * 1. **multipart 恰好两 part**：官方「多传任何字段返回 400」——字段集合是硬约束。
 * 2. **200-with-error 特例**：识别开始后 HTTP 已是 200，失败以 error body 返回；
 *   只看状态码会把错误当成功解析，直接 SerializationException 崩给用户看不懂的文案。
 * 3. **前导换行**：识别期间服务端持续发换行保活，body 前导空白必须容忍。
 * 4. **finish_reason=length 页必须标注**：截断的文本不标注会让模型把残缺内容当完整事实。
 * 5. **ref/box token 默认剥离**：本仓暂无消费位，裸 token 进 prompt 是噪声；
 *   且全角竖线 ｜ 是官方原文的一部分（勿改半角——改了正则就剥不掉）。
 */
class DeepSeekOcrClientTest {

    // ---------------- 请求形状 ----------------

    @Test
    fun request_is_multipart_with_exactly_file_and_purpose() {
        val client = DeepSeekOcrClient()
        val request = client.buildRequest(
            "https://api.deepseek.com/", "sk-test", byteArrayOf(1, 2), "scan.pdf", "text_recognition",
        )

        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/ocr", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))

        val body = request.body as MultipartBody
        assertEquals(2, body.parts.size)
        val names = body.parts.map { part ->
            Regex("name=\"([^\"]+)\"").find(part.headers?.get("Content-Disposition").orEmpty())?.groupValues?.get(1)
        }
        // 恰好 file + purpose，顺序无关但集合固定
        assertEquals(setOf("file", "purpose"), names.toSet())
        assertTrue(body.parts[0].headers?.get("Content-Disposition").orEmpty().contains("filename=\"scan.pdf\""))
    }

    // ---------------- 200-with-error / 保活换行 ----------------

    @Test
    fun http_200_with_error_body_is_a_failure() {
        val client = DeepSeekOcrClient(clientReturning(200, """{"error":{"message":"gateway timeout"}}"""))

        val thrown = assertThrows(DeepSeekOcrException::class.java) {
            client.recognize("https://api.deepseek.com", "sk", byteArrayOf(1), "a.png", "text_recognition", maxAttempts = 1)
        }
        assertTrue(thrown.message.orEmpty().contains("failed after upload"))
        assertTrue(thrown.message.orEmpty().contains("gateway timeout"))
    }

    @Test
    fun leading_keepalive_newlines_are_tolerated() {
        val body = "\n\n\n" + """
            {"id":"ocr-1","object":"ocr.result","page_count":1,
             "pages":[{"index":0,"text":"hello","finish_reason":"stop"}]}
        """.trimIndent()
        val client = DeepSeekOcrClient(clientReturning(200, body))

        val result = client.recognize("https://api.deepseek.com", "sk", byteArrayOf(1), "a.png", "text_recognition")
        assertEquals(1, result.pageCount)
        assertEquals("hello", result.pages.single().text)
    }

    @Test
    fun retryable_codes_are_retried_then_thrown() {
        var calls = 0
        val client = DeepSeekOcrClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (calls == 1) 503 else 200)
                .message("fake")
                .body("""{"id":"x","page_count":1,"pages":[]}""".toResponseBody(null))
                .build()
        }.build())

        val result = client.recognize("https://api.deepseek.com", "sk", byteArrayOf(1), "a.png", "text_recognition")
        assertEquals(2, calls) // 第一次 503 重试，第二次成功
        assertEquals(1, result.pageCount)
    }

    @Test
    fun non_retryable_codes_are_not_retried() {
        var calls = 0
        val client = DeepSeekOcrClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(413)
                .message("fake")
                .body("""{"error":{"message":"too large"}}""".toResponseBody(null))
                .build()
        }.build())

        val thrown = assertThrows(DeepSeekOcrException::class.java) {
            client.recognize("https://api.deepseek.com", "sk", byteArrayOf(1), "a.png", "text_recognition")
        }
        assertEquals(1, calls) // 413 不重试
        assertEquals(413, thrown.statusCode)
        assertTrue(thrown.message.orEmpty().contains("too large"))
    }

    // ---------------- prompt 组装纯函数 ----------------

    @Test
    fun layout_tokens_are_stripped_by_default() {
        val tokened = "<｜ref｜>标题<｜/ref｜><｜box｜>[[52,40,948,96]]<｜/box｜>正文内容"
        assertEquals("正文内容", stripLayoutTokens(tokened))
    }

    @Test
    fun multi_box_and_unclosed_variants_do_not_crash() {
        // 多 box 依次剥离；直接抽文字的文档没有 token，原样返回
        assertEquals("ab", stripLayoutTokens("<｜box｜>[[1,2,3,4]]<｜/box｜>a<｜box｜>[[5,6,7,8]]<｜/box｜>b"))
        assertEquals("plain", stripLayoutTokens("plain"))
    }

    @Test
    fun truncated_pages_are_marked_and_pages_are_one_based() {
        val prompt = toPromptText(
            listOf(
                DeepSeekOcrPage(index = 0, text = "第一页", finishReason = "stop"),
                DeepSeekOcrPage(index = 1, text = "第二页", finishReason = "length"),
            ),
        )
        assertTrue(prompt.contains("---Page 1:"))
        assertTrue(prompt.contains("第一页"))
        assertTrue(prompt.contains("---Page 2:"))
        assertTrue(prompt.contains("可能不完整"))
        assertTrue(prompt.contains("第二页"))
    }

    @Test
    fun include_layout_keeps_tokens() {
        val tokened = "<｜ref｜>标题<｜/ref｜><｜box｜>[[52,40,948,96]]<｜/box｜>正文"
        val prompt = toPromptText(listOf(DeepSeekOcrPage(index = 0, text = tokened, finishReason = "stop")), includeLayout = true)
        assertTrue(prompt.contains("<｜ref｜>"))
    }

    @Test
    fun response_json_round_trip_with_official_field_names() {
        val json = Json { ignoreUnknownKeys = true }
        val result = json.decodeFromString<DeepSeekOcrResult>(
            """{"id":"ocr-1","object":"ocr.result","created":1762790000,"purpose":"text_recognition",
                "page_count":2,
                "pages":[{"index":0,"text":"a","finish_reason":"stop"},
                         {"index":1,"text":"b","finish_reason":"length"}],
                "usage":{"input_tokens":1536,"output_tokens":412,"total_tokens":1948}}""".trimIndent(),
        )
        assertEquals(2, result.pageCount)
        assertEquals("length", result.pages[1].finishReason)
        assertEquals(1948, result.usage?.totalTokens)
    }

    // ---------------- 测试基建 ----------------

    private fun clientReturning(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody(null))
                .build()
        }.build()
}
