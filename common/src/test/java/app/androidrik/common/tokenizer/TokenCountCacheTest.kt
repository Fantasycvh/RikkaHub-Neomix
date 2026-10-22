package app.androidrik.common.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TokenCountCacheTest {
    @Test
    fun `hit skips computation and snapshot is immutable`() {
        val cache = TokenCountCache()
        assertEquals(7, cache.count("hello") { 7 })
        val beforeHit = cache.snapshot()
        assertEquals(7, cache.count("hello") { error("must hit") })
        assertEquals(TokenCountCacheStats(0, 1, 1, 10), beforeHit)
        assertEquals(TokenCountCacheStats(1, 1, 1, 10), cache.snapshot())
    }

    @Test
    fun `entry limit evicts least recently accessed not inserted`() {
        val cache = TokenCountCache()
        repeat(512) { index -> cache.count("key-$index") { index } }
        assertEquals(0, cache.count("key-0") { error("must hit") })
        cache.count("new") { 1000 }
        assertEquals(512, cache.snapshot().entries)
        assertEquals(0, cache.count("key-0") { error("recent entry evicted") })
        var recomputed = false
        cache.count("key-1") { recomputed = true; 1 }
        assertTrue(recomputed)
    }

    @Test
    fun `UTF16 byte limit evicts LRU and includes surrogate pairs`() {
        val cache = TokenCountCache(maxRetainedTextBytes = 12)
        cache.count("a") { 1 }
        cache.count("\uD83D\uDE00") { 2 }
        assertEquals(6L, cache.snapshot().retainedTextBytes)
        cache.count("a") { error("must hit") }
        cache.count("bbbb") { 4 }
        assertEquals(TokenCountCacheStats(1, 3, 2, 10), cache.snapshot())
        cache.count("\uD83D\uDE00") { 2 }
        assertEquals(12L, cache.snapshot().retainedTextBytes)
        assertEquals(2, cache.snapshot().entries)
    }

    @Test
    fun `production byte budget is four MiB and per text boundary is inclusive`() {
        val cache = TokenCountCache()
        val text = "x".repeat(128 * 1024 - 2)
        repeat(16) { cache.count(text + it.toString().padStart(2, '0')) { it } }
        assertEquals(4L * 1024 * 1024, cache.snapshot().retainedTextBytes)
        assertEquals(16, cache.snapshot().entries)
        cache.count(text + "16") { 16 }
        assertEquals(4L * 1024 * 1024, cache.snapshot().retainedTextBytes)
        assertEquals(16, cache.snapshot().entries)
        assertEquals(1, cache.count(text + "01") { error("must hit") })
        var evicted = false
        cache.count(text + "00") { evicted = true; 0 }
        assertTrue(evicted)
    }

    @Test
    fun `oversized text and failed computations are not retained`() {
        val cache = TokenCountCache()
        val oversized = "x".repeat(128 * 1024 + 1)
        repeat(2) { assertEquals(42, cache.count(oversized) { 42 }) }
        assertNull(cache.count("failure") { null })
        assertNull(cache.count("failure") { null })
        assertEquals(TokenCountCacheStats(0, 4, 0, 0), cache.snapshot())
    }

    @Test
    fun `invalidation during computation prevents stale refill without blocking other keys`() {
        val cache = TokenCountCache()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldCount = executor.submit(Callable {
                cache.count("old") {
                    started.countDown()
                    assertTrue(finish.await(5, TimeUnit.SECONDS))
                    11
                }
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // A separate key, snapshot, and invalidation must not wait for slow tokenization.
            assertEquals(22, executor.submit(Callable { cache.count("other") { 22 } }).get(5, TimeUnit.SECONDS))
            cache.invalidate()
            assertEquals(TokenCountCacheStats(0, 0, 0, 0), cache.snapshot())
            finish.countDown()
            assertEquals(11, oldCount.get(5, TimeUnit.SECONDS))
            assertEquals(TokenCountCacheStats(0, 0, 0, 0), cache.snapshot())
            assertEquals(33, cache.count("after") { 33 })
            assertEquals(0, cache.snapshot().entries)
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent reads writes eviction and snapshots preserve accounting`() {
        val cache = TokenCountCache(maxEntries = 32, maxRetainedTextBytes = 256)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 8).map { worker ->
                executor.submit(Callable {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(1000) { iteration ->
                        val key = "${(iteration + worker * 31) % 100}"
                        assertEquals(key.length, cache.count(key) { key.length })
                        val stats = cache.snapshot()
                        assertTrue(stats.entries in 0..32)
                        assertTrue(stats.retainedTextBytes in 0L..256L)
                    }
                })
            }
            start.countDown()
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }
            val stats = cache.snapshot()
            assertEquals(8000L, stats.hits + stats.misses)
            assertTrue(stats.retainedTextBytes >= stats.entries * 2L)
            assertTrue(stats.retainedTextBytes <= stats.entries * 4L)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
