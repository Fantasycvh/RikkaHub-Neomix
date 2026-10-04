package app.androidrik.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `os-release` 解析（对齐上游 `298e2771`）：发行版名要拼进**系统提示**，而该文件在 Rootfs 内
 * **外部可写** ⇒ 净化与截断是必须的（否则可塞换行/控制字符搅乱提示词结构）。
 */
class OsReleaseNameTest {

    @Test
    fun `prefers pretty name`() {
        val content = """
            NAME="Ubuntu"
            VERSION="24.04.1 LTS (Noble Numbat)"
            ID=ubuntu
            PRETTY_NAME="Ubuntu 24.04.1 LTS"
            VERSION_ID="24.04"
        """.trimIndent()
        assertEquals("Ubuntu 24.04.1 LTS", parseOsReleaseName(content))
    }

    @Test
    fun `falls back to name plus version id`() {
        val content = "NAME=Alpine Linux\nID=alpine\nVERSION_ID=3.20.3\n"
        assertEquals("Alpine Linux 3.20.3", parseOsReleaseName(content))
    }

    @Test
    fun `tolerates comments, quotes, whitespace and blank lines`() {
        val content = """
            # This is /etc/os-release
            PRETTY_NAME = 'Debian GNU/Linux 12 (bookworm)'

            ID=debian
        """.trimIndent()
        assertEquals("Debian GNU/Linux 12 (bookworm)", parseOsReleaseName(content))
    }

    @Test
    fun `strips control characters that do not break the line`() {
        // Rootfs 内的文件可被用户/工具改写，值里可能塞控制字符；它们进提示词会搅乱结构。
        // 用**真字符**拼接（写成 "\t" 字面量则测的其实是反斜杠，净化逻辑不会被触发）。
        val injected = "Evil" + '\t' + "with" + '\u0000' + "control"
        assertEquals("Evilwithcontrol", parseOsReleaseName("PRETTY_NAME=\"$injected\"\n"))
    }

    @Test
    fun `a newline inside the value truncates it but cannot inject structure`() {
        // 逐行解析把换行当行尾 ⇒ 换行本身进不了提示词；但值会被截断且引号不成对，
        // 兜底 trim 必须把那个孤引号吃掉（否则提示词里出现 `"Evil`）
        assertEquals("Evil", parseOsReleaseName("PRETTY_NAME=\"Evil\nDistribution\"\n"))
    }

    @Test
    fun `caps length so a hostile file cannot flood the prompt`() {
        val long = "A".repeat(500)
        val parsed = parseOsReleaseName("PRETTY_NAME=$long\n")
        assertEquals(80, parsed?.length)
    }

    @Test
    fun `returns null when nothing usable is found`() {
        assertNull(parseOsReleaseName(""))
        assertNull(parseOsReleaseName("PRETTY_NAME=\n"))
        assertNull(parseOsReleaseName("# only a comment\n"))
        assertNull(parseOsReleaseName("PRETTY_NAME=\"   \"\n"))
    }
}
