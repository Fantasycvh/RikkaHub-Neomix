package app.androidrik.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区导出到 cache 的文件名必须**按路径唯一**（上游 issue #2126）。
 *
 * ## 为什么值得钉
 *
 * 预览/分享图片时文件先进 cache 再交给 Coil / FileProvider 消费。原来用的是
 * `File(dir, entry.name)`——**只用文件名**，而工作区是目录树：`images/a.png` 与
 * `backup/a.png` 会落到同一个 cache 路径互相覆盖。Coil 以**绝对路径**为缓存键，
 * 路径没变就直接命中上一次的字节 ⇒ 报告里那条「预览第二张同名图片，看到的是第一张的
 * 内容」。
 *
 * 这类缺陷没有任何报错、只在**两张同名图片先后被预览**时才显形，所以把判据抽出来钉住。
 * 同一份判据现在被 `exportToCacheFile`（预览/分享/系统打开）与 `exportToCacheDir`
 * （列表内 SVG 缩略图）共用——本仓踩过「两处各维护一份、改一处忘一处」的学费。
 */
class WorkspaceCacheNamingTest {

    @Test
    fun `same file name in different directories does not collide`() {
        val first = workspaceCacheFileName("images/a.png")
        val second = workspaceCacheFileName("backup/a.png")

        assertNotEquals(
            "不同子目录下的同名文件必须导出成不同文件名，否则 cache 里互相覆盖，" +
                "Coil 按绝对路径命中旧字节 ⇒ 预览串图（#2126）",
            first,
            second,
        )
        assertEquals("images_a.png", first)
        assertEquals("backup_a.png", second)
    }

    @Test
    fun `extension survives so mime and type detection still work`() {
        // 扩展名被弄丢会让「点开」变成交给系统应用打开一个没有类型的东西
        assertTrue(
            "扩展名必须保留（消费侧靠它推 MIME / 判类型）",
            workspaceCacheFileName("deep/nested/photo.PNG").endsWith(".PNG"),
        )
    }

    @Test
    fun `top level file keeps its bare name`() {
        assertEquals(
            "顶层文件没有目录前缀，文件名应保持原样（不动无碰撞的情况）",
            "a.png",
            workspaceCacheFileName("a.png"),
        )
    }

    @Test
    fun `leading and trailing slashes are trimmed`() {
        // 工作区路径有时带前导 '/'（见 WorkspaceFileEntry.path 的两种形态）
        assertEquals("a.png", workspaceCacheFileName("/a.png"))
        assertEquals("images_a.png", workspaceCacheFileName("/images/a.png/"))
    }

    @Test
    fun `directory-only path cannot escape into a parent directory`() {
        // replace 之后不应再含分隔符：含 '/' 的路径会写到 dir 之外（或直接建目录失败）
        val name = workspaceCacheFileName("a/b/c.txt")

        assertEquals("a_b_c.txt", name)
        assertTrue("结果里不许再出现 '/'", !name.contains('/'))
    }
}
