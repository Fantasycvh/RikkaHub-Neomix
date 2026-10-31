package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AptSourcesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var linuxDir: File

    /** Ubuntu 24.04 arm64 base 镜像的 deb822 格式源（sources.list 只剩搬家注释） */
    private val deb822 = """
        Types: deb
        URIs: http://ports.ubuntu.com/ubuntu-ports
        Suites: noble noble-updates noble-backports
        Components: main restricted universe multiverse

        Types: deb
        URIs: http://ports.ubuntu.com/ubuntu-ports
        Suites: noble-security
        Components: main restricted universe multiverse
    """.trimIndent()

    /** one-line 格式（amd64 老式源） */
    private val oneLine = "deb http://archive.ubuntu.com/ubuntu noble main restricted\n"

    private fun writeSources(vararg contents: String) {
        linuxDir = tempFolder.newFolder("linux")
        val aptDir = File(linuxDir, "etc/apt/sources.list.d").apply { mkdirs() }
        File(File(linuxDir, "etc/apt"), "sources.list").writeText(
            "# Ubuntu sources have moved to the ubuntu.sources file\n"
        )
        contents.forEachIndexed { i, c -> File(aptDir, "ubuntu-$i.sources").writeText(c) }
    }

    @Test
    fun `rewrite replaces official hosts in both deb822 and one-line files`() {
        writeSources(deb822, oneLine)

        assertTrue(AptSources.rewriteTo(linuxDir, AptSources.DEFAULT_MIRROR, explicit = false))

        val rewritten = File(linuxDir, "etc/apt/sources.list.d/ubuntu-0.sources").readText()
        assertTrue(rewritten.contains("http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"))
        assertFalse(rewritten.contains("ports.ubuntu.com"))
        // one-line 的 ubuntu 池目录要原样保留，换错目录 apt 会直接找不到包
        assertTrue(
            File(linuxDir, "etc/apt/sources.list.d/ubuntu-1.sources").readText()
                .contains("http://mirrors.tuna.tsinghua.edu.cn/ubuntu noble")
        )
        // 首次改写留下官方原始备份
        assertTrue(File(linuxDir, "etc/apt/sources.list.d/ubuntu-0.sources.orig").isFile)
        assertEquals(AptSources.DEFAULT_MIRROR, AptSources.appliedMirror(linuxDir))
        assertFalse(AptSources.isExplicit(linuxDir))
    }

    @Test
    fun `rewrite replays from orig backup instead of stacking mirrors`() {
        writeSources(deb822)
        AptSources.rewriteTo(linuxDir, AptSources.DEFAULT_MIRROR, explicit = false)
        val aliyun = "http://mirrors.aliyun.com"

        assertTrue(AptSources.rewriteTo(linuxDir, aliyun, explicit = true))

        val rewritten = File(linuxDir, "etc/apt/sources.list.d/ubuntu-0.sources").readText()
        assertTrue(rewritten.contains("$aliyun/ubuntu-ports"))
        // 换站是「从官方源重新改一次」：不残留上一家的地址
        assertFalse(rewritten.contains("tuna"))
        assertTrue(AptSources.isExplicit(linuxDir))
    }

    @Test
    fun `rewrite is idempotent for same target`() {
        writeSources(deb822)
        AptSources.rewriteTo(linuxDir, AptSources.DEFAULT_MIRROR, explicit = false)
        val before = File(linuxDir, "etc/apt/sources.list.d/ubuntu-0.sources").lastModified()

        assertFalse(AptSources.rewriteTo(linuxDir, AptSources.DEFAULT_MIRROR, explicit = false))
        assertEquals(
            before,
            File(linuxDir, "etc/apt/sources.list.d/ubuntu-0.sources").lastModified()
        )
    }

    @Test
    fun `comment lines keep official urls`() {
        linuxDir = tempFolder.newFolder("linux")
        val aptDir = File(linuxDir, "etc/apt/sources.list.d").apply { mkdirs() }
        File(aptDir, "extra.sources").writeText(
            "# deb http://archive.ubuntu.com/ubuntu noble main\n" +
                "deb http://archive.ubuntu.com/ubuntu noble universe\n"
        )

        AptSources.rewriteTo(linuxDir, AptSources.DEFAULT_MIRROR, explicit = true)

        val rewritten = File(aptDir, "extra.sources").readText()
        // 注释行保持原样：避免把示例里的官方地址当成生效源
        assertTrue(rewritten.contains("# deb http://archive.ubuntu.com/ubuntu noble main"))
        assertTrue(rewritten.contains("deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu noble universe"))
    }

    @Test
    fun `usesPortsRepo and detectSuite follow source content`() {
        writeSources(deb822)
        assertTrue(AptSources.usesPortsRepo(linuxDir))
        assertEquals("noble", AptSources.detectSuite(linuxDir, "fallback"))

        val amd64Dir = tempFolder.newFolder("linux-amd64")
        File(File(amd64Dir, "etc/apt"), "sources.list").apply { parentFile.mkdirs() }.writeText(oneLine)
        assertFalse(AptSources.usesPortsRepo(amd64Dir))

        val emptyDir = tempFolder.newFolder("linux-empty")
        File(File(emptyDir, "etc/apt"), "sources.list").apply { parentFile.mkdirs() }.writeText("\n")
        // 全空时按非 amd64 处理（arm64 设备是主流），测速路径宁可用 ports
        assertTrue(AptSources.usesPortsRepo(emptyDir))
        assertEquals("fallback", AptSources.detectSuite(emptyDir, "fallback"))
    }

    @Test
    fun `no marker when never rewritten`() {
        writeSources(deb822)
        assertNull(AptSources.appliedMirror(linuxDir))
        assertFalse(AptSources.isExplicit(linuxDir))
    }
}
