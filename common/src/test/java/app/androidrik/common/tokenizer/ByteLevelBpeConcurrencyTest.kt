package app.androidrik.common.tokenizer

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ByteLevelBpe.count] 的并发安全：拼装预览把精确计数分摊到多核，前提是同一实例可被并发调用。
 * 片段缓存是 `ConcurrentHashMap`、词表只读，这里用真实词表验证"并发结果 == 串行结果"。
 */
class ByteLevelBpeConcurrencyTest {

    @Before
    fun setUp() = TokenizerRegistry.release()

    @After
    fun tearDown() = TokenizerRegistry.release()

    @Test
    fun `concurrent counting equals sequential counting`() {
        assertTrue("需要真实词表", TokenizerRegistry.installFromClasspath())
        val bpe = TokenizerRegistry.current ?: error("tokenizer not installed")

        val texts = (0 until 64).map { index ->
            buildString {
                repeat(20) { append("第${index}段 with ascii words, numbers $it and punctuation (x, y). ") }
            }
        }
        val expected = texts.map { bpe.count(it) }

        val pool = Executors.newFixedThreadPool(6)
        try {
            // 同一批文本并发跑多轮：既验证结果一致，也验证共享片段缓存在并发下不自相矛盾
            repeat(3) {
                val futures = texts.map { text -> pool.submit(Callable { bpe.count(text) }) }
                assertEquals(expected, futures.map { it.get(10, TimeUnit.SECONDS) })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent counting matches registry counts`() {
        assertTrue("需要真实词表", TokenizerRegistry.installFromClasspath())
        val texts = List(32) { index -> "token counting under concurrency $index ".repeat(30) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = texts.map { text ->
                pool.submit(Callable { TokenizerRegistry.count(text) })
            }
            val parallel = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(texts.map { TokenizerRegistry.count(it) }, parallel)
        } finally {
            pool.shutdownNow()
        }
    }
}
