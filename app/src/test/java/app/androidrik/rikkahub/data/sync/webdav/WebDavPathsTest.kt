package app.androidrik.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * WebDavPaths 纯函数的单测：拆段 / 编码拼接 / 逐级前缀。
 * 这些函数没有 Android 依赖，所以可以在 JVM 单测里直接跑。
 */
class WebDavPathsTest {

    @Test
    fun `multi level config path and relative paths are split in order`() {
        assertEquals(
            listOf("neomix_backups", "v3", "objects", "aa", "obj.bin"),
            webDavPathSegments("neomix_backups/v3", listOf("objects/aa", "obj.bin")),
        )
    }

    @Test
    fun `only leading and trailing slashes are trimmed while spaces are preserved`() {
        assertEquals(
            listOf("my backups", "backup 2026.zip"),
            webDavPathSegments("/my backups/", listOf("/backup 2026.zip/")),
        )
        // 首尾空格是远端名字的一部分，不能 trim（trim 掉会指向另一个对象）
        assertEquals(listOf(" a "), webDavPathSegments(" a ", emptyList()))
    }

    @Test
    fun `empty segments are dropped and null config path is ignored`() {
        assertEquals(listOf("a", "b", "c"), webDavPathSegments("a//b", listOf("", "/", "c/")))
        assertEquals(listOf("x"), webDavPathSegments(null, listOf("x")))
        assertEquals(emptyList<String>(), webDavPathSegments(null, emptyList()))
        assertEquals(emptyList<String>(), webDavPathSegments("/", listOf("//")))
    }

    @Test
    fun `url joins segments with single slashes`() {
        assertEquals(
            "https://dav.example.com/remote/neomix_backups/v3/objects/aa",
            buildWebDavUrl(
                "https://dav.example.com/remote/",
                webDavPathSegments("neomix_backups/v3", listOf("objects/aa")),
            ),
        )
    }

    @Test
    fun `url encodes space chinese and url special chars per segment`() {
        assertEquals(
            "https://dav.example.com/dav/my%20backups/%E6%B5%8B%E8%AF%95/a%23b%3Fc%26d",
            buildWebDavUrl("https://dav.example.com/dav", listOf("my backups", "测试", "a#b?c&d")),
        )
    }

    @Test
    fun `url with no segments is the base without trailing slash`() {
        assertEquals(
            "https://dav.example.com/dav",
            buildWebDavUrl("https://dav.example.com/dav/", emptyList()),
        )
    }

    @Test
    fun `multi level separator is never percent encoded`() {
        val url = buildWebDavUrl("https://dav.example.com", webDavPathSegments("v3/objects/aa", emptyList()))

        assertEquals("https://dav.example.com/v3/objects/aa", url)
        assertFalse(url.contains("%2F"))
    }

    @Test
    fun `single level path behaves exactly like before`() {
        // 回归护栏：单级 config.path + 单级文件名是旧实现唯一支持的形态，结果必须逐字节不变
        assertEquals("backup_20260814.zip", encodeWebDavPathSegment("backup_20260814.zip"))
        assertEquals("my%20backup.zip", encodeWebDavPathSegment("my backup.zip"))
        assertEquals(
            "https://dav.example.com/dav/neomix_backups/backup_20260814.zip",
            buildWebDavUrl(
                "https://dav.example.com/dav",
                webDavPathSegments("neomix_backups", listOf("backup_20260814.zip")),
            ),
        )
    }

    @Test
    fun `cumulative segments list every prefix in order`() {
        assertEquals(
            listOf(listOf("a"), listOf("a", "b"), listOf("a", "b", "c")),
            cumulativeSegments(listOf("a", "b", "c")),
        )
        assertEquals(emptyList<List<String>>(), cumulativeSegments(emptyList()))
    }
}
