package app.androidrik.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavClientTest {

    @Test
    fun `encode path segment keeps plain ascii unchanged`() {
        assertEquals("backup_20260814.zip", encodeWebDavPathSegment("backup_20260814.zip"))
    }

    @Test
    fun `encode path segment percent-encodes space as percent20`() {
        assertEquals("my%20backup.zip", encodeWebDavPathSegment("my backup.zip"))
    }

    @Test
    fun `encode path segment encodes chinese and url special chars`() {
        assertEquals("%E6%B5%8B%E8%AF%95", encodeWebDavPathSegment("测试"))
        assertEquals("a%23b%3Fc%26d", encodeWebDavPathSegment("a#b?c&d"))
    }
}
