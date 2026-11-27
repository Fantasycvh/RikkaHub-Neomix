package app.androidrik.rikkahub.utils

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class BackgroundMemoryTrimPolicyTest {

    @Test
    fun `background image cache budget steps down after delay`() {
        val bigCache = 120L * 1024 * 1024
        val midCache = 48L * 1024 * 1024
        val smallCache = 16L * 1024 * 1024

        // 刚进后台与延迟门槛之前：轻度上限 64 MiB
        assertEquals(
            BACKGROUND_IMAGE_CACHE_LIGHT_MAX_BYTES,
            resolveBackgroundImageCacheTrimTargetBytes(bigCache, backgroundElapsedMs = 0L),
        )
        assertEquals(
            midCache,
            resolveBackgroundImageCacheTrimTargetBytes(midCache, backgroundElapsedMs = BACKGROUND_IMAGE_TRIM_DELAY_MS - 1L),
        )

        // 后台持续达到 45s 后：收敛到 32 MiB
        assertEquals(
            BACKGROUND_IMAGE_CACHE_DEEP_MAX_BYTES,
            resolveBackgroundImageCacheTrimTargetBytes(bigCache, backgroundElapsedMs = BACKGROUND_IMAGE_TRIM_DELAY_MS),
        )
        assertEquals(
            BACKGROUND_IMAGE_CACHE_DEEP_MAX_BYTES,
            resolveBackgroundImageCacheTrimTargetBytes(midCache, backgroundElapsedMs = BACKGROUND_IMAGE_TRIM_DELAY_MS + 10_000L),
        )
        assertEquals(
            smallCache,
            resolveBackgroundImageCacheTrimTargetBytes(smallCache, backgroundElapsedMs = BACKGROUND_IMAGE_TRIM_DELAY_MS),
        )
        assertEquals(0L, resolveBackgroundImageCacheTrimTargetBytes(-1024L, backgroundElapsedMs = 0L))
    }

    @Test
    fun `ui hidden and running moderate do not aggressively evict`() {
        val uiHidden = resolveMemoryTrimPlan(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(uiHidden.evictDerivedCaches)
        assertFalse(uiHidden.evictIdleConnections)

        val moderate = resolveMemoryTrimPlan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertFalse(moderate.evictDerivedCaches)
        assertFalse(moderate.evictIdleConnections)
    }

    @Test
    fun `running low and critical escalate progressively`() {
        val low = resolveMemoryTrimPlan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertFalse(low.evictDerivedCaches)
        assertFalse(low.evictIdleConnections)

        val critical = resolveMemoryTrimPlan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertTrue(critical.evictDerivedCaches)
        assertTrue(critical.evictIdleConnections)
    }

    @Test
    fun `background and complete trim levels release derived resources`() {
        val levels = listOf(
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        levels.forEach { level ->
            val plan = resolveMemoryTrimPlan(level)
            assertTrue("level=$level", plan.evictDerivedCaches)
            assertTrue("level=$level", plan.evictIdleConnections)
        }
    }
}
