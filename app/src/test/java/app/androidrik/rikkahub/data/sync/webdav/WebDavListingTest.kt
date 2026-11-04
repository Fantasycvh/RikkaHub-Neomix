package app.androidrik.rikkahub.data.sync.webdav

import org.junit.Assert.*
import org.junit.Test

class WebDavListingTest {
    private val url = "https://example.test/dav/My%20Backup/v3/snapshots"
    private fun resource(href: String, directory: Boolean = false) = WebDavResourceInfo(href, "untrusted label", 1, "", null, directory)

    @Test fun parentCanAppearLastAndDisplayNameIsNotTheKey() {
        val list = directWebDavChildren(url, listOf(resource("/dav/My%20Backup/v3/snapshots/one.json.gz"), resource(url + "/", true)))
        assertEquals(listOf("one.json.gz"), list.map { it.displayName })
    }

    @Test fun relativeHrefsAndEncodedUnicodeAreResolved() {
        assertEquals("测试.json", directWebDavChildren(url, listOf(resource("%E6%B5%8B%E8%AF%95.json"))).single().displayName)
    }

    @Test fun unrelatedOrAmbiguousHrefsAreRejected() {
        listOf("https://other.test/dav/file", "/private/file", "child/nested", "%2e%2e", "slash%2fname", "bad%5cname", "name?query=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { directWebDavChildren(url, listOf(resource(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { directWebDavChildren(url, listOf(resource("same"), resource("%73ame"))) }
    }

    @Test fun emptyCollectionOnlyContainsParent() {
        assertTrue(directWebDavChildren(url, listOf(resource(url, true))).isEmpty())
    }
}
