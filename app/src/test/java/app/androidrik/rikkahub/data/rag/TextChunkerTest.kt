package app.androidrik.rikkahub.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun `blank text produces no chunks`() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun `short paragraphs merge into one chunk`() {
        val text = "第一段\n\n第二段\n\n第三段"
        val chunks = TextChunker.chunk(text, chunkSize = 512, overlap = 128)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("第一段"))
        assertTrue(chunks[0].contains("第三段"))
    }

    @Test
    fun `paragraphs split when exceeding chunk size`() {
        val text = (1..20).joinToString("\n\n") { "段落${it}" + "x".repeat(60) }
        val chunks = TextChunker.chunk(text, chunkSize = 200, overlap = 50)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("chunk too large: ${chunk.length}", chunk.length <= 200)
        }
        // 所有内容都进了分块（重叠只会增加，不会丢）
        assertTrue(chunks.joinToString("").contains("段落1"))
        assertTrue(chunks.joinToString("").contains("段落20"))
    }

    @Test
    fun `very long single paragraph is windowed with overlap`() {
        val text = "a".repeat(1000)
        val chunks = TextChunker.chunk(text, chunkSize = 200, overlap = 50)
        assertTrue(chunks.size >= 5)
        // 窗口推进步长 = 200 - 50 = 150，相邻块首尾重叠 50 字符
        assertTrue(chunks[1].startsWith(chunks[0].takeLast(50)))
    }

    @Test
    fun `cosine similarity basic cases`() {
        assertEquals(1f, cosineSimilarity(listOf(1f, 0f), listOf(1f, 0f)))
        assertEquals(0f, cosineSimilarity(listOf(1f, 0f), listOf(0f, 1f)))
        assertEquals(-1f, cosineSimilarity(listOf(1f, 0f), listOf(-1f, 0f)))
        // 维度不一致 = 不同模型的向量，按不相似处理
        assertEquals(0f, cosineSimilarity(listOf(1f, 2f), listOf(1f, 2f, 3f)))
        assertEquals(0f, cosineSimilarity(emptyList(), emptyList()))
    }
}
