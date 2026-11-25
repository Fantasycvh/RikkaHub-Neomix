package app.androidrik.rikkahub.data.sync.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanSyncEndpointTest {
    @Test
    fun acceptsLocalizedAddressPunctuation() {
        assertEquals("192.168.85.253:41489", LanSyncEndpoint.parse("192．168。85.253：41489")?.toString())
    }

    @Test
    fun stillRejectsPublicAddressesAndInvalidPorts() {
        assertNull(LanSyncEndpoint.parse("1.1.1.1：5000"))
        assertNull(LanSyncEndpoint.parse("192.168.85.253：65536"))
    }
}
