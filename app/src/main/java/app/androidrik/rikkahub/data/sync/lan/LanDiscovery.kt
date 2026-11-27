package app.androidrik.rikkahub.data.sync.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

private const val SYNC_SERVICE = "_rikka-sync._tcp."

/** Advertisements are endpoint hints. The TLS certificate and paired token remain authoritative. */
internal class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun advertise(fingerprint: String, endpoint: LanSyncEndpoint): Closeable {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        val info = NsdServiceInfo().apply {
            serviceType = SYNC_SERVICE
            serviceName = "Rikka-${fingerprint.take(12)}"
            port = endpoint.port
            // A phone can be a Wi-Fi client and hotspot simultaneously. Announce on the
            // network hosting the listening socket, rather than advertising an unreachable
            // interface's address with the same port.
            if (Build.VERSION.SDK_INT >= 33) {
                network = connectivity.allNetworks.firstOrNull { candidate ->
                    connectivity.getLinkProperties(candidate)?.linkAddresses.orEmpty()
                        .any { it.address == endpoint.address }
                }
            }
            setAttribute("id", fingerprint)
            setAttribute("protocol", LAN_PROTOCOL.toString())
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Closeable { runCatching { nsd.unregisterService(listener) } }
    }

    @Suppress("DEPRECATION")
    fun discover(fingerprint: String) = callbackFlow<List<LanSyncEndpoint>> {
        val closed = AtomicBoolean()
        val found = linkedMapOf<String, List<LanSyncEndpoint>>()
        val known = linkedMapOf<String, NsdServiceInfo>()
        val watches = linkedMapOf<String, Closeable>()
        val resolving = AtomicBoolean()
        val pending = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
        fun serviceKey(info: NsdServiceInfo) = info.serviceName +
            if (Build.VERSION.SDK_INT >= 33) "@${info.network}" else ""
        fun publish() { synchronized(found) { trySend(found.values.flatten().distinct().take(8)) } }
        fun update(key: String, info: NsdServiceInfo, source: NsdServiceInfo) {
            val id = info.attributes["id"]?.decodeToString()
            val protocol = info.attributes["protocol"]?.decodeToString()
            val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
            val endpoints = hosts.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.map { LanSyncEndpoint(it, info.port) }
            synchronized(found) {
                if (closed.get() || known[key] !== source) return
                if (id == fingerprint && protocol == LAN_PROTOCOL.toString() && info.port in 1..65535) {
                    found[key] = endpoints
                } else found.remove(key)
                publish()
            }
        }
        lateinit var resolveNext: () -> Unit
        resolveNext = next@{
            if (closed.get() || !resolving.compareAndSet(false, true)) return@next
            val info = pending.poll()
            if (info == null) { resolving.set(false); return@next }
            val source = info
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    resolving.set(false)
                    resolveNext()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    update(serviceKey(source), info, source)
                    resolving.set(false)
                    resolveNext()
                }
            }
            try { nsd.resolveService(info, listener) } catch (_: RuntimeException) {
                resolving.set(false)
                resolveNext()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("Rikka-${fingerprint.take(12)}", ignoreCase = true)) return
                val key = serviceKey(info)
                synchronized(found) {
                    if (closed.get() || key in known || known.size >= 32) return
                    known[key] = info
                    if (Build.VERSION.SDK_INT >= 34) {
                        try {
                            watches[key] = watch(info, { update(key, it, info) }, {
                                synchronized(found) {
                                    if (known[key] === info) { found.remove(key); publish() }
                                }
                            })
                        } catch (_: RuntimeException) {
                            known.remove(key)
                        }
                    } else if (pending.size < 32) { pending.add(info); resolveNext() }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    val key = serviceKey(info)
                    known.remove(key)
                    watches.remove(key)?.close()
                    found.remove(key)
                }
                publish()
            }
        }
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                pending.clear()
                synchronized(found) {
                    known.clear()
                    watches.values.forEach { it.close() }
                    watches.clear()
                    found.clear()
                }
                publish()
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(network)
            nsd.discoverServices(SYNC_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            // A remembered endpoint remains available when discovery is unsupported or permission is denied.
        }
        val refresh = launch {
            if (Build.VERSION.SDK_INT < 34) while (true) {
                delay(5_000)
                // Older NSD has only one-shot resolution. Refresh known services so a
                // receiver's new port is picked up without another onServiceFound event.
                synchronized(found) {
                    known.values.forEach { if (pending.size < 32) pending.add(it) }
                }
                resolveNext()
            }
        }
        awaitClose {
            closed.set(true)
            refresh.cancel()
            synchronized(found) {
                watches.values.forEach { it.close() }
                watches.clear()
                known.clear()
            }
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { connectivity.unregisterNetworkCallback(network) }
        }
    }

    @android.annotation.TargetApi(34)
    private fun watch(info: NsdServiceInfo, onUpdate: (NsdServiceInfo) -> Unit, onLost: () -> Unit): Closeable {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = onLost()
            override fun onServiceInfoCallbackUnregistered() = Unit
            override fun onServiceLost() = onLost()
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onUpdate(serviceInfo)
        }
        nsd.registerServiceInfoCallback(info, java.util.concurrent.Executor { it.run() }, callback)
        return Closeable { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
    }
}
