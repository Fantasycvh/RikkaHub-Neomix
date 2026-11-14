package app.androidrik.asr.providers

import app.androidrik.asr.ASRProviderSetting
import app.androidrik.asr.pcm16ToWav
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * DeepSeek ASR（整段 multipart 识别）的契约守卫。
 *
 * ## 为什么这些断言值得存在
 *
 * 1. **multipart 恰好一个 `file` 字段**：官方明确「多传任何字段返回 400」——
 *    OCR 才有 `purpose` 字段，ASR 多传是必踩的坑。
 * 2. **Content-Type 与文件名**：服务端按文件实际内容判断格式，但 WAV 头 + audio/wav
 *    的正确描述是第一道兼容性防线。
 * 3. **响应解析**：`{request_id, text, duration_ms}` 三字段里只消费 text；
 *    非 JSON / 缺 text 不能崩（静音段返回空 text 是正常路径）。
 * 4. **分段常量 < 服务端硬限**：8 MiB / 120s 是 400/413 的直接来源，客户端阈值必须留余量。
 */
class DeepSeekASRControllerTest {

    @Test
    fun request_is_multipart_with_exactly_one_file_field() {
        val provider = ASRProviderSetting.DeepSeek(apiKey = "sk-test")
        val wav = pcm16ToWav(byteArrayOf(1, 0, 2, 0), 16000, 1, 16)

        val request = DeepSeekASRController.buildAsrRequest(provider, wav)

        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/asr", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))

        val body = request.body as MultipartBody
        // 只允许 file 一个字段：OCR 才有 purpose，ASR 多传任何字段服务端 400
        assertEquals(1, body.parts.size)
        assertEquals("file", body.parts.single().headers?.get("Content-Disposition")?.let {
            Regex("name=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        })
        val disposition = body.parts.single().headers?.get("Content-Disposition").orEmpty()
        assertTrue(disposition.contains("filename=\"audio.wav\""))
        assertTrue(body.parts.single().body.contentType().toString().startsWith("audio/wav"))
    }

    @Test
    fun trailing_slash_base_url_does_not_duplicate_path() {
        val provider = ASRProviderSetting.DeepSeek(baseUrl = "https://api.deepseek.com/")

        val request = DeepSeekASRController.buildAsrRequest(provider, byteArrayOf(1))

        assertEquals("https://api.deepseek.com/v1/tools/asr", request.url.toString())
    }

    @Test
    fun response_text_is_extracted_and_trimmed() {
        assertEquals(
            "你好世界",
            DeepSeekASRController.parseAsrResponseBody("""{"request_id":"abc","text":" 你好世界 ","duration_ms":3200}"""),
        )
        // 静音段：text 空/缺 = 空串（正常路径，不报错）
        assertEquals("", DeepSeekASRController.parseAsrResponseBody("""{"request_id":"abc","text":""}"""))
        assertEquals("", DeepSeekASRController.parseAsrResponseBody("""{"request_id":"abc"}"""))
    }

    @Test
    fun non_json_response_is_a_descriptive_error_not_a_crash() {
        val thrown = assertThrows(IOException::class.java) {
            DeepSeekASRController.parseAsrResponseBody("<html>bad gateway</html>")
        }
        assertTrue(thrown.message.orEmpty().contains("not valid JSON"))
    }

    @Test
    fun segment_thresholds_stay_within_server_limits() {
        // 客户端字节阈值必须 < 服务端 8 MiB 硬限（WAV 头 +44B 后仍不能越界）
        assertTrue(DEEPSEEK_ASR_MAX_SEGMENT_BYTES < 8 * 1024 * 1024)
        // 时间阈值不越过 120s 硬限
        assertTrue(DEEPSEEK_ASR_MAX_SEGMENT_SEC <= 120)
    }
}
