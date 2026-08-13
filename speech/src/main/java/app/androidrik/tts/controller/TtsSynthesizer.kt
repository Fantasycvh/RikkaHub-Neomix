package app.androidrik.tts.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import app.androidrik.tts.model.AudioChunk
import app.androidrik.tts.model.AudioFormat
import app.androidrik.tts.model.TTSRequest
import app.androidrik.tts.model.TTSResponse
import app.androidrik.tts.provider.TTSManager
import app.androidrik.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream

/**
 * Bridge TTS provider flow to a single audio buffer.
 */
class TtsSynthesizer(
    private val ttsManager: TTSManager
) {
    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk
    ): TTSResponse = withContext(Dispatchers.IO) {
        collectToResponse(
            ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
        )
    }

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        flow.collect { chunk ->
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            // 8MB 上限：异常/恶意流全量收集曾 OOM，超限即停止
            if (output.size() + chunk.data.size <= MAX_TTS_AUDIO_BYTES) {
                output.write(chunk.data)
            } else {
                return@collect
            }
        }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate
        )
    }
}

/** TTS 合成音频最大字节数（约 8MB） */
private const val MAX_TTS_AUDIO_BYTES = 8 * 1024 * 1024

