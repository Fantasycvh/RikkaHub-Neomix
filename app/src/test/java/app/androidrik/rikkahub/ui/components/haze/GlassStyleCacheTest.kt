package app.androidrik.rikkahub.ui.components.haze

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GlassStyleCacheTest {

    @Test
    fun `cache reuses same instance for identical blur key`() {
        val cache = BoundedEffectCache<BlurStyleCacheKey, Any>(maxEntries = 4)
        var createdCount = 0
        val keyA = BlurStyleCacheKey.of(Color.White, blurRadiusDp = 16f, darkMode = false)
        val keyB = BlurStyleCacheKey.of(Color.White, blurRadiusDp = 16f, darkMode = false)

        val first = cache.getOrPut(keyA) { createdCount++; Any() }
        val second = cache.getOrPut(keyB) { createdCount++; Any() }

        assertSame(first, second)
        assertEquals(1, createdCount)
        assertEquals(1, cache.size)
    }

    @Test
    fun `cache key distinguishes theme mode radius preset and corner shape`() {
        val lightBlur = BlurStyleCacheKey.of(Color.White, blurRadiusDp = 16f, darkMode = false)
        val darkBlur = BlurStyleCacheKey.of(Color.White, blurRadiusDp = 16f, darkMode = true)
        val smallRadiusBlur = BlurStyleCacheKey.of(Color.White, blurRadiusDp = 12f, darkMode = false)
        assertNotEquals(lightBlur, darkBlur)
        assertNotEquals(lightBlur, smallRadiusBlur)

        val standard = GlassStyleCacheKey.of(
            preset = GlassStylePreset.STANDARD,
            containerColor = Color.White,
            tintAlpha = 0.3f,
            darkMode = false,
            readabilityMode = LiquidGlassReadabilityMode.AUTO,
            cornerShape = RoundedCornerShape(16.dp),
        )
        val sameStandard = GlassStyleCacheKey.of(
            preset = GlassStylePreset.STANDARD,
            containerColor = Color.White,
            tintAlpha = 0.3f,
            darkMode = false,
            readabilityMode = LiquidGlassReadabilityMode.AUTO,
            cornerShape = RoundedCornerShape(16.dp),
        )
        val capsule = standard.copy(preset = GlassStylePreset.CAPSULE)
        val differentCorner = standard.copy(cornerShape = RoundedCornerShape(24.dp))
        val nullCorner = standard.copy(cornerShape = null)

        assertEquals(standard, sameStandard)
        assertNotEquals(standard, capsule)
        assertNotEquals(standard, differentCorner)
        assertNotEquals(standard, nullCorner)
    }

    @Test
    fun `bounded cache evicts least recently used entry and supports clear`() {
        val cache = BoundedEffectCache<String, String>(maxEntries = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        // 访问 "a" 使其刷新为最近使用，随后插入 "c" 应淘汰 "b"
        assertEquals("A", cache.get("a"))
        cache.put("c", "C")

        assertEquals(2, cache.size)
        assertEquals("A", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("C", cache.get("c"))

        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("a"))
    }
}
