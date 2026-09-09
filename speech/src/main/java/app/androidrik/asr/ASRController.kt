package app.androidrik.asr

import kotlinx.coroutines.flow.StateFlow

interface ASRController {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    /** 语音模式：暂停麦克风采集但保持连接（TTS 播放时用，等最终转写文本回传）。 */
    fun pauseCapture() {}
    fun dispose()
}
