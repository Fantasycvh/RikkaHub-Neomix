package app.androidrik.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.tts.model.AudioChunk
import app.androidrik.tts.model.AudioFormat
import app.androidrik.tts.model.TTSRequest
import app.androidrik.tts.provider.TTSProvider
import app.androidrik.tts.provider.TTSProviderException
import app.androidrik.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "DeepSeekTTSProvider"

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** DeepSeek TTS 单请求计费字符上限（中文字符算 2，其余算 1） */
internal const val DEEPSEEK_TTS_MAX_BILLED_CHARS = 10_000L

/** DeepSeek TTS PCM 输出规格：16-bit 单声道 24 kHz（官方文档固定值） */
const val DEEPSEEK_TTS_PCM_SAMPLE_RATE = 24000

/**
 * 估算 DeepSeek TTS 的计费字符数：中文等 CJK 字符算 2，其余算 1。
 *
 * 服务端口径以官方为准；这里是**客户端预检**——超限直接报错不发请求，
 * 省一次 400 往返（失败不计费，但等待是真实的）。
 */
internal fun estimateBilledChars(text: String): Long {
    var billed = 0L
    for (ch in text) {
        billed += if (isCjkChar(ch)) 2L else 1L
    }
    return billed
}

/**
 * 「中文字符算 2」的客户端近似：CJK 统一表意文字及其扩展、全角标点与假名等
 * 东亚文字块。与服务端的精确口径可能存在边缘差异（如个别符号归属），
 * 但本仓朗读分片仅 160 字符，任何近似都远够用——预检只防异常长的整段合成。
 */
private fun isCjkChar(ch: Char): Boolean {
    val block = Character.UnicodeBlock.of(ch)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES
}

/**
 * DeepSeek TTS 适配器（2026-11-13 Tool API）。
 *
 * 走 [POST {baseUrl}/v1/tools/tts] 整段接口：JSON `{text, voice_id, format, filter_markdown}`，
 * 服务端边合成边流式返回音频字节。鉴权 `Authorization: Bearer`。
 *
 * 错误语义（官方文档）：
 * - 400 = 无可朗读文本或未过内容审核（`Content Exists Risk`），不可重试；
 * - 429/503/504 可重试（[TTSProviderException.isRetryable] 由 TtsController 消费）；
 * - **响应提前结束视为失败**：合成中途断连时 OkHttp 读 body 会抛 IOException，
 *   不会把截断的音频当成功返回。
 *
 * 官方文档: https://api-docs.deepseek.com/zh-cn/guides/tts/
 */
class DeepSeekTTSProvider(
    // 构造注入供测试用假 client（本仓惯例：HTTP 不打桩库，用 interceptor 拦截）
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // 一次性合成慢（10k 计费字符 + 排队），给足读超时
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : TTSProvider<TTSProviderSetting.DeepSeek> {

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.DeepSeek,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        emit(synthesize(providerSetting, request.text))
    }

    /**
     * 整段合成独立成非挂起纯函数：HTTP 全链路（预检→请求→错误分类→chunk 映射）
     * 可在 JVM 单测里直接覆盖，不必经 Android [Context]（本仓 provider 测试惯例）。
     */
    internal fun synthesize(providerSetting: TTSProviderSetting.DeepSeek, text: String): AudioChunk {
        val billed = estimateBilledChars(text)
        if (billed > DEEPSEEK_TTS_MAX_BILLED_CHARS) {
            throw TTSProviderException(
                message = "DeepSeek TTS text too long: ~$billed billed chars (max $DEEPSEEK_TTS_MAX_BILLED_CHARS)",
                statusCode = 400,
            )
        }

        Log.i(
            TAG,
            "generateSpeech: voice=${providerSetting.voiceId} format=${providerSetting.format} " +
                "textLen=${text.length} billedChars=$billed"
        )

        val response = httpClient.newCall(buildRequest(providerSetting, text)).execute()
        if (!response.isSuccessful) {
            val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
            throw TTSProviderException(
                message = "DeepSeek TTS request failed: HTTP ${response.code} ${response.message}. body=$errorBody",
                statusCode = response.code,
            )
        }

        val audioBytes = response.body?.bytes()
            ?: throw TTSProviderException(
                message = "DeepSeek TTS returned empty body",
                statusCode = response.code,
            )
        if (audioBytes.isEmpty()) {
            throw TTSProviderException(
                message = "DeepSeek TTS returned 0 bytes",
                statusCode = response.code,
            )
        }

        val audioFormat = when (providerSetting.format.lowercase()) {
            "pcm" -> AudioFormat.PCM
            else -> AudioFormat.WAV
        }

        return AudioChunk(
            data = audioBytes,
            format = audioFormat,
            sampleRate = DEEPSEEK_TTS_PCM_SAMPLE_RATE,
            isLast = true,
            metadata = mapOf(
                "provider" to "deepseek",
                "voiceId" to providerSetting.voiceId,
                "format" to providerSetting.format,
            ),
        )
    }

    /** 请求构造独立成纯函数：单测直接断言 URL/方法/头/JSON 体，不必经 Android [Context]。 */
    internal fun buildRequest(providerSetting: TTSProviderSetting.DeepSeek, text: String): Request {
        val requestBody = buildJsonObject {
            put("text", text)
            put("voice_id", providerSetting.voiceId)
            put("format", providerSetting.format)
            put("filter_markdown", providerSetting.filterMarkdown)
        }
        return Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/v1/tools/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/wav, audio/pcm, application/octet-stream")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }
}
