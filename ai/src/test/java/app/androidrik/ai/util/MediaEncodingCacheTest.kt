package app.androidrik.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 媒体编码缓存的字节预算/LRU 语义（移植自 Yuihub 3.0.0 `MediaEncodingCacheTest`，
 * design-tool-context-gate §7）。接入行为（encodeBase64 命中/失效）由 key 结构保证：
 * 含 path:length:lastModified，文件更新自动换 key，无需失效钩子。
 */
class MediaEncodingCacheTest {
    @Test
    fun `cached image preserves gif mime type`() {
        val cache = MediaEncodingCache()
        val image = EncodedImage("R0lGODlh", "image/gif")
        cache.put("gif", image)
        assertEquals(image, cache.get("gif"))
    }

    @Test
    fun `cache evicts least recently used entry by byte budget`() {
        val cache = MediaEncodingCache(maxBytes = 64, maxEntryBytes = 64)
        val value = EncodedImage("1234567890", "m")
        cache.put("a", value)
        cache.put("b", value)
        cache.get("a")
        cache.put("c", value)
        assertNull(cache.get("b"))
        assertEquals(value, cache.get("a"))
        assertEquals(value, cache.get("c"))
        assertEquals(48, cache.sizeBytes)
    }

    @Test
    fun `oversized values are not retained`() {
        val cache = MediaEncodingCache(maxBytes = 64, maxEntryBytes = 16)
        cache.put("large", EncodedImage("1234567890", "m"))
        assertNull(cache.get("large"))
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun `replacement and memory trim release retained bytes`() {
        val cache = MediaEncodingCache()
        cache.put("key", EncodedImage("1234567890", "image/gif"))
        cache.put("key", EncodedImage("1", "m"))
        assertEquals(10, cache.sizeBytes)
        cache.clear()
        assertNull(cache.get("key"))
        assertEquals(0, cache.sizeBytes)
    }
}
