package app.androidrik.rikkahub.data.sync.lan

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal class LanTlsServer(credentials: LanCredentials, address: Inet4Address) : Closeable {
    private val closed = AtomicBoolean()
    private val active = AtomicReference<SSLSocket?>(null)
    private val socket = (credentials.tlsContext(pairingTrust()).serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        try {
            require(address.isSiteLocalAddress)
            needClientAuth = true
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            bind(InetSocketAddress(address, 0), 4)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    val port: Int get() = socket.localPort

    suspend fun serve(handle: suspend (String, LanHttpRequest) -> LanHttpResponse) {
        while (!closed.get()) {
            currentCoroutineContext().ensureActive()
            val client = try {
                socket.accept() as SSLSocket
            } catch (failure: IOException) {
                if (closed.get()) return
                throw failure
            }
            active.set(client)
            client.use {
                try {
                    if (closed.get()) return
                    if (!(client.inetAddress is Inet4Address && client.inetAddress.isSiteLocalAddress)) return@use
                    client.soTimeout = 2_000
                    client.startHandshake()
                    val peer = client.session.peerCertificates.single() as X509Certificate
                    val request = LanHttpRequest.read(client.getInputStream().buffered())
                    val response = if (request == null) LanHttpResponse(400) else handle(peer.fingerprint(), request)
                    client.getOutputStream().buffered().use { response.writeTo(it) }
                } catch (_: IOException) {
                    // Failed handshakes and incomplete requests close only the current connection.
                } finally {
                    active.compareAndSet(client, null)
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        runCatching { active.getAndSet(null)?.close() }
    }
}

internal class LanHttpRequest private constructor(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    private val input: InputStream,
    private val deadline: Long,
) {
    val contentLength: Long = headers["content-length"]?.toLongOrNull() ?: 0L

    fun readBytes(limit: Int): ByteArray? {
        if (contentLength !in 1..limit.toLong()) return null
        val bytes = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < bytes.size && SystemClock.elapsedRealtime() < deadline) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            offset += count
        }
        return bytes.takeIf { offset == bytes.size }
    }

    companion object {
        fun read(input: InputStream): LanHttpRequest? {
            val deadline = SystemClock.elapsedRealtime() + 4_000
            val fields = readLine(input, deadline).split(' ')
            if (fields.size != 3 || fields[2] != "HTTP/1.1" || !fields[1].startsWith('/')) return null
            val headers = linkedMapOf<String, String>()
            repeat(24) {
                val line = readLine(input, deadline)
                if (line.isEmpty()) {
                    return LanHttpRequest(fields[0], fields[1], headers, input, deadline)
                }
                val separator = line.indexOf(':')
                if (separator < 1) return null
                val name = line.substring(0, separator).lowercase(Locale.ROOT)
                if (name.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }) return null
                val value = line.substring(separator + 1).trim()
                if (name == "transfer-encoding" || headers.put(name, value) != null) return null
                if (name == "content-length" && (value.toLongOrNull() ?: -1L) < 0) return null
            }
            return null
        }

        private fun readLine(input: InputStream, deadline: Long): String {
            val line = StringBuilder()
            while (line.length < 512 && SystemClock.elapsedRealtime() < deadline) {
                when (val byte = input.read()) {
                    '\r'.code -> {
                        if (input.read() != '\n'.code) break
                        return line.toString()
                    }
                    in 32..126, '\t'.code -> line.append(byte.toChar())
                    else -> break
                }
            }
            throw IOException("Invalid LAN HTTP request")
        }
    }
}

internal class LanHttpResponse(
    private val status: Int,
    private val contentLength: Long = 0,
    private val headers: Map<String, String> = emptyMap(),
    private val body: (OutputStream) -> Unit = {},
) {
    fun writeTo(output: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 $status Response\r\nContent-Length: $contentLength\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(header.encodeToByteArray())
        body(output)
        output.flush()
    }

    companion object {
        fun bytes(status: Int, bytes: ByteArray, contentType: String) =
            LanHttpResponse(status, bytes.size.toLong(), mapOf("Content-Type" to contentType)) { it.write(bytes) }
    }
}
