package app.androidrik.rikkahub.data.sync.lan

import android.app.Application
import android.os.Build
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Inet4Address
import java.net.SocketException

internal enum class LanSyncStage { PREPARING, WAITING, EXCHANGING, UPLOADING, DOWNLOADING, APPLYING }

internal data class LanSyncResult(val local: LanSyncReport?, val remote: LanSyncReport?, val pending: Boolean = false)

internal data class LanSyncEndpoint(val address: Inet4Address, val port: Int) {
    override fun toString() = "${address.hostAddress}:$port"

    companion object {
        fun parse(text: String): LanSyncEndpoint? {
            val parts = text.trim().replace('：', ':').replace('．', '.').replace('。', '.').split(':', limit = 3)
            if (parts.size != 2) return null
            val address = privateAddress(parts[0]) ?: return null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return LanSyncEndpoint(address, port)
        }
    }
}

internal class LanSyncService(
    private val app: Application,
    private val settings: SettingsStore,
    private val data: LanSyncData,
    private val json: Json,
) {
    private val operation = Mutex()
    private val discovery = LanDiscovery(app)
    fun discover(peer: LanPeer) = discovery.discover(peer.certSha256)
    private val deviceName = Build.MODEL.trim().take(80).takeIf(::validLanName) ?: "RikkaHub"

    suspend fun receive(
        peer: LanPeer,
        onEndpoint: (LanSyncEndpoint?) -> Unit,
        onStage: (LanSyncStage) -> Unit,
        onApplied: (LanSyncReport) -> Unit,
    ) = withContext(Dispatchers.IO) {
        operation.withLock {
            requireLanPermission(app)
            val credentials = LanIdentity.load()
            val (staging, directory) = stagingFor(peer)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val address = findPrivateAddress()
                if (address == null) {
                    onEndpoint(null)
                    onStage(LanSyncStage.WAITING)
                    delay(1_000)
                    continue
                }
                onStage(LanSyncStage.PREPARING)
                try {
                    var local = data.capture(credentials.fingerprint, deviceName, System.currentTimeMillis())
                    try {
                        val uploads = LanSyncUploadSession(local.manifest, staging, json, System::currentTimeMillis, local.files,
                            refreshLocal = {
                                val refreshed = data.capture(credentials.fingerprint, deviceName, System.currentTimeMillis())
                                val previous = local
                                local = refreshed
                                previous.close()
                                refreshed.manifest to refreshed.files
                            },
                        ) { remote, plan ->
                            onStage(LanSyncStage.APPLYING)
                            try {
                                data.apply(local, remote, plan, staging).also { report ->
                                    onApplied(report)
                                    recordCompleted(peer)
                                }
                            } finally {
                                onStage(LanSyncStage.WAITING)
                            }
                        }
                        LanSyncDownloadServer(credentials, address, local.manifest, local.files, json,
                            tokenForPeer = { fingerprint ->
                                if (fingerprint != peer.certSha256) null
                                else settings.lanPeers.first().firstOrNull { it.certSha256 == fingerprint }?.encryptedToken
                                    ?.let { runCatching { SecretCipher.decrypt(it) }.getOrNull() }
                            },
                            uploads = uploads,
                        ).use { server ->
                            val advertisement = runCatching {
                                discovery.advertise(credentials.fingerprint, LanSyncEndpoint(address, server.port))
                            }.getOrNull()
                            coroutineScope {
                                val handler = launch(Dispatchers.IO) { server.serve() }
                                try {
                                    onEndpoint(LanSyncEndpoint(address, server.port))
                                    onStage(LanSyncStage.WAITING)
                                    while (android.os.SystemClock.elapsedRealtime() < deadline && findPrivateAddress() == address) {
                                        delay(1_000)
                                    }
                                } finally {
                                    advertisement?.close()
                                    server.close()
                                    handler.cancel()
                                }
                            }
                        }
                    } finally {
                        local.close()
                    }
                } catch (_: SocketException) {
                    // Removing a Wi-Fi interface can fail accept/bind before the address
                    // polling loop notices it. Keep the original receive deadline and
                    // rebuild the listener once the network is available again.
                    currentCoroutineContext().ensureActive()
                    onStage(LanSyncStage.WAITING)
                    delay(1_000)
                } finally {
                    onEndpoint(null)
                }
            }
        }
    }

    suspend fun sync(
        peer: LanPeer,
        endpoints: List<LanSyncEndpoint>,
        onStage: (LanSyncStage) -> Unit,
    ): LanSyncResult {
        var failure: java.io.IOException? = null
        for (endpoint in endpoints.distinct().take(8)) {
            try { return sync(peer, endpoint, onStage) } catch (error: java.io.IOException) {
                if (error is LanSyncHttpException || error is javax.net.ssl.SSLException) throw error
                failure = error
            }
        }
        val refreshed = withTimeoutOrNull(5_000) {
            discover(peer).first { candidates -> candidates.any { it !in endpoints } }
        }.orEmpty()
        for (endpoint in refreshed.filterNot { it in endpoints }.take(8)) {
            try { return sync(peer, endpoint, onStage) } catch (error: java.io.IOException) {
                if (error is LanSyncHttpException || error is javax.net.ssl.SSLException) throw error
                failure = error
            }
        }
        throw failure ?: java.io.IOException("No reachable paired device")
    }

    suspend fun sync(peer: LanPeer, endpoint: LanSyncEndpoint, onStage: (LanSyncStage) -> Unit): LanSyncResult =
        withContext(Dispatchers.IO) {
            operation.withLock {
                requireLanPermission(app)
                val credentials = LanIdentity.load()
                val token = SecretCipher.decrypt(peer.encryptedToken)
                val (staging, directory) = stagingFor(peer)
                val client = LanSyncDownloadClient(credentials, endpoint.address, endpoint.port, peer.certSha256, token, json)
                onStage(LanSyncStage.PREPARING)
                data.capture(credentials.fingerprint, deviceName, System.currentTimeMillis()).use { local ->
                    onStage(LanSyncStage.EXCHANGING)
                    val offer = client.exchange(local.manifest)
                    val remote = offer.manifest
                    requireSupportedFamilies(remote)
                    settings.updateLanSyncStatus(peer, endpoint.toString())
                    val plan = planLanMerge(local.manifest, remote, offer.plannedAt)
                    onStage(LanSyncStage.UPLOADING)
                    val sent = transferLanLanes(plan.objectsFrom(local.manifest)) { entries, budget ->
                        client.push(entries, local.files, budget)
                    }
                    onStage(LanSyncStage.DOWNLOADING)
                    val received = transferLanLanes(plan.objectsFrom(remote)) { entries, budget ->
                        client.pull(entries, staging, budget, local.files)
                    }
                    val pending = sent.remainingObjects.isNotEmpty() || received.remainingObjects.isNotEmpty()
                    val ready = plan.completedKeys(local.manifest, remote, sent, received)
                    onStage(LanSyncStage.APPLYING)
                    val remoteReport = client.finish(ready)
                    val localReport = data.apply(local, remote, plan.restrictTo(ready), staging)
                    if (!pending) directory.deleteRecursively()
                    recordCompleted(peer, endpoint)
                    LanSyncResult(localReport, remoteReport, pending)
                }
            }
        }

    private fun stagingFor(peer: LanPeer): Pair<LanObjectStore, File> {
        require(isLanDigest(peer.certSha256))
        val root = File(app.cacheDir, "lan-objects")
        val expireBefore = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        root.listFiles().orEmpty().forEach { peerDir ->
            if (isLanDigest(peerDir.name)) {
                peerDir.listFiles().orEmpty().filter { it.lastModified() < expireBefore }.forEach { it.delete() }
            }
        }
        val directory = File(root, peer.certSha256)
        // Two levels are intentional: every store has exclusive use of a fingerprint's directory.
        check(directory.isDirectory || directory.mkdirs())
        return LanObjectStore(directory) to directory
    }

    private suspend fun recordCompleted(peer: LanPeer, endpoint: LanSyncEndpoint? = null) {
        try {
            settings.updateLanSyncStatus(peer, endpoint?.toString(), System.currentTimeMillis())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: java.io.IOException) {
            // Room already committed. A last-sync display update cannot turn that into a failed application.
        }
    }
}
