package app.androidrik.common.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ByteLevelBpeVolatileSpansTest {
    companion object {
        private val bpe: ByteLevelBpe by lazy {
            ByteLevelBpe.load(
                ByteLevelBpeVolatileSpansTest::class.java
                    .getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)
                    ?: error("vocabulary resource missing"),
            )
        }
    }

    private fun stableChunks(
        text: String,
        spans: List<IntRange>,
        context: Int = 16,
    ): List<String> {
        val chunks = mutableListOf<String>()
        val actual = bpe.countWithVolatileSpans(text, spans, context) {
            chunks += it
            bpe.count(it)
        }
        assertEquals("text=$text spans=$spans context=$context", bpe.count(text), actual)
        return chunks
    }

    @Test
    fun `empty text and no spans do not create empty chunks`() {
        assertEquals(emptyList<String>(), stableChunks("", emptyList()))
        val text = "中文 👩🏽‍💻 can't 123456\n\t  hello"
        assertEquals(listOf(text), stableChunks(text, emptyList()))
    }

    @Test
    fun `default context is sixteen UTF-16 chars and same class matches are grouped`() {
        val prefix = "alpha beta gamma delta epsilon "
        val suffix = " omega sigma theta kappa lambda"
        val text = prefix + "123456789012345678901234567890123456789012345678901234567890123456" + suffix
        // Numeric regex matches are 3 digits, not BPE tokens or a fixed-width text window.
        val point = prefix.length + 30
        assertEquals(
            listOf(prefix + "123456789012", "901234567890123456" + suffix),
            stableChunks(text, listOf(point..point)),
        )
        assertEquals(stableChunks(text, listOf(point..point)), stableChunks(text, listOf(point..point), 0))
        assertEquals(stableChunks(text, listOf(point..point)), stableChunks(text, listOf(point..point), -100))
    }

    @Test
    fun `context expands through arbitrarily long letter and chinese matches`() {
        for (letters in listOf("antidisestablishmentarianism".repeat(80), "这是没有空格的一整段中文文本".repeat(100))) {
            val prefix = "stable prefix!"
            val suffix = "! stable suffix"
            val text = prefix + " " + letters + suffix
            val point = prefix.length + letters.length / 2
            // The leading space belongs to the complete letter match, not the stable prefix.
            assertEquals(listOf(prefix, suffix), stableChunks(text, listOf(point..point)))
        }
    }

    @Test
    fun `emoji contractions unicode whitespace and digit boundaries stay exact`() {
        val samples = listOf(
            "中文汉字与更多中文，英文 hello 世界。",
            "👩🏽‍💻🙂🚀👨‍👩‍👧‍👦 emoji 中文 🧑🏿‍🚀",
            "I can't we're I'LL they've she'd I'm isn't wouldn't",
            "a \t  \r\n\n\u00a0\u3000\u2003 b\t\t\n c   ",
            "12345678901234567890123456789012345678901234567890",
        )
        for (sample in samples) {
            val text = "stable prefix with many words! ".repeat(3) + sample + "! stable suffix with many words".repeat(3)
            for (point in text.indices) {
                // Includes windows landing inside surrogate pairs, contractions and 3-digit groups.
                stableChunks(text, listOf(point..point))
            }
        }
    }

    @Test
    fun `unsorted overlapping adjacent and separate spans group stable islands`() {
        val text = ("alpha beta 中文 can't 👩🏽‍💻 123456789\n".repeat(30))
        val first = 80..82
        val second = 600..610
        val expected = stableChunks(text, listOf(first, second))
        assertEquals(3, expected.size)
        assertEquals(expected, stableChunks(text, listOf(second, first, first, 81..83, 84..84)))
        assertEquals(emptyList<String>(), stableChunks(text, listOf(0..text.lastIndex)))
    }

    @Test
    fun `whitespace lookahead cannot retokenize stable chunk at artificial end of input`() {
        val prefix = "permanent header!"
        val suffix = " permanent suffix"
        for (space in listOf(" ", "\u00a0", "\u3000", "\u2003")) {
            val text = prefix + space.repeat(2) + "!".repeat(100) + suffix
            val point = prefix.length + 40
            assertEquals(listOf(prefix, suffix), stableChunks(text, listOf(point..point)))
            // Reverse classification: the whitespace group itself intersects the window.
            stableChunks(text, listOf(prefix.length..prefix.length))
        }
    }

    @Test
    fun `invalid ranges bypass stable cache for the whole text`() {
        val text = "stable 中文 👩🏽‍💻 123456 can't\n".repeat(10)
        for (invalid in listOf(-1..3, 5..4, 0..text.length, text.length..text.length, Int.MIN_VALUE..Int.MAX_VALUE)) {
            assertEquals(emptyList<String>(), stableChunks(text, listOf(30..31, invalid)))
        }
        assertEquals(emptyList<String>(), stableChunks("", listOf(0..0)))
    }

    @Test
    fun `large context cannot overflow and always counts the entire text as volatile`() {
        val text = "prefix 中文 can't 123456 👩🏽‍💻 suffix".repeat(10)
        assertEquals(emptyList<String>(), stableChunks(text, listOf(100..100), Int.MAX_VALUE))
    }

    @Test
    fun `persistent stable cache is reused across changes to volatile numeric value`() {
        val cache = mutableMapOf<String, Int>()
        var hits = 0
        val prefix = "permanent stable prefix! " + "123456789012345678901234567890"
        val suffix = "123456789012345678901234567890" + "! permanent stable suffix"
        for (value in listOf("111", "222", "333", "44444", "555555555")) {
            val text = prefix + value + suffix
            val count = bpe.countWithVolatileSpans(text, listOf(prefix.length until prefix.length + value.length)) { chunk ->
                cache[chunk]?.also { hits++ } ?: bpe.count(chunk).also { cache[chunk] = it }
            }
            assertEquals(bpe.count(text), count)
        }
        assertTrue("unchanged regex-aligned chunks should hit persistent cache", hits >= 6)
    }

    @Test
    fun `randomized multilingual inputs spans and context match whole count`() {
        val random = Random(0xB0E)
        val fragments = listOf(
            "中文", "测试", "hello", " antidisestablishmentarianism", "🙂", "👩🏽‍💻", "𐐀",
            "can't", "'RE", "'ll", "123", "4567890", "９８７６", "!?:", "\r\n", "\t",
            " ", "   ", "\u3000", "\u00a0", "\u200b", "\u2003", "\n\n", "_", "'",
        )
        repeat(500) {
            val text = buildString { repeat(random.nextInt(1, 100)) { append(fragments.random(random)) } }
            val spans = List(random.nextInt(0, 8)) {
                val start = random.nextInt(text.length)
                start..random.nextInt(start, text.length)
            }.shuffled(random)
            stableChunks(text, spans, listOf(-1, 0, 16, 17, 32, 128, Int.MAX_VALUE).random(random))
        }
    }
}
