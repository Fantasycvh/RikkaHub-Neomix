package app.androidrik.rikkahub.ui.components.message

/** Read-only source preview. Ranges partition UTF-16 exactly; no delimiters/characters are discarded. */
internal data class PlainTextChunk(val start: Int, val end: Int)
internal fun plainTextChunks(text: String, maxChars: Int = 4096): List<PlainTextChunk> {
    require(maxChars >= 2)
    val chunks = mutableListOf<PlainTextChunk>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
        chunks += PlainTextChunk(start, end)
        start = end
    }
    return chunks
}
