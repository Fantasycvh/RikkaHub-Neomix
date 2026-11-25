package app.androidrik.rikkahub.data.sync.lan

import java.io.Closeable
import java.net.Inet4Address

/** Single TLS pairing endpoint. The listener is closed whenever the invitation is dismissed or expires. */
internal class LanPairingServer(credentials: LanCredentials, address: Inet4Address) : Closeable {
    private val listener = LanTlsServer(credentials, address)
    val port: Int get() = listener.port

    suspend fun serve(handle: suspend (String, String) -> Pair<Int, String>) {
        listener.serve pair@{ peer, request ->
            if (request.method != "POST" || request.path != "/v1/pair") return@pair LanHttpResponse(400)
            val body = request.readBytes(PAIR_MAX_BYTES) ?: return@pair LanHttpResponse(400)
            val (status, response) = handle(peer, body.decodeToString())
            LanHttpResponse.bytes(status, response.encodeToByteArray(), "application/json")
        }
    }

    override fun close() = listener.close()
}
