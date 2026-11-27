package app.androidrik.rikkahub.data.sync.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.net.Inet4Address
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LanDiscoveryInstrumentedTest {
    @Test
    fun discoversARegisteredPrivateEndpointAndClosesRegistration() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "lan-nsd-test-${UUID.randomUUID()}"
        val identity = LanIdentity.load(alias)
        try {
            val address = lanAddress(app)
            LanTlsServer(identity, address).use { listener ->
                val discovery = LanDiscovery(app)
                val found = async {
                    withTimeout(25_000) { discovery.discover(identity.fingerprint).first { it.isNotEmpty() } }
                }
                delay(300)
                discovery.advertise(identity.fingerprint, LanSyncEndpoint(address, listener.port)).use {
                    val endpoints = found.await()
                    assertEquals(listOf(LanSyncEndpoint(address, listener.port)), endpoints)
                }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test
    fun discoversReplacementPortWithoutRestartingDiscovery() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "lan-nsd-rebind-${UUID.randomUUID()}"
        val identity = LanIdentity.load(alias)
        val address = lanAddress(app)
        val updates = Channel<List<LanSyncEndpoint>>(Channel.UNLIMITED)
        val discovery = LanDiscovery(app)
        val collector = launch { discovery.discover(identity.fingerprint).collect { updates.send(it) } }
        suspend fun awaitPort(port: Int) = withTimeout(25_000) {
            var endpoints = updates.receive()
            while (LanSyncEndpoint(address, port) !in endpoints) {
                endpoints = updates.receive()
            }
            endpoints
        }
        try {
            LanTlsServer(identity, address).use { first ->
                LanTlsServer(identity, address).use { second ->
                    discovery.advertise(identity.fingerprint, LanSyncEndpoint(address, first.port)).use {
                        awaitPort(first.port)
                    }
                    discovery.advertise(identity.fingerprint, LanSyncEndpoint(address, second.port)).use {
                        assertEquals(listOf(LanSyncEndpoint(address, second.port)), awaitPort(second.port))
                    }
                }
            }
        } finally {
            collector.cancel()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Suppress("DEPRECATION")
    private fun lanAddress(context: Context): Inet4Address {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // The emulator's eth0 is its cellular transport; NSD only advertises on LAN
        // transports. Bind these discovery tests to the actual Wi-Fi/Ethernet network.
        return requireNotNull(connectivity.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            ) connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress }
            else null
        })
    }
}
