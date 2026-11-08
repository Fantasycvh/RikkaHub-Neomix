package app.androidrik.rikkahub.perf

import app.androidrik.rikkahub.ui.hooks.CustomTtsState
import app.androidrik.tts.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

/** Match an unavailable TTS provider, with no playback engine or network initialization. */
internal object BaselineTts : CustomTtsState {
    override val isAvailable = MutableStateFlow(false)
    override val isSpeaking = MutableStateFlow(false)
    override val error = MutableStateFlow<String?>(null)
    override val currentChunk = MutableStateFlow(0)
    override val totalChunks = MutableStateFlow(0)
    override val playbackState = MutableStateFlow(PlaybackState())
    override fun speak(text: String, flushCalled: Boolean) = error("TTS must not run during a baseline")
    override fun stop() {}
    override fun pause() {}
    override fun resume() {}
    override fun skipNext() {}
    override fun fastForward(ms: Long) {}
    override fun setSpeed(speed: Float) {}
    override fun cleanup() {}
}

internal object BaselineAsr : app.androidrik.rikkahub.ui.hooks.CustomAsrState {
    override val state = MutableStateFlow(app.androidrik.asr.ASRState())
    override fun start(onTranscriptChange: (String) -> Unit) = error("ASR must not run during a baseline")
    override fun stop() {}
    override fun cleanup() {}
}
