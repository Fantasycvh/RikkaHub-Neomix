package app.androidrik.common.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class TokenizerRegistryCacheTest {
    @Before
    fun reset() = TokenizerRegistry.release()

    @After
    fun cleanup() = TokenizerRegistry.release()

    @Test
    fun `release resets stats and a different tokenizer cannot reuse old counts`() {
        assertNull(TokenizerRegistry.count("ab"))
        assertEquals(TokenCountCacheStats(0, 0, 0, 0), TokenizerRegistry.cacheStats())
        assertTrue(TokenizerRegistry.install(vocabulary("a", "b", "ab")))
        val previous = TokenizerRegistry.current
        assertEquals(1, TokenizerRegistry.count("ab"))
        assertEquals(1, TokenizerRegistry.count("ab"))
        assertEquals(TokenCountCacheStats(1, 1, 1, 4), TokenizerRegistry.cacheStats())
        TokenizerRegistry.release()
        assertFalse(TokenizerRegistry.isReady)
        assertNull(TokenizerRegistry.count("ab"))
        assertEquals(TokenCountCacheStats(0, 0, 0, 0), TokenizerRegistry.cacheStats())
        assertTrue(TokenizerRegistry.install(vocabulary("a", "b")))
        assertNotSame(previous, TokenizerRegistry.current)
        assertEquals(2, TokenizerRegistry.count("ab"))
        assertEquals(TokenCountCacheStats(0, 1, 1, 4), TokenizerRegistry.cacheStats())
    }

    @Test
    fun `cached and uncached counts match every exact reference vector`() {
        assertTrue(TokenizerRegistry.installFromClasspath())
        val vectors = javaClass.getResourceAsStream("/tokenizer/cl100k_reference.json")!!.use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonArray
        }
        vectors.forEach { entry ->
            val vector = entry.jsonObject
            val text = vector["text"]!!.jsonPrimitive.content
            val expected = vector["count"]!!.jsonPrimitive.content.toInt()
            assertEquals(expected, TokenizerRegistry.count(text))
            assertEquals(expected, TokenizerRegistry.count(text))
        }
        assertTrue(TokenizerRegistry.cacheStats().hits > 0)
    }

    @Test
    fun `count release and reinstall may race without mixing tokenizer results`() {
        val executor = Executors.newFixedThreadPool(5)
        try {
            assertTrue(TokenizerRegistry.install(vocabulary("a", "b", "ab")))
            val readers = (0 until 4).map {
                executor.submit(Callable {
                    repeat(1000) {
                        val result = TokenizerRegistry.count("ab")
                        assertTrue(result == null || result == 1 || result == 2)
                        val stats = TokenizerRegistry.cacheStats()
                        assertTrue(stats.entries in 0..1)
                        assertTrue(stats.retainedTextBytes == 0L || stats.retainedTextBytes == 4L)
                    }
                })
            }
            val switcher = executor.submit(Callable {
                repeat(50) { generation ->
                    TokenizerRegistry.release()
                    assertTrue(TokenizerRegistry.install(
                        if (generation % 2 == 0) vocabulary("a", "b") else vocabulary("a", "b", "ab")
                    ))
                }
            })
            readers.forEach { it.get(15, TimeUnit.SECONDS) }
            switcher.get(15, TimeUnit.SECONDS)
            TokenizerRegistry.release()
            assertTrue(TokenizerRegistry.install(vocabulary("a", "b")))
            assertEquals(2, TokenizerRegistry.count("ab"))
            assertEquals(2, TokenizerRegistry.count("ab"))
            assertEquals(TokenCountCacheStats(1, 1, 1, 4), TokenizerRegistry.cacheStats())
        } finally {
            executor.shutdownNow()
        }
    }

    /** Tiny gz vocabulary: varint byte length followed by token bytes, in rank order. */
    private fun vocabulary(vararg tokens: String) = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { gzip ->
            tokens.forEach { token ->
                val bytes = token.toByteArray(Charsets.UTF_8)
                require(bytes.size < 128)
                gzip.write(bytes.size)
                gzip.write(bytes)
            }
        }
    }.toByteArray().inputStream()
}
