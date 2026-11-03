package app.androidrik.rikkahub.data.sync.webdav

import android.util.Log
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.basicAuth
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.datastore.WebDavConfig
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "WebDavClient"

// 路径编码/拆段/拼接见 WebDavPaths.kt（纯 JVM，可单测）。

class WebDavClient(
    private val config: WebDavConfig,
    private val httpClient: HttpClient,
) {
    /**
     * 拼接远端 URL：`config.path` 与每个参数都按 `/` 拆段后再逐段编码。
     *
     * 旧实现把两者各当成**一整段**，`config.path = "v3/objects"` 会变成 `v3%2Fobjects`，
     * 多级目录永远建不出来也用不了（增量仓库需要 `v3/objects/<aa>/`，设计稿 §2.4）。
     */
    private fun WebDavConfig.buildUrl(vararg segments: String): String =
        buildWebDavUrl(url, webDavPathSegments(path, segments.toList()))

    suspend fun put(
        path: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "PUT: $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", data.size.toString())
                }
                setBody(data)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "put failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to put: ${response.status}", response.status.value, errorBody)
            }

            Log.d(TAG, "put success: $path")
            Unit
        }
    }

    suspend fun put(
        path: String,
        file: File,
        contentType: String = "application/octet-stream",
        onProgress: ((sent: Long, total: Long?) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "PUT (stream file): $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", file.length().toString())
                }
                onProgress?.let { cb -> onUpload { sent, total -> cb(sent, total) } }
                // Stream file content to avoid loading the whole backup zip into heap.
                setBody(file.readChannel())
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "put(file) failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to put file: ${response.status}", response.status.value, errorBody)
            }

            Log.d(TAG, "put(file) success: $path (${file.length()} bytes)")
            Unit
        }
    }

    suspend fun get(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "GET: $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "get failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to get: ${response.status}", response.status.value, errorBody)
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().readBytes()
        }
    }

    suspend fun getStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "GET (stream): $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "getStream failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to get stream: ${response.status}", response.status.value, errorBody)
            }

            response.bodyAsChannel().toInputStream()
        }
    }

    suspend fun downloadToFile(
        path: String,
        targetFile: File,
        onProgress: ((downloaded: Long, total: Long?) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "GET (download to file): $url")

            httpClient.prepareRequest(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "downloadToFile failed: ${response.status} - $errorBody")
                    throw WebDavException("Failed to download: ${response.status}", response.status.value, errorBody)
                }

                // 总量优先取响应头 Content-Length（可能缺失，如 chunked）
                val total = response.headers["Content-Length"]?.toLongOrNull()
                val channel = response.bodyAsChannel()
                var downloaded = 0L
                targetFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                }
                onProgress?.invoke(downloaded, total ?: downloaded)
                Log.d(TAG, "downloadToFile success: downloaded ${targetFile.length()} bytes")
            }
            Unit
        }
    }

    suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "DELETE: $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Delete
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "delete failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to delete: ${response.status}", response.status.value, errorBody)
            }

            Log.d(TAG, "delete success: $path")
            Unit
        }
    }

    suspend fun head(path: String): Result<WebDavResourceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "HEAD: $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Head
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                throw WebDavException("Resource not found: ${response.status}", response.status.value, "")
            }

            WebDavResourceInfo(
                href = path,
                displayName = path.substringAfterLast("/"),
                contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0,
                contentType = response.headers["Content-Type"] ?: "application/octet-stream",
                lastModified = parseLastModified(response.headers["Last-Modified"]),
                isCollection = false,
            )
        }
    }

    suspend fun mkcol(path: String): Result<Unit> = mkcolAt(config.buildUrl(path))

    /**
     * 对**已拼好的完整 URL** 发 MKCOL。
     *
     * [mkdirs] 逐级创建时手上只有前缀 URL，不能再经过 `buildUrl`（那会把 `config.path`
     * 再拼一遍）。除了入参换成 URL，鉴权/状态码处理与原先完全一致。
     */
    private suspend fun mkcolAt(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "MKCOL: $url")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("MKCOL")
                basicAuth(config.username, config.password)
            }

            // 201 Created or 405 Method Not Allowed (already exists) are acceptable
            if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "mkcol failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to create collection: ${response.status}", response.status.value, errorBody)
            }

            Log.d(TAG, "mkcol success: $url")
            Unit
        }
    }

    suspend fun propfind(
        path: String = "",
        depth: Int = 1,
    ): Result<List<WebDavResourceInfo>> = propfindAt(config.buildUrl(path), depth)

    /** 对完整 URL 发 PROPFIND（[mkdirs] 逐级探测用；解析/鉴权与 [propfind] 完全相同）。 */
    private suspend fun propfindAt(
        url: String,
        depth: Int,
    ): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "PROPFIND: $url, depth: $depth")

            val propfindBody = """<?xml version="1.0" encoding="UTF-8"?>
                |<D:propfind xmlns:D="DAV:">
                |  <D:prop>
                |    <D:displayname/>
                |    <D:getcontentlength/>
                |    <D:getcontenttype/>
                |    <D:getlastmodified/>
                |    <D:resourcetype/>
                |  </D:prop>
                |</D:propfind>
            """.trimMargin()

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("PROPFIND")
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", "application/xml; charset=utf-8")
                    append("Depth", depth.toString())
                }
                setBody(propfindBody)
            }

            if (!response.status.isSuccess() && response.status.value != 207) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "propfind failed: ${response.status} - $errorBody")
                throw WebDavException("Failed to propfind: ${response.status}", response.status.value, errorBody)
            }

            val xmlBody = response.bodyAsText()
            parsePropfindResponse(xmlBody, url)
        }
    }

    suspend fun exists(path: String): Boolean {
        return head(path).isSuccess
    }

    /**
     * 逐级确保远端目录存在：`config.path` 的各层 + [path] 的各层。
     *
     * 为什么需要它：增量仓库要写 `v3/objects/<aa>/<hash>` 这类多级路径，而 WebDAV 的
     * MKCOL 只能逐级创建（父目录不存在时服务端返回 409），旧的 ensureCollectionExists
     * 只会建最后一层。每级先 PROPFIND depth=0 判存在，缺失才 MKCOL（405 视为已存在）。
     */
    suspend fun mkdirs(path: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val segments = webDavPathSegments(config.path, listOf(path))
            for (prefix in cumulativeSegments(segments)) {
                val url = buildWebDavUrl(config.url, prefix)
                if (propfindAt(url, depth = 0).isSuccess) {
                    Log.d(TAG, "Collection already exists: $url")
                    continue
                }
                mkcolAt(url).getOrThrow()
            }
            Unit
        }
    }

    /** 保持既有签名（[WebDavSync] 仍调 `ensureCollectionExists()`），语义改为逐级创建。 */
    suspend fun ensureCollectionExists(path: String = ""): Result<Unit> = mkdirs(path)

    suspend fun list(path: String = ""): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = propfind(path, depth = 1).getOrThrow()
            // Filter out the parent directory itself (first entry)
            if (result.isNotEmpty()) {
                result.drop(1)
            } else {
                emptyList()
            }
        }
    }

    private fun parsePropfindResponse(xml: String, baseUrl: String): List<WebDavResourceInfo> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val resources = mutableListOf<WebDavResourceInfo>()
        var currentHref: String? = null
        var currentDisplayName: String? = null
        var currentContentLength: Long = 0
        var currentContentType: String? = null
        var currentLastModified: Instant? = null
        var currentIsCollection = false
        var currentTag: String? = null
        var inResponse = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(":")
                    when (currentTag) {
                        "response" -> {
                            inResponse = true
                            currentHref = null
                            currentDisplayName = null
                            currentContentLength = 0
                            currentContentType = null
                            currentLastModified = null
                            currentIsCollection = false
                        }
                        "collection" -> {
                            currentIsCollection = true
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && inResponse) {
                        when (currentTag) {
                            "href" -> currentHref = text
                            "displayname" -> currentDisplayName = text
                            "getcontentlength" -> currentContentLength = text.toLongOrNull() ?: 0
                            "getcontenttype" -> currentContentType = text
                            "getlastmodified" -> currentLastModified = parseLastModified(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name.substringAfter(":")
                    if (tagName == "response" && currentHref != null) {
                        val displayName = currentDisplayName
                            ?: currentHref!!.trimEnd('/').substringAfterLast("/")

                        resources.add(
                            WebDavResourceInfo(
                                href = currentHref!!,
                                displayName = displayName,
                                contentLength = currentContentLength,
                                contentType = currentContentType ?: "application/octet-stream",
                                lastModified = currentLastModified,
                                isCollection = currentIsCollection,
                            )
                        )
                        inResponse = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return resources
    }

    private fun parseLastModified(dateString: String?): Instant? {
        if (dateString.isNullOrBlank()) return null

        return try {
            // RFC 1123 format: "Tue, 15 Nov 1994 08:12:31 GMT"
            ZonedDateTime.parse(dateString, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                // RFC 850 format: "Tuesday, 15-Nov-94 08:12:31 GMT"
                ZonedDateTime.parse(dateString, DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz")).toInstant()
            } catch (e: Exception) {
                try {
                    // ISO 8601
                    Instant.parse(dateString)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse date: $dateString")
                    null
                }
            }
        }
    }
}

data class WebDavResourceInfo(
    val href: String,
    val displayName: String,
    val contentLength: Long,
    val contentType: String,
    val lastModified: Instant?,
    val isCollection: Boolean,
)

class WebDavException(
    message: String,
    val statusCode: Int,
    val responseBody: String,
) : Exception(message)
