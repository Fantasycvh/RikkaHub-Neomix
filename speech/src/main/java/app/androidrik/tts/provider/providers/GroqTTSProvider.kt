package app.androidrik.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GroqTTSProvider"

class GroqTTSProvider : TTSProvider<TTSProviderSetting.Groq> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Groq,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", request.text)
            put("voice", providerSetting.voice)
            put("response_format", "wav")
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/audio/speech")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            Log.e(TAG, "generateSpeech: ${response.code} ${response.message}")
            Log.e(TAG, "generateSpeech: ${response.body?.string()}")
            throw TTSProviderException("Groq TTS request failed: ${response.code} ${response.message}", statusCode = response.code)
        }

        val audioData = response.body.bytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "groq",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice,
                    "response_format" to "wav"
                )
            )
        )
    }
}
