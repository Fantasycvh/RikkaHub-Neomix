package app.androidrik.common.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 诊断用探针：把「预分词扫描」和「BPE 合并」的耗时分开，避免再把扫描开销误判成计数开销。
 * 只断言正确性；耗时通过 stdout 打印，供人工/脚本读取，不参与 CI 阈值。
 */
class ByteLevelBpePerfProbeTest {
    companion object {
        private val bpe: ByteLevelBpe by lazy {
            ByteLevelBpe.load(
                ByteLevelBpePerfProbeTest::class.java
                    .getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)
                    ?: error("vocabulary resource missing"),
            )
        }

        private fun body(repeat: Int): String {
            val line = "The quick brown fox jumps over the lazy dog while the diligent engineer " +
                "reviews the request payload and verifies that every stable chunk remains identical. "
            return buildString { repeat(repeat) { append(it.toString().padStart(5, '0')).append(": ").append(line).append('\n') } }
        }
    }

    private inline fun timed(rounds: Int = 3, block: () -> Unit): Long {
        repeat(2) { block() } // warm up JIT
        var best = Long.MAX_VALUE
        repeat(rounds) {
            val started = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - started)
        }
        return best / 1_000_000
    }

    @Test
    fun `probe scan versus merge cost`() {
        val text = body(700) +
            "\nCurrent model: gpt-5.6-terra\nTrailing stable footer for boundary checking."
        println("[probe] chars=${text.length}")

        val scanMs = timed { bpe.countPreTokenMatches(text) }
        val countMs = timed { bpe.count(text) }
        val span = listOf(text.length - 60..text.length - 30)
        val volatileMs = timed {
            bpe.countWithVolatileSpans(text, span, 16) { bpe.count(it) }
        }

        println("[probe] scanMs=$scanMs countMs=$countMs volatileMs=$volatileMs")
        assertEquals(bpe.count(text), bpe.countWithVolatileSpans(text, span, 16) { bpe.count(it) })
    }
}
