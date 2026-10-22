package app.androidrik.common.tokenizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * 手写预分词扫描器与 cl100k 正则的**对拍**测试。
 *
 * 桌面 JVM 上正则很快，所以随机语料可以跑几十万组；一旦有字符集/贪婪回溯的偏差，
 * 这里会直接暴露（片段边界逐字符比较，而不是只比 token 数）。
 */
class ByteLevelBpePreTokenParityTest {
    companion object {
        private val bpe: ByteLevelBpe by lazy {
            ByteLevelBpe.load(
                ByteLevelBpePreTokenParityTest::class.java
                    .getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)
                    ?: error("vocabulary resource missing"),
            )
        }
        private val reference by lazy { bpe.RegexReference() }

        private fun scanPieces(text: String): List<String> {
            val pieces = ArrayList<String>(text.length / 3 + 4)
            var index = 0
            while (index < text.length) {
                val end = bpe.pieceEndsForTest(text, index)
                pieces += text.substring(index, end)
                index = end
            }
            return pieces
        }

        private const val ALPHABET =
            "abcXYZ019 \t\n\r'\"sdmtSDMTlLvVeErR\u00a0\u3000\u2000\u000b\u000c\u001c\u0085" +
                "中文汉字，。！？、ー한글あいうアイウ" +
                "١٢٣٤\u2160\u2460\u00b2\u00bd" +
                "👍🏽‍👩éñüßç\u0301\u200b\u200d." +
                "!@#$%^&*()[]{}<>/\\|~`+=_-"
    }

    private fun randomText(random: Random, maxLength: Int): String {
        val length = random.nextInt(maxLength + 1)
        val builder = StringBuilder(length)
        repeat(length) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return builder.toString()
    }

    @Test
    fun `scanner matches regex on fixed edge cases`() {
        val cases = listOf(
            "",
            " ",
            "  ",
            "   a",
            "a   ",
            "a  \n b",
            "\n",
            "\r\n",
            " \n \n ",
            "  \n",
            "'s",
            "'S",
            "'ll",
            "'LL",
            "'l",
            "'re",
            "'ve",
            "'",
            "''s",
            "we'll I'm it's",
            "123",
            "1234",
            "12",
            "١٢٣٤",
            "中文 测试",
            "，。！",
            " .",
            " .\n\n",
            "a1b2c3",
            "\u3000中文",
            "\u200b zero width",
            "👍🏽 emoji",
            "a\tb",
            "}\"",
            "  }  ",
        )
        for (case in cases) {
            assertEquals("case=$case", reference.pieces(case), scanPieces(case))
        }
    }

    @Test
    fun `scanner matches regex on randomized corpora`() {
        val random = Random(20261022)
        var checked = 0
        repeat(60_000) {
            val text = randomText(random, 24)
            assertEquals("text=$text", reference.pieces(text), scanPieces(text))
            checked++
        }
        println("[parity] randomized texts checked=$checked")
    }

    @Test
    fun `scanner matches regex on long structured text`() {
        val random = Random(7)
        val builder = StringBuilder()
        repeat(400) {
            builder.append("Line ").append(it).append(": we'll check 12345 chars 's 中文 ，。👍\n")
            if (random.nextBoolean()) builder.append("  ")
        }
        val text = builder.toString()
        assertEquals(reference.pieces(text), scanPieces(text))
    }

    @Test
    fun `token counts stay identical to encoded ids`() {
        val random = Random(11)
        repeat(200) {
            val text = randomText(random, 60)
            assertEquals("text=$text", bpe.encode(text).size, bpe.count(text))
        }
    }
}
