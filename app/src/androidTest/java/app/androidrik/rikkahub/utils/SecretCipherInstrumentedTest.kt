package app.androidrik.rikkahub.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 真机 Keystore 验证：加解密、随机 IV、密文篡改拒绝。 */
@RunWith(AndroidJUnit4::class)
class SecretCipherInstrumentedTest {
    @Test
    fun roundTripsPasswordAndPrivateKeyLikeValues() {
        val values = listOf(
            "correct horse battery staple",
            "中文密码与符号 !@#￥%……&*()",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\nBBBB\n-----END OPENSSH PRIVATE KEY-----",
            "line1\nline2\t\u0000tail",
        )
        values.forEach { value ->
            val encrypted = SecretCipher.encrypt(value)
            assertTrue("missing version prefix: $encrypted", encrypted.startsWith("v1:"))
            assertNotEquals(value, encrypted)
            assertTrue("plaintext leaked into ciphertext", !encrypted.contains(value))
            assertTrue("decrypt mismatch", SecretCipher.decrypt(encrypted) == value)
        }
    }

    @Test
    fun encryptsSameValueWithFreshIv() {
        val first = SecretCipher.encrypt("same-secret")
        val second = SecretCipher.encrypt("same-secret")
        assertNotEquals("GCM IV must be random per encryption", first, second)
        assertTrue(SecretCipher.decrypt(first) == "same-secret")
        assertTrue(SecretCipher.decrypt(second) == "same-secret")
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val encrypted = SecretCipher.encrypt("tamper-me")
        val bytes = Base64.getDecoder().decode(encrypted.removePrefix("v1:"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        val tampered = "v1:" + Base64.getEncoder().encodeToString(bytes)
        try {
            SecretCipher.decrypt(tampered)
            fail("tampered ciphertext must not decrypt")
        } catch (expected: Exception) {
            // GCM authentication failure
        }
    }

    @Test
    fun rejectsUnknownCiphertextFormat() {
        try {
            SecretCipher.decrypt("plain-text")
            fail("unknown format must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
