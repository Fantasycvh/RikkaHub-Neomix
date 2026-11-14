package app.androidrik.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.asr.ASRController
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.asr.ASRState
import app.androidrik.asr.ASRStatus
import app.androidrik.asr.appendAmplitude
import app.androidrik.asr.calculateRmsAmplitude
import app.androidrik.asr.pcm16ToWav
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "DeepSeekASR"

/**
 * DeepSeek ASR 官方限制：单文件 ≤8 MiB / ≤120 秒。
 * PCM 阈值取 7 MiB（WAV 头 +44 字节留余量）；16kHz/16bit/mono 下 7 MiB ≈ 116 秒，
 * 与 100s 时间阈值正好双保险。
 */
internal const val DEEPSEEK_ASR_MAX_SEGMENT_BYTES = 7 * 1024 * 1024

/** DeepSeek ASR 服务端单文件时长上限（秒） */
internal const val DEEPSEEK_ASR_MAX_SEGMENT_SEC = 120

/**
 * DeepSeek ASR Controller（2026-11-13 Tool API）。
 *
 * DeepSeek ASR 是 **整段文件识别**：multipart 单字段 `file`（16-bit PCM WAV）POST 到
 * `{baseUrl}/v1/tools/asr`，一次返回完整转写，不支持流式/VAD——因此**不适合语音模式**
 * （`supportsServerVadVoiceMode` 不含本类，VoiceMode 在入口即拦截提示）。
 *
 * 结构照抄 [MiMoASRController]：录音期间按时间/字节阈值切段，每段 `pcm16ToWav` 后
 * multipart 上传，识别文本按完成顺序拼接；stop() 时把剩余 PCM 做最后一次 flush。
 * 差异点：
 * - 鉴权是标准 `Authorization: Bearer`（MiMo 用 `api-key` 头）；
 * - multipart 而非 base64 内嵌 JSON（speech 模块此前无 multipart 代码）；
 * - 重试：MiMo 无重试，DeepSeek 对 503/504 **最多重试 1 次**（429 重试会撞账号级
 *   并发闸门，其余 4xx 不重试；见配料 03 §4.4）；
 * - 无 language 参数（服务端自动判定），设置里不暴露语言项。
 */
class DeepSeekASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.DeepSeek
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 同一时刻只允许一个 flush 协程在跑, 避免乱序拼结果
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        flushJob = null

        // HTTP 一次性接口, 没有 WebSocket 连接阶段, 直接进入 Listening
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true
            )
        }
        startRecorder()
    }

    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }

        // 把剩余 PCM 做最后一次 flush, 完成后切回 Idle
        scope.launch(Dispatchers.IO) {
            try {
                flushJob?.join()
                flushSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "DeepSeek ASR final flush failed")
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                // 时间阈值夹紧到服务端 120s 硬限之内（设置页允许输 0 = 仅 stop 时上传）
                val segmentMs = provider.segmentDurationSec
                    .coerceAtLeast(0)
                    .coerceAtMost(DEEPSEEK_ASR_MAX_SEGMENT_SEC)
                    .toLong() * 1000L
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            if (segmentMs <= 0L) {
                                currentBuffer.size() >= DEEPSEEK_ASR_MAX_SEGMENT_BYTES
                            } else {
                                val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                                currentBuffer.size() >= DEEPSEEK_ASR_MAX_SEGMENT_BYTES || elapsed >= segmentMs
                            }
                        }

                        if (shouldFlush) {
                            triggerFlush()
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun triggerFlush() {
        // 同一时刻只跑一个 flush, 避免后发先至导致结果乱序
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            runCatching { flushSegment() }
                .onFailure { Log.e(TAG, "Segment flush failed", it) }
        }
    }

    /**
     * 取出当前缓冲区里的 PCM, 转 WAV 后 multipart POST 到 DeepSeek;
     * 把识别结果加到 completedTranscripts。
     */
    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        val wavBytes = pcm16ToWav(
            pcm = pcmBytes,
            sampleRate = provider.sampleRate,
            channels = 1,
            bitsPerSample = 16
        )

        val text = withContext(Dispatchers.IO) {
            uploadSegment(wavBytes)
        }

        if (text.isNotEmpty()) {
            completedTranscripts.add(text)
            publishTranscript()
        }
    }

    /**
     * 上传一段 WAV。仅对 503/504 重试 1 次（服务端瞬时过载/识别超时）；
     * 429 表示账号级并发超限，重试只会继续撞闸门，直接报错。
     */
    private fun uploadSegment(wavBytes: ByteArray, maxAttempts: Int = 2): String {
        var lastError: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return httpClient.newCall(buildAsrRequest(provider, wavBytes)).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (resp.code == 503 || resp.code == 504) {
                            throw AsrRetryableException("DeepSeek ASR HTTP ${resp.code}: $respBody")
                        }
                        throw IOException("DeepSeek ASR HTTP ${resp.code}: $respBody")
                    }
                    parseAsrResponseBody(respBody)
                }
            } catch (e: AsrRetryableException) {
                lastError = e
                Log.w(TAG, "DeepSeek ASR retryable failure (attempt ${attempt + 1}/$maxAttempts)", e)
            }
        }
        throw lastError ?: IOException("DeepSeek ASR upload failed")
    }

    private fun publishTranscript() {
        val transcript = completedTranscripts
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private class AsrRetryableException(message: String) : IOException(message)

    companion object {
        /**
         * 请求构造独立纯函数：单测直接断言 multipart 形状（字段名/文件名/Content-Type），
         * 不经录音链路与 Android Context。
         */
        internal fun buildAsrRequest(providerSetting: ASRProviderSetting.DeepSeek, wavBytes: ByteArray): Request =
            Request.Builder()
                .url("${providerSetting.baseUrl.trimEnd('/')}/v1/tools/asr")
                .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
                .post(
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        // DeepSeek ASR 只收一个 file 字段；OCR 才有 purpose 字段，这里多传会 400
                        .addFormDataPart(
                            "file",
                            "audio.wav",
                            wavBytes.toRequestBody("audio/wav".toMediaType()),
                        )
                        .build()
                )
                .build()

        /**
         * 解析 `{request_id, text, duration_ms}`。独立纯函数供单测覆盖。
         * text 为空（静音段）不算错误，返回空串由调用方跳过拼接。
         * 用 kotlinx 而非 org.json：后者在 JVM 单测里是返回 null 的桩，两边行为不一致。
         */
        internal fun parseAsrResponseBody(body: String): String {
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            }.getOrElse {
                throw IOException("DeepSeek ASR response is not valid JSON: $body")
            }
            val text = (json["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            return text.trim()
        }
    }
}
