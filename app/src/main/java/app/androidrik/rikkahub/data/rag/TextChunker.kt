package app.androidrik.rikkahub.data.rag

/**
 * 文本分块（移植自 MineChat RAG/TextChunker）。
 *
 * 段落优先聚块：先按空行分段，段内塞到 [chunkSize] 为止；超长段落再按固定窗口切，
 * 保留 [overlap] 字符重叠以维持上下文连续性。纯 JVM 逻辑，可直接单测。
 */
object TextChunker {
    private const val DEFAULT_CHUNK_SIZE = 512
    private const val DEFAULT_OVERLAP = 128

    fun chunk(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        overlap: Int = DEFAULT_OVERLAP,
    ): List<String> {
        if (text.isBlank()) return emptyList()

        val paragraphs = text.split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.length > chunkSize) {
                // 段落太大，先落盘当前块，再对段落做固定窗口切分
                if (currentChunk.isNotBlank()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.clear()
                }
                chunks.addAll(splitLargeParagraph(paragraph, chunkSize, overlap))
            } else if (currentChunk.length + paragraph.length + 1 > chunkSize) {
                // 当前块加上新段落会超限：先落盘当前块，再带重叠起新块
                chunks.add(currentChunk.toString().trim())
                val overlapText = getOverlapText(currentChunk.toString(), overlap)
                currentChunk.clear()
                if (overlapText.isNotBlank()) {
                    currentChunk.append(overlapText).append("\n")
                }
                currentChunk.append(paragraph)
            } else {
                if (currentChunk.isNotBlank()) {
                    currentChunk.append("\n\n")
                }
                currentChunk.append(paragraph)
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun splitLargeParagraph(
        text: String,
        chunkSize: Int,
        overlap: Int,
    ): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end).trim())
            start += chunkSize - overlap
        }
        return chunks
    }

    private fun getOverlapText(text: String, overlap: Int): String {
        if (text.length <= overlap) return text
        return text.substring(text.length - overlap)
    }
}

/** 余弦相似度；维度不一致或零向量时返回 0（不同 embedding 模型的向量不可比，按不相似处理）。 */
fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
    return if (denominator > 0) dot / denominator else 0f
}
