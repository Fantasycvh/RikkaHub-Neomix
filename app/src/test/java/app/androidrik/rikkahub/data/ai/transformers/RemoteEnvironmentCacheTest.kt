package app.androidrik.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteEnvironmentCacheTest {
    private var now = 0L

    private fun cache(failureTtlMillis: Long = 60_000L) = RemoteEnvironmentCache(
        failureTtlMillis = failureTtlMillis,
        now = { now },
    )

    @Test
    fun `successful environment is cached until config changes`() = runTest {
        var loads = 0
        val cache = cache()
        suspend fun load(): RemoteEnvironment {
            loads++
            return RemoteEnvironment(shell = "/bin/bash", distro = "Ubuntu 24.04")
        }

        val first = cache.get("ws", "config-a") { load() }
        now += 10_000_000L
        val second = cache.get("ws", "config-a") { load() }
        val changed = cache.get("ws", "config-b") { load() }

        assertEquals(RemoteEnvironment("/bin/bash", "Ubuntu 24.04"), first.getOrThrow())
        assertEquals(first, second)
        assertEquals(2, loads)
        assertEquals(first.getOrThrow(), changed.getOrThrow())
    }

    @Test
    fun `failure is reused within ttl then retried`() = runTest {
        val cache = cache(failureTtlMillis = 60_000L)
        var loads = 0
        suspend fun load(): RemoteEnvironment {
            loads++
            if (loads == 1) error("unreachable")
            return RemoteEnvironment("/bin/sh", null)
        }

        val first = cache.get("ws", null) { load() }
        now = 59_999L
        val cachedFailure = cache.get("ws", null) { load() }
        now = 60_000L
        val retried = cache.get("ws", null) { load() }

        assertTrue(first.isFailure)
        assertEquals(first, cachedFailure)
        assertEquals(2, loads)
        assertEquals("/bin/sh", retried.getOrThrow().shell)
    }

    @Test
    fun `cancellation is not cached`() = runTest {
        val cache = cache()
        try {
            cache.get("ws", null) {
                throw CancellationException("cancelled")
            }
            fail("CancellationException should propagate")
        } catch (expected: CancellationException) {
            // expected
        }

        val recovered = cache.get("ws", null) { RemoteEnvironment("/bin/bash", null) }
        assertEquals("/bin/bash", recovered.getOrThrow().shell)
    }
}
