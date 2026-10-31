package app.androidrik.workspace

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsCatalogTest {

    @Test
    fun `abi maps to ubuntu arch slugs`() {
        assertEquals("arm64", RootfsCatalog.archSlugForAbi("arm64-v8a"))
        assertEquals("amd64", RootfsCatalog.archSlugForAbi("x86_64"))
        assertNull(RootfsCatalog.archSlugForAbi("armeabi-v7a"))
        assertNull(RootfsCatalog.fileNameForAbi("x86"))
    }

    @Test
    fun `file name contains release and arch`() {
        assertEquals(
            "ubuntu-base-${RootfsCatalog.UBUNTU_NOBLE_RELEASE}-base-arm64.tar.gz",
            RootfsCatalog.fileNameForAbi("arm64-v8a"),
        )
    }

    @Test
    fun `official mirror is first and provides default url`() {
        val url = RootfsCatalog.defaultUrlForAbi("arm64-v8a")!!
        assertTrue(url.startsWith("https://cdimage.ubuntu.com/"))
        assertTrue(url.endsWith(RootfsCatalog.fileNameForAbi("arm64-v8a")!!))
    }

    @Test
    fun `mirror urls are unique and https`() {
        val urls = RootfsCatalog.MIRRORS.map { it.urlFor("x") }
        assertEquals(urls.size, urls.toSet().size)
        assertTrue(urls.all { it.startsWith("https://") })
    }

    @Test
    fun `package mirror setup serializes round trip and reports active`() {
        val empty = PackageMirrorSetup()
        assertFalse(empty.active)
        val restored = Json.decodeFromString(
            PackageMirrorSetup.serializer(),
            Json.encodeToString(PackageMirrorSetup.serializer(), empty),
        )
        assertEquals(empty, restored)

        val configured = PackageMirrorSetup(configuredAt = 1, aptRepoUrl = "http://x/ubuntu")
        assertTrue(configured.active)
    }

    @Test
    fun `apt site picks pool by architecture`() {
        val site = PackageMirrorCatalog.APT_SITES.first()
        assertEquals("http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", site.repoUrl(ports = true))
        assertEquals("http://mirrors.tuna.tsinghua.edu.cn/ubuntu", site.repoUrl(ports = false))
        // 测速路径要拼到真实存在的 Release 文件
        val mirror = site.site(ports = true, suite = "noble")
        assertEquals(
            "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble/Release",
            mirror.probeUrl,
        )
    }
}
