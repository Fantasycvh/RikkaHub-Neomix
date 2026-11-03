package app.androidrik.workspace.ssh

import com.jcraft.jsch.HostKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * normalizeSshHost 的 IDN 归一化行为: ASCII 恒等、幂等、非法回落,
 * 以及连接路径与指纹路径共用同一归一化键。
 */
class SshIdnNormalizeTest {

    @Test
    fun unicodeHostIsConvertedToPunycode() {
        assertEquals("xn--kkrv96l5zbusc.top", normalizeSshHost("金陵十钗.top"))
    }

    @Test
    fun asciiHostsPassThroughByteIdentical() {
        // 大小写、点、数字都不动 —— 快速路径连 IDN.toASCII 都不进
        assertEquals("Example.COM", normalizeSshHost("Example.COM"))
        assertEquals("frp-dry.com", normalizeSshHost("frp-dry.com"))
        assertEquals("10.10.10.2", normalizeSshHost("10.10.10.2"))
        assertEquals("", normalizeSshHost(""))
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = normalizeSshHost("金陵十钗.top")
        assertEquals(once, normalizeSshHost(once))
    }

    @Test
    fun invalidIdnFallsBackToOriginal() {
        // 空标签 ("..") 会让 IDN.toASCII 抛 IllegalArgumentException, 必须回落原串,
        // 不新增异常面 —— JSch 会报和今天一样的错误
        val bad = "金陵..十钗.top"
        assertEquals(bad, normalizeSshHost(bad))
    }

    @Test
    fun fingerprintKeyFollowsNormalizedHost() {
        // 两个不同原始写法 —— 中文与 punycode —— 归一化后必须是同一个字符串,
        // 这样 getSession 的 session host 与 TrustedHostKeyRepository 的指纹键同源
        val normalizedFromChinese = normalizeSshHost("金陵十钗.top")
        val normalizedFromPunycode = normalizeSshHost("xn--kkrv96l5zbusc.top")
        assertEquals("xn--kkrv96l5zbusc.top", normalizedFromChinese)
        assertEquals(normalizedFromPunycode, normalizedFromChinese)

        // HostKey (指纹键) 构造所用的 host 串 = 归一化结果; blob 为真实
        // ssh-ed25519 公钥, 与 SshHostKey(Base64 blob) 的存法一致
        val key = Base64.getDecoder().decode(
            "AAAAC3NzaC1lZDI1NTE5AAAAIFtcnyoTO0iVGNTXKCsqYTMNwUUy54HsT1a1b4L0KQZx",
        )
        val hostKey = HostKey(normalizedFromChinese, key)
        assertEquals("xn--kkrv96l5zbusc.top", hostKey.host)
        assertEquals("ssh-ed25519", hostKey.type)
    }
}
