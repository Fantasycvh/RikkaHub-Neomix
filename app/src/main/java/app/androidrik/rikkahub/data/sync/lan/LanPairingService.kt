package app.androidrik.rikkahub.data.sync.lan

import android.app.Application
import android.os.Build
import android.os.SystemClock
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

internal enum class LanPairingFailure { INVALID_CODE, NO_ADDRESS, NOT_AUTHORIZED, SELF, EXPIRED, REJECTED, LOCKED, PROTOCOL, FAILED }

internal class LanPairingException(val reason: LanPairingFailure) : IllegalStateException(reason.name)

internal class LanPairingService(
    private val app: Application,
    private val appScope: AppScope,
    private val store: SettingsStore,
    private val json: Json,
) {
    private val lock = Mutex()
    private val invitationState = MutableStateFlow<LanInvite?>(null)
    val invitation = invitationState.asStateFlow()
    val peers = store.lanPeers
    private var server: LanPairingServer? = null
    private val name = Build.MODEL.trim().take(80).takeIf(::validLanName) ?: "RikkaHub"

    suspend fun fingerprint(): String = withContext(Dispatchers.IO) { LanIdentity.load().fingerprint }

    suspend fun start() = withContext(Dispatchers.IO) {
        requirePermission()
        lock.withLock {
            if (server != null) return@withLock
            val address = findPrivateAddress() ?: throw LanPairingException(LanPairingFailure.NO_ADDRESS)
            val credentials = LanIdentity.load()
            val host = LanPairingServer(credentials, address)
            val invite = LanInvite(address, host.port, credentials.fingerprint, LanInvite.randomPin())
            val gate = LanPinGate(invite.pin, SystemClock.elapsedRealtime() + PAIR_WINDOW_MILLIS)
            server = host
            invitationState.value = invite
            appScope.launch(Dispatchers.IO) {
                try {
                    coroutineScope {
                        val timeout = launch { delay(PAIR_WINDOW_MILLIS); host.close() }
                        try {
                            serve(host, gate, credentials.fingerprint)
                        } finally {
                            timeout.cancel()
                        }
                    }
                } finally {
                    withContext(NonCancellable) { lock.withLock { if (server === host) stopLocked() } }
                }
            }
        }
    }

    fun stop() {
        appScope.launch(Dispatchers.IO) { lock.withLock { stopLocked() } }
    }

    private fun stopLocked() {
        server?.close()
        server = null
        invitationState.value = null
    }

    suspend fun forget(fingerprint: String) = store.forgetLanPeer(fingerprint)

    suspend fun join(code: String) = withContext(Dispatchers.IO) {
        requirePermission()
        val invite = LanInvite.parse(code) ?: throw LanPairingException(LanPairingFailure.INVALID_CODE)
        val credentials = LanIdentity.load()
        if (credentials.fingerprint == invite.fingerprint) throw LanPairingException(LanPairingFailure.SELF)
        val response = requestLanPair(credentials, invite, name, json)
        store.saveLanPeer(LanPeer(response.name, response.deviceId, SecretCipher.encrypt(response.token),
            lastEndpoints = listOf("${invite.address.hostAddress}:${invite.port}")))
    }

    private suspend fun serve(host: LanPairingServer, gate: LanPinGate, localFingerprint: String) {
        // A lost response can be retried from the same certificate without rotating the saved token.
        var accepted: LanPairResponse? = null
        var acceptedPeer: String? = null
        host.serve request@{ peerFingerprint, requestBody ->
            val request = runCatching { json.decodeFromString<LanPairRequest>(requestBody) }.getOrNull()
                ?: return@request 400 to ""
            if (request.protocol != LAN_PROTOCOL) return@request 426 to ""
            if (request.deviceId != peerFingerprint || peerFingerprint == localFingerprint || !validLanName(request.name)) {
                return@request 403 to ""
            }
            val status = gate.verify(request.pin, SystemClock.elapsedRealtime())
            if (status != 200) return@request status to ""
            if (acceptedPeer != null && acceptedPeer != peerFingerprint) return@request 410 to ""
            val response = accepted ?: LanPairResponse(LAN_PROTOCOL, newPairToken(), localFingerprint, name).also {
                store.saveLanPeer(LanPeer(request.name, peerFingerprint, SecretCipher.encrypt(it.token)))
                accepted = it
                acceptedPeer = peerFingerprint
            }
            200 to json.encodeToString(response)
        }
    }

    private fun requirePermission() {
        if (Build.VERSION.SDK_INT >= 37 && app.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) throw LanPairingException(LanPairingFailure.NOT_AUTHORIZED)
    }
}

internal fun requestLanPair(credentials: LanCredentials, invite: LanInvite, name: String, json: Json): LanPairResponse {
    val trust = pairingTrust(invite.fingerprint)
    val client = OkHttpClient.Builder()
        .sslSocketFactory(credentials.tlsContext(trust).socketFactory, trust)
        .hostnameVerifier { _, session ->
            (session.peerCertificates.singleOrNull() as? X509Certificate)?.fingerprint() == invite.fingerprint
        }
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url("https://${invite.address.hostAddress}:${invite.port}/v1/pair")
        .post(json.encodeToString(LanPairRequest(LAN_PROTOCOL, invite.pin, credentials.fingerprint, name))
            .toRequestBody("application/json".toMediaType()))
        .build()
    val response = client.newCall(request).execute().use { result ->
        if (!result.isSuccessful) {
            throw LanPairingException(when (result.code) {
                403 -> LanPairingFailure.REJECTED
                410 -> LanPairingFailure.EXPIRED
                426 -> LanPairingFailure.PROTOCOL
                429 -> LanPairingFailure.LOCKED
                else -> LanPairingFailure.FAILED
            })
        }
        val source = result.body.source()
        if (source.request(PAIR_MAX_BYTES + 1L)) throw LanPairingException(LanPairingFailure.FAILED)
        json.decodeFromString<LanPairResponse>(source.readUtf8())
    }
    if (response.protocol != LAN_PROTOCOL || response.deviceId != invite.fingerprint ||
        !validPairToken(response.token) || !validLanName(response.name)
    ) throw LanPairingException(LanPairingFailure.FAILED)
    return response
}

internal fun findPrivateAddress(): Inet4Address? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .firstOrNull { it.isSiteLocalAddress }
