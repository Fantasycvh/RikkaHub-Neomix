package app.androidrik.rikkahub.data.ai.account

import app.androidrik.ai.provider.OAuthCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 凭据落盘加密契约（P3）。
 *
 * 本仓与上游的一处**刻意偏离**：上游把 OAuth 凭据明文写进 settings JSON，本仓沿用旧
 * `CodexSubscriptionAccount` 的安全策略，用 `SecretCipher`（Android Keystore，v1: 前缀）
 * 加密 access/refresh token 再落盘；email/plan 是展示字段，保持明文。
 */
class CredentialCipherTest {
    /** 可逆的假 cipher：单测不需要 Android Keystore */
    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: String): String = "v1:enc($plaintext)"
        override fun decrypt(ciphertext: String): String = ciphertext.removePrefix("v1:enc(").removeSuffix(")")
    }

    private class BrokenCipher : CredentialCipher {
        override fun encrypt(plaintext: String): String = "v1:broken"
        override fun decrypt(ciphertext: String): String = error("cannot decrypt: ${ciphertext.take(8)}")
    }

    private val cipher = FakeCipher()
    private val credential = OAuthCredential(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresAt = 123L,
        accountId = "acct",
        email = "user@example.com",
        plan = "plus",
    )

    @Test
    fun `tokens are encrypted on persist and decrypted on read`() {
        val stored = credential.encrypted(cipher)

        assertEquals("v1:enc(access-token)", stored.accessToken)
        assertEquals("v1:enc(refresh-token)", stored.refreshToken)
        // 展示字段保持明文
        assertEquals("user@example.com", stored.email)
        assertEquals("plus", stored.plan)
        assertEquals("acct", stored.accountId)
        assertEquals(123L, stored.expiresAt)

        val roundTripped = stored.decrypted(cipher)
        assertEquals(credential.accessToken, roundTripped.accessToken)
        assertEquals(credential.refreshToken, roundTripped.refreshToken)
    }

    @Test
    fun `encrypting twice does not double wrap`() {
        val once = credential.encrypted(cipher)
        val twice = once.encrypted(cipher)
        assertEquals(once.accessToken, twice.accessToken)
    }

    @Test
    fun `plaintext credentials are passed through on read`() {
        // 导入/备份里可能带明文（上游格式）；没有 v1: 前缀就不解密
        val plain = OAuthCredential(accessToken = "plain-access", refreshToken = "plain-refresh")
        val read = plain.decrypted(cipher)
        assertEquals("plain-access", read.accessToken)
        assertEquals("plain-refresh", read.refreshToken)
    }

    @Test
    fun `undecryptable token becomes empty so user is asked to sign in again`() {
        val stored = OAuthCredential(accessToken = "v1:broken", refreshToken = null)
        val read = stored.decrypted(BrokenCipher())
        assertEquals("", read.accessToken)
        assertNull(read.refreshToken)
    }

    @Test
    fun `empty tokens stay empty`() {
        val empty = OAuthCredential(accessToken = "", refreshToken = null)
        val stored = empty.encrypted(cipher)
        assertEquals("", stored.accessToken)
        assertNull(stored.refreshToken)
        // 空字符串不应被当作密文解密
        assertEquals("", stored.decrypted(BrokenCipher()).accessToken)
    }

    @Test
    fun `encrypted differs from plaintext`() {
        assertNotEquals(credential.accessToken, credential.encrypted(cipher).accessToken)
    }
}
