package app.androidrik.tts.provider.providers

import app.androidrik.tts.model.AudioFormat
import app.androidrik.tts.provider.TTSProviderException
import app.androidrik.tts.provider.TTSProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek TTS（整段 POST）的契约守卫。
 *
 * ## 为什么这些断言值得存在
 *
 * 1. **请求形状是线上契约**：`{text, voice_id, format, filter_markdown}` 四字段 +
 *    Bearer 头 + `/v1/tools/tts` 路径；官方要求请求体 ≤64 KiB，不发多余字段是硬约束。
 * 2. **默认 `wav` 不是随意选的**：`AudioPlayer` 对裸 PCM 缺省按 24000 Hz 包 WAV 头
 *    （采样率猜错会变调且不报错），`format=wav` 时服务端带容器头、ExoPlayer 直接吃；
 *    `pcm` 分支必须显式带上 `DEEPSEEK_TTS_PCM_SAMPLE_RATE`。
 * 3. **计费字符预检**：中文算 2——超 10,000 计费字符时**不发请求**直接失败
 *    （省一次 400 往返）。
 * 4. **错误保留服务端原文 + 状态码**：400 = 无可读文本/内容审核（不可重试），
 *    429/503/504 可重试——isRetryable 依据 statusCode，丢了就失去重试能力。
 *
 * HTTP 不打桩库：给 [OkHttpClient] 装一个直接返回假响应的 interceptor（与本仓
 * `DeepSeekSearchServiceTest` 同款惯例），整条请求构造→发送→映射路径真跑一遍。
 * [DeepSeekTTSProvider.synthesize] 不经 Android Context，JVM 单测可直接调用；
 * generateSpeech 只是 `flow { emit(synthesize(...)) }` 的薄包装。
 */
class DeepSeekTTSProviderTest {

    // ---------------- 计费字符预检 ----------------

    @Test
    fun cjk_chars_are_billed_double() {
        assertEquals(4L, estimateBilledChars("你好")) // 2 个中文 × 2
        assertEquals(5L, estimateBilledChars("hello")) // 5 个 ASCII × 1
        assertEquals(6L, estimateBilledChars("你好ok")) // 2×2 + 2×1
    }

    @Test
    fun precheck_rejects_over_limit_text_without_sending_request() {
        // 6000 个中文 = 12000 计费字符 > 10000：必须在发请求前就失败
        val provider = DeepSeekTTSProvider(httpClient = failingClient())
        val setting = TTSProviderSetting.DeepSeek(apiKey = "sk-test")

        val thrown = assertThrows(
            TTSProviderException::class.java,
        ) { provider.synthesize(setting, "你".repeat(6000)) }

        assertTrue(thrown.message.orEmpty().contains("billed chars"))
        assertEquals(400, thrown.statusCode)
    }

    // ---------------- 请求形状 ----------------

    @Test
    fun request_shape_matches_official_contract() {
        val provider = DeepSeekTTSProvider()
        val setting = TTSProviderSetting.DeepSeek(
            apiKey = "sk-test",
            baseUrl = "https://api.deepseek.com",
            voiceId = "stella",
            format = "wav",
            filterMarkdown = false,
        )

        val request = provider.buildRequest(setting, "你好")

        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/tts", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        // 读真实请求体（toString 不含内容）；JsonObject.toString() 的 `: ` 带空格，归一化后断言
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8().replace(" ", "")
        assertTrue(body.contains("\"text\""))
        assertTrue(body.contains("\"voice_id\":\"stella\""))
        assertTrue(body.contains("\"format\":\"wav\""))
        assertTrue(body.contains("\"filter_markdown\":false"))
    }

    @Test
    fun trailing_slash_base_url_does_not_duplicate_path() {
        val provider = DeepSeekTTSProvider()
        val setting = TTSProviderSetting.DeepSeek(baseUrl = "https://api.deepseek.com/")

        val request = provider.buildRequest(setting, "hi")

        assertEquals("https://api.deepseek.com/v1/tools/tts", request.url.toString())
    }

    // ---------------- 完整链路（interceptor 假响应）----------------

    @Test
    fun successful_wav_response_maps_to_single_terminal_chunk() {
        val audioBytes = byteArrayOf(1, 2, 3, 4, 5)
        val captured = arrayOfNulls<okhttp3.Request>(1)
        val provider = DeepSeekTTSProvider(
            httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    captured[0] = chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(audioBytes.toResponseBody(null))
                        .build()
                }
                .build(),
        )
        val setting = TTSProviderSetting.DeepSeek(apiKey = "sk-test", format = "wav")

        val chunk = provider.synthesize(setting, "朗读测试")

        assertEquals(AudioFormat.WAV, chunk.format)
        assertTrue(chunk.isLast)
        assertEquals(DEEPSEEK_TTS_PCM_SAMPLE_RATE, chunk.sampleRate)
        assertTrue(chunk.data.contentEquals(audioBytes))
        assertEquals("https://api.deepseek.com/v1/tools/tts", captured[0]?.url?.toString())
    }

    @Test
    fun pcm_format_maps_to_pcm_with_explicit_sample_rate() {
        val provider = DeepSeekTTSProvider(httpClient = fakeClient(byteArrayOf(9, 9)))
        val setting = TTSProviderSetting.DeepSeek(apiKey = "sk-test", format = "pcm")

        val chunk = provider.synthesize(setting, "朗读")

        assertEquals(AudioFormat.PCM, chunk.format)
        // PCM 必须显式给采样率：播放端猜错会静默变调
        assertEquals(DEEPSEEK_TTS_PCM_SAMPLE_RATE, chunk.sampleRate)
    }

    @Test
    fun http_400_carries_server_message_and_is_not_retryable() {
        val provider = DeepSeekTTSProvider(
            httpClient = fakeClient("""{"error":{"message":"Content Exists Risk"}}""".toByteArray(), code = 400),
        )
        val setting = TTSProviderSetting.DeepSeek(apiKey = "sk-test")

        val thrown = assertThrows(
            TTSProviderException::class.java,
        ) { provider.synthesize(setting, "测试") }

        assertEquals(400, thrown.statusCode)
        assertTrue(!thrown.isRetryable)
        assertTrue(thrown.message.orEmpty().contains("Content Exists Risk"))
    }

    @Test
    fun http_429_is_retryable() {
        val provider = DeepSeekTTSProvider(
            httpClient = fakeClient("""{"error":{"message":"rate limited"}}""".toByteArray(), code = 429),
        )
        val setting = TTSProviderSetting.DeepSeek(apiKey = "sk-test")

        val thrown = assertThrows(
            TTSProviderException::class.java,
        ) { provider.synthesize(setting, "测试") }

        assertEquals(429, thrown.statusCode)
        assertTrue(thrown.isRetryable)
    }

    @Test
    fun empty_200_body_is_a_failure_not_silent_success() {
        val provider = DeepSeekTTSProvider(httpClient = fakeClient(byteArrayOf()))
        val setting = TTSProviderSetting.DeepSeek(apiKey = "sk-test")

        val thrown = assertThrows(
            TTSProviderException::class.java,
        ) { provider.synthesize(setting, "测试") }

        assertTrue(thrown.message.orEmpty().contains("0 bytes"))
    }

    // ---------------- 测试基建 ----------------

    private fun fakeClient(body: ByteArray, code: Int = 200): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fake")
                    .body(body.toResponseBody(null))
                    .build()
            }
            .build()

    /** 预检路径若真的发了请求，测试应立刻失败暴露 */
    private fun failingClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { throw IllegalStateException("request should never be sent") }
            .build()
}
