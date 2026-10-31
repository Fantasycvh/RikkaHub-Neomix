package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.workspace.PackageMirrorSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageMirrorPromptTest {

    @Test
    fun `empty setup produces no prompt`() {
        assertEquals("", buildPackageMirrorPrompt(PackageMirrorSetup()))
    }

    @Test
    fun `configured setup lists active managers and fallback redirects`() {
        val setup = PackageMirrorSetup(
            aptRepoUrl = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            npmRegistry = "https://registry.npmmirror.com/",
            pipIndexUrl = "https://mirrors.aliyun.com/pypi/simple/",
            goProxy = "https://goproxy.cn",
        )
        val prompt = buildPackageMirrorPrompt(setup)
        assertTrue(prompt.startsWith("\n"))
        assertTrue(prompt.contains("<package_mirrors>"))
        assertTrue(prompt.contains(setup.aptRepoUrl))
        assertTrue(prompt.contains(setup.npmRegistry))
        assertTrue(prompt.contains(setup.pipIndexUrl))
        assertTrue(prompt.contains("GOPROXY=${setup.goProxy},direct"))
        // 备选 apt 站要排除当前已生效的主机
        assertFalse(prompt.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-ports (other"))
        assertTrue(prompt.contains("mirrors.aliyun.com"))
    }

    @Test
    fun `other apt mirrors excludes current host`() {
        val setup = PackageMirrorSetup(aptRepoUrl = "http://mirrors.aliyun.com/ubuntu")
        val prompt = buildPackageMirrorPrompt(setup)
        assertFalse(prompt.contains("http://mirrors.aliyun.com, "))
        assertTrue(prompt.contains("http://mirrors.tuna.tsinghua.edu.cn"))
    }
}
