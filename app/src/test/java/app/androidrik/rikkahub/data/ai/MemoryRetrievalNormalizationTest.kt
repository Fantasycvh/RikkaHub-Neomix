package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalNormalizationTest {
    private fun relevance(query: String, content: String) =
        memoryQueryTokens(query).relevanceOf(AssistantMemory("fixture", content))

    @Test
    fun unicodeEquivalentFormsMatchInBothDirections() {
        listOf(
            "ＴｉｋＺ" to "TikZ",
            "ＦＴＳ５" to "FTS5",
            "Cafe\u0301" to "Café",
            "nai\u0308ve" to "naïve",
            "ｶﾀｶﾅ" to "カタカナ",
            "한글" to "한글",
            "Ａｎｄｒｏｉｄ" to "Android",
            "re\u0301sume\u0301" to "résumé",
        ).forEach { (a, b) ->
            assertEquals("$a -> $b", 1f, relevance(a, b), 0f)
            assertEquals("$b -> $a", 1f, relevance(b, a), 0f)
        }
    }

    @Test
    fun mixedQueriesKeepStandaloneCjkEvidenceWithoutOverweightingIt() {
        assertEquals(0.5f, relevance("猫 python", "用户养了一只猫"), 0f)
        assertEquals(0.5f, relevance("猫 python", "Python scripts"), 0f)
        assertEquals(1f, relevance("猫 python", "猫与 Python"), 0f)
        assertEquals(0.5f, relevance("茶 Kotlin", "喜欢喝茶"), 0f)
        assertEquals(0f, relevance("猫 python", "用户喜欢咖啡"), 0f)
        assertEquals(1f, relevance("猫", "用户养了一只猫"), 0f)
    }

    @Test
    fun singletonCjkRunsWorkBesideOtherCjkGramsAndWithoutSpaces() {
        assertEquals(0.5f, relevance("咖啡 猫", "咖啡"), 0f)
        assertEquals(0.5f, relevance("咖啡 猫", "猫"), 0f)
        assertEquals(0.5f, relevance("猫Python", "猫"), 0f)
        assertEquals(1f, relevance("猫 猫 Python", "猫 Python"), 0f)
        assertEquals(0f, relevance("咖啡", "咖"), 0f)
    }

    @Test
    fun normalizationDoesNotAddFuzzyStemmingOrStopwordEvidence() {
        assertEquals(0f, relevance("cafe", "Café"), 0f)
        assertEquals(0f, relevance("coff", "coffee"), 0f)
        assertEquals(0f, relevance("cats", "cat"), 0f)
        assertEquals(0f, relevance("咖啡", "咖 啡"), 0f)
        assertEquals(1f, relevance("我 Python", "Python"), 0f)
        assertTrue(memoryQueryTokens("我们的是可以").isEmpty)
        assertTrue(memoryQueryTokens("ＰＬＥＡＳＥ ＨＥＬＰ ＭＥ").isEmpty)
    }
}
