package app.androidrik.rikkahub.data.sync.repo

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Loopback-only HTTP fixture, not an emulation of vendor durability or S3 authentication. */
internal class FixtureBackupServer : Closeable {
    private val socket = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val directories = ConcurrentHashMap.newKeySet<String>().apply { add(""); add("/dav") }
    val methods = CopyOnWriteArrayList<String>()
    @Volatile var forbidden = false
    @Volatile var unknownLength = false
    @Volatile var signedRequests = 0
    private val worker = thread(isDaemon = true, name = "backup-http-fixture") {
        while (!socket.isClosed) {
            try { socket.accept().use { connection ->
                connection.soTimeout = 10000
                val input = connection.getInputStream().buffered()
                val request = line(input).split(' ')
                if (request.size < 2) return@use
                val method = request[0]; methods += method
                val uri = URI(request[1])
                val path = uri.path.trimEnd('/')
                val headers = linkedMapOf<String, String>()
                while (true) { val h = line(input); if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
                if (headers["authorization"]?.startsWith("AWS4-HMAC-SHA256") == true) signedRequests++
                val content = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
                var offset = 0
                while (offset < content.size) { val n = input.read(content, offset, content.size - offset); check(n > 0); offset += n }
                val s3 = path.startsWith("/bucket")
                var status = 200
                var body = byteArrayOf()
                when {
                    method == "PROPFIND" && forbidden -> status = 403
                    method == "PROPFIND" -> {
                        if (path !in directories) status = 404 else {
                            val children = if (headers["depth"] == "0") emptyList() else
                                (objects.keys + directories).filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
                            // Parent last is legal and catches the historical drop(1) bug.
                            body = ("<D:multistatus xmlns:D=\"DAV:\">" + (children + path).joinToString("") { item ->
                                val href = URI(null, null, item + if (item in directories) "/" else "", null).toASCIIString()
                                "<D:response><D:href>$href</D:href><D:propstat><D:prop><D:displayname>not-the-key</D:displayname>" +
                                    "<D:getcontentlength>${objects[item]?.size ?: 0}</D:getcontentlength><D:resourcetype>" +
                                    (if (item in directories) "<D:collection/>" else "") + "</D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                            } + "</D:multistatus>").toByteArray()
                            status = 207
                        }
                    }
                    method == "MKCOL" -> { directories.add(path); status = 201 }
                    method == "PUT" -> {
                        if (!s3 && path.substringBeforeLast('/', "") !in directories) status = 409
                        else { objects[path] = content; status = 201 }
                    }
                    method == "GET" && s3 && uri.rawQuery?.contains("list-type") == true -> {
                        val query = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        val prefix = query["prefix"].orEmpty()
                        val keys = objects.keys.map { it.removePrefix("/bucket/") }.filter { it.startsWith(prefix) }.sorted()
                        body = ("<ListBucketResult><IsTruncated>false</IsTruncated>" + keys.joinToString("") {
                            "<Contents><Key>$it</Key><Size>${objects["/bucket/$it"]!!.size}</Size></Contents>"
                        } + "</ListBucketResult>").toByteArray()
                    }
                    method == "GET" || method == "HEAD" -> { body = objects[path] ?: byteArrayOf(); if (!objects.containsKey(path)) status = 404 }
                    method == "DELETE" -> { objects.remove(path); status = 204 }
                    else -> status = 400
                }
                connection.getOutputStream().use { out ->
                    val lengthHeader = if (unknownLength) "" else "Content-Length: ${body.size}\r\n"
                    out.write("HTTP/1.1 $status Fixture\r\n${lengthHeader}Connection: close\r\n\r\n".toByteArray())
                    if (method != "HEAD") out.write(body)
                }
            } } catch (_: Exception) { /* Closing the socket terminates the accept loop. */ }
        }
    }
    private fun line(input: InputStream): String {
        val text = StringBuilder()
        while (true) { val b = input.read(); if (b < 0 || b == 10) return text.toString().trimEnd('\r'); text.append(b.toChar()); check(text.length < 16384) }
    }
    override fun close() { socket.close(); worker.join(2000) }
}
