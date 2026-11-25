package app.androidrik.rikkahub.data.sync.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingProtocolTest {
    @Test
    fun `invitation carries the pinned certificate and only accepts literal private IPv4`() {
        val fingerprint = "a1".repeat(32)
        val code = "rikka-lan:1:192.168.1.10:46521:$fingerprint:000038"
        val invite = requireNotNull(LanInvite.parse(code))
        assertEquals(code, invite.code)
        assertEquals(fingerprint, invite.fingerprint)
        assertNull(LanInvite.parse(code.replace("192.168.1.10", "example.com")))
        assertNull(LanInvite.parse(code.replace("192.168.1.10", "8.8.8.8")))
        assertNull(LanInvite.parse(code.replace("192.168.1.10", "127.0.0.1")))
        assertNull(LanInvite.parse(code.replace("192.168.1.10", "192.168.1.010")))
        assertNull(LanInvite.parse(code.replace(":1:", ":2:")))
        assertNull(LanInvite.parse(code.replace(":46521:", ":65536:")))
        assertNull(LanInvite.parse(code.replace(fingerprint, "short")))
    }

    @Test
    fun `five wrong PINs lock the invitation and expired codes are refused`() {
        val gate = LanPinGate("000038", 120_000)
        repeat(4) { assertEquals(403, gate.verify("000037", 5_000)) }
        assertEquals(429, gate.verify("000037", 5_000))
        assertEquals(429, gate.verify("000038", 64_999))
        assertEquals(200, gate.verify("000038", 65_000))
        assertEquals(410, gate.verify("000038", 120_000))
    }

    @Test
    fun `tokens use the URL-safe 256-bit representation`() {
        val first = newPairToken()
        val second = newPairToken()
        assertTrue(validPairToken(first))
        assertTrue(first != second)
        assertFalse(validPairToken(first + "="))
    }
}
