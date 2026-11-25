package app.androidrik.rikkahub.data.sync.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Proxy
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

private const val MANIFEST_PATH = "/v1/manifest"
private const val OBJECT_PATH = "/v1/objects/"
private const val MANIFEST_BYTES = 4L * 1024 * 1024
private const val TOKEN_HEADER = "X-Rikka-Token"
private const val PROTOCOL_HEADER = "X-Rikka-Protocol"
private const val OBJECT_SIZE_HEADER = "X-Rikka-Object-Size"
private val rangePattern = Regex("bytes=([0-9]{1,9})-([0-9]{1,9})")

/** Serves a single captured inventory. The owner closes the listener at the end of the sync window. */
internal class LanSyncDownloadServer(
    credentials: LanCredentials,
    address: Inet4Address,
    manifest: LanManifest,
    files: Map<String, File>,
    json: Json,
    private val tokenForPeer: suspend (String) -> String?,
) : Closeable {
    private val inventory = (manifest.conversations + manifest.assistants).flatMap { listOf(it.sha256) + it.attachmentShas }
        .toSet().associateWith { digest -> requireNotNull(files[digest]) }
    private val manifestBody = json.encodeToString(manifest).encodeToByteArray()
    private val listener = run {
        manifest.validate(manifest.device.dbVersion, credentials.fingerprint)
        require(manifestBody.size <= MANIFEST_BYTES && inventory.values.all { it.isFile && it.length() in 1..LAN_OBJECT_MAX_BYTES })
        LanTlsServer(credentials, address)
    }
    val port: Int get() = listener.port

    suspend fun serve() {
        listener.serve request@{ fingerprint, request ->
            val supplied = request.headers[TOKEN_HEADER.lowercase()]
            val expected = tokenForPeer(fingerprint)
            if (supplied == null || expected == null || !validPairToken(supplied) ||
                !MessageDigest.isEqual(supplied.encodeToByteArray(), expected.encodeToByteArray())
            ) return@request LanHttpResponse(403)
            if (request.headers[PROTOCOL_HEADER.lowercase()] != LAN_PROTOCOL.toString()) return@request LanHttpResponse(426)
            if (request.contentLength != 0L) return@request LanHttpResponse(400)
            if (request.method == "GET" && request.path == MANIFEST_PATH) {
                return@request LanHttpResponse.bytes(200, manifestBody, "application/json")
            }
            val file = request.path.takeIf { it.startsWith(OBJECT_PATH) }?.removePrefix(OBJECT_PATH)?.let(inventory::get)
                ?: return@request LanHttpResponse(404)
            val total = file.length()
            if (total !in 1..LAN_OBJECT_MAX_BYTES) return@request LanHttpResponse(404)
            if (request.method == "HEAD") {
                return@request LanHttpResponse(200, headers = mapOf(OBJECT_SIZE_HEADER to total.toString()))
            }
            if (request.method != "GET") return@request LanHttpResponse(405)
            val range = rangePattern.matchEntire(request.headers["range"].orEmpty()) ?: return@request LanHttpResponse(416)
            val start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLong()
            val count = end - start + 1
            if (start >= total || end >= total || count !in 1..LAN_CHUNK_BYTES.toLong()) return@request LanHttpResponse(416)
            LanHttpResponse(206, count, mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Range" to "bytes $start-$end/$total",
                "Accept-Ranges" to "bytes",
            )) { output -> readLanFileRange(file, start, count.toInt()).use { it.copyTo(output) } }
        }
    }

    override fun close() = listener.close()
}

internal class LanSyncDownloadClient(
    credentials: LanCredentials,
    address: Inet4Address,
    port: Int,
    private val fingerprint: String,
    private val token: String,
    private val json: Json,
) {
    init {
        require(address.isSiteLocalAddress && port in 1..65535 && isLanDigest(fingerprint) && validPairToken(token))
    }

    private val base = "https://${address.hostAddress}:$port"
    private val trust = pairingTrust(fingerprint)
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(credentials.tlsContext(trust).socketFactory, trust)
        .hostnameVerifier { _, session ->
            (session.peerCertificates.singleOrNull() as? X509Certificate)?.fingerprint() == fingerprint
        }
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchManifest(dbVersion: Int): LanManifest = withContext(Dispatchers.IO) {
        execute(request(MANIFEST_PATH).get().build(), 200).use { response ->
            val buffer = response.body.source()
            if (buffer.request(MANIFEST_BYTES + 1)) throw IOException("LAN manifest exceeds size limit")
            json.decodeFromString<LanManifest>(buffer.readUtf8()).also { it.validate(dbVersion, fingerprint) }
        }
    }

    suspend fun pull(
        entries: List<LanManifestObject>,
        target: LanObjectStore,
        budgetBytes: Long = LAN_OBJECT_MAX_BYTES,
    ): LanTransferResult = withContext(Dispatchers.IO) {
        val sizes = mutableMapOf<String, Long>()
        receiveLanObjects(entries, target, budgetBytes, sourceSize = { digest ->
            execute(request(OBJECT_PATH + digest).head().build(), 200).use { response ->
                val total = response.header(OBJECT_SIZE_HEADER)?.toLongOrNull()
                    ?: throw IOException("Missing LAN object size")
                sizes[digest] = total
                total
            }
        }, readRange = { digest, start, size ->
            val end = start + size - 1
            val response = execute(request(OBJECT_PATH + digest).header("Range", "bytes=$start-$end").get().build(), 206)
            try {
                if (response.header("Content-Range") != "bytes $start-$end/${sizes.getValue(digest)}" ||
                    response.body.contentLength() != size.toLong()
                ) throw IOException("Unexpected LAN object range")
                response.body.byteStream()
            } catch (failure: Throwable) {
                response.close()
                throw failure
            }
        })
    }

    private fun request(path: String) = Request.Builder()
        .url(base + path)
        .header(TOKEN_HEADER, token)
        .header(PROTOCOL_HEADER, LAN_PROTOCOL.toString())
        .header("Accept-Encoding", "identity")

    private fun execute(request: Request, status: Int): Response {
        val response = client.newCall(request).execute()
        if (response.code == status) return response
        response.close()
        throw IOException("LAN peer returned HTTP ${response.code}")
    }
}
