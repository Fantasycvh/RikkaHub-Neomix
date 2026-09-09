package app.androidrik.asr

/** 一次服务端 VAD 语句。仅 stopped 事件不含最终转写文本。 */
data class ASRVoiceTurn(
    val itemId: String? = null,
    val speechEnded: Boolean = false,
    val finalText: String? = null,
) {
    val isComplete: Boolean get() = speechEnded && finalText != null

    fun started(id: String): ASRVoiceTurn =
        if (itemId == null && id.isNotBlank()) copy(itemId = id) else this

    fun stopped(id: String): ASRVoiceTurn = started(id).let {
        if (id.isNotBlank() && it.itemId == id) it.copy(speechEnded = true) else it
    }

    fun completed(id: String, text: String): ASRVoiceTurn = started(id).let {
        if (id.isNotBlank() && it.itemId == id) it.copy(finalText = text.trim()) else it
    }
}
