package app.androidrik.rikkahub.data.sync.s3

import android.util.Log
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant

private const val TAG = "S3Client"

class S3Client(
    private val config: S3Config,
    private val httpClient: HttpClient,
) {
    companion object {
        /** 超过此大小走分片上传（= 分片大小；小文件单 PUT + 全量哈希瞬时完成）。 */
        const val MULTIPART_THRESHOLD: Long = 8L * 1024 * 1024

        /**
         * 分片大小 8MB。S3 规范要求除最后一片外 ≥ 5MiB；8MB 给内存缓冲留了
         * 合理水位（堆内单缓冲），2.8GB ≈ 350 片。
         */
        const val MULTIPART_PART_SIZE: Int = 8 * 1024 * 1024

        /** 单分片上传重试次数（网络抖动重传一片即可，不必整体重来——这正是分片的意义）。 */
        const val MAX_PART_ATTEMPTS = 2
    }
    suspend fun putObject(
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "PUT",
                path = path,
                payload = data,
                contentType = contentType,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Put
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
                setBody(data)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "putObject failed: ${response.status} - $errorBody")
                throw S3Exception("Failed to put object: ${response.status}", errorBody)
            }

            Log.d(TAG, "putObject success: $key")
            Unit
        }
    }

    suspend fun putObject(
        key: String,
        file: File,
        contentType: String = "application/octet-stream",
        onUploadProgress: ((sent: Long, total: Long?) -> Unit)? = null,
        onHashProgress: ((hashed: Long, total: Long) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            // 注意：这一步会完整读一遍文件（2.8GB 下数十秒），必须通过 onHashProgress 上报，
            // 否则调用方的进度会长时间停在 0%（见设计稿 §9.1 坑 1）。
            val fileSha256 = file.sha256Hex { hashed -> onHashProgress?.invoke(hashed, file.length()) }
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "PUT",
                path = path,
                payloadHash = fileSha256,
                contentLength = file.length(),
                contentType = contentType,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Put
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
                onUploadProgress?.let { cb -> onUpload { sent, total -> cb(sent, total) } }
                // Stream large files to avoid loading backup archives into heap.
                setBody(file.readChannel())
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "putObject(file) failed: ${response.status} - $errorBody")
                throw S3Exception("Failed to put object: ${response.status}", errorBody)
            }

            Log.d(TAG, "putObject(file) success: $key (${file.length()} bytes)")
            Unit
        }
    }

    /**
     * 大文件上传入口（大文件加固 P0，设计稿 §九）：按大小分发到单 PUT 或分片上传。
     *
     * ≤ [MULTIPART_THRESHOLD]：走既有单 PUT（文件小，全量哈希瞬时完成，路径经过充分验证）。
     * ＞阈值：走分片——**单遍哈希**（每片读取时算哈希用于签名，不再有全文件预读空窗，
     * P1 项由此结构性解决），且单分片失败只需重传该片而非整个 2.8GB（健壮性）。
     */
    suspend fun uploadObject(
        key: String,
        file: File,
        contentType: String = "application/octet-stream",
        onUploadProgress: ((sent: Long, total: Long?) -> Unit)? = null,
        onHashProgress: ((hashed: Long, total: Long) -> Unit)? = null,
    ): Result<Unit> {
        return if (file.length() <= MULTIPART_THRESHOLD) {
            putObject(key, file, contentType, onUploadProgress, onHashProgress)
        } else {
            uploadMultipart(key, file, contentType, onUploadProgress)
        }
    }

    /**
     * S3 分片上传（Multipart Upload API）。
     *
     * 每片读入内存（[MULTIPART_PART_SIZE] 8MB）→ 算片哈希签名 → PUT → 收集 ETag。
     * 分片大小必须 ≥ 5MiB（S3 规范，最后一片除外）；失败（任一分片重传后仍失败）
     * 会 abort 清理服务端残余分片，再返回失败。
     *
     * @param onUploadProgress (已上传字节数, 总字节数)——按「已完成片的字节 + 本片已发」计
     */
    private suspend fun uploadMultipart(
        key: String,
        file: File,
        contentType: String,
        onUploadProgress: ((sent: Long, total: Long?) -> Unit)?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uploadId = initiateMultipartUpload(key, contentType).getOrThrow()
            val parts = mutableListOf<Pair<Int, String>>()
            try {
                val totalParts = ((file.length() + MULTIPART_PART_SIZE - 1) / MULTIPART_PART_SIZE).toInt()
                file.inputStream().use { input ->
                    val buffer = ByteArray(MULTIPART_PART_SIZE)
                    var partNumber = 1
                    var bytesBeforePart = 0L
                    while (true) {
                        var filled = 0
                        while (filled < buffer.size) {
                            val n = input.read(buffer, filled, buffer.size - filled)
                            if (n <= 0) break
                            filled += n
                        }
                        if (filled == 0) break

                        val partData = if (filled == buffer.size) buffer else buffer.copyOf(filled)
                        // 单分片失败重传一次（网络抖动）；再失败则整体中止
                        var etag: String? = null
                        repeat(MAX_PART_ATTEMPTS) { attempt ->
                            val result = uploadPart(key, uploadId, partNumber, partData) { sent, _ ->
                                onUploadProgress?.invoke(bytesBeforePart + sent, file.length())
                            }
                            etag = result.getOrNull()
                            if (etag == null && attempt == MAX_PART_ATTEMPTS - 1) {
                                result.getOrThrow() // 抛出最终失败
                            }
                        }
                        etag?.let { parts += partNumber to it }
                        bytesBeforePart += filled
                        partNumber++
                        Log.d(TAG, "uploadMultipart: part ${partNumber - 1}/$totalParts done (${filled} bytes)")
                    }
                }

                completeMultipartUpload(key, uploadId, parts).getOrThrow()
                Log.i(TAG, "uploadMultipart success: $key (${file.length()} bytes, ${parts.size} parts)")
            } catch (t: Throwable) {
                Log.e(TAG, "uploadMultipart failed, aborting uploadId=$uploadId", t)
                runCatching { abortMultipartUpload(key, uploadId) }
                throw t
            }
            Unit
        }
    }

    /** 初始化分片上传，返回 UploadId。 */
    private suspend fun initiateMultipartUpload(key: String, contentType: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val signed = AwsSignatureV4.sign(
                    config = config,
                    method = "POST",
                    path = "/${key.trimStart('/')}",
                    queryParams = mapOf("uploads" to ""),
                    contentType = contentType,
                )
                val response: HttpResponse = httpClient.request(signed.url) {
                    method = HttpMethod.Post
                    headers { signed.headers.forEach { (k, v) -> append(k, v) } }
                }
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    throw S3Exception("Initiate multipart failed: ${response.status}", errorBody)
                }
                parseUploadId(response.bodyAsText())
                    ?: throw S3Exception("Initiate multipart: UploadId missing in response", "")
            }
        }

    /** 上传单个分片，返回 ETag。分片数据已读入内存 ⇒ 哈希即算即签（单遍）。 */
    private suspend fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        data: ByteArray,
        onUploadProgress: ((sent: Long, total: Long?) -> Unit)?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payloadHash = data.sha256Hex()
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "PUT",
                path = "/${key.trimStart('/')}",
                queryParams = mapOf(
                    "partNumber" to partNumber.toString(),
                    "uploadId" to uploadId,
                ),
                payloadHash = payloadHash,
                contentLength = data.size.toLong(),
                contentType = "application/octet-stream",
            )
            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Put
                headers { signed.headers.forEach { (k, v) -> append(k, v) } }
                onUploadProgress?.let { cb -> onUpload { sent, total -> cb(sent, total) } }
                setBody(data)
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw S3Exception("Upload part $partNumber failed: ${response.status}", errorBody)
            }
            response.headers["etag"]?.trim('"')
                ?: throw S3Exception("Upload part $partNumber: ETag missing in response", "")
        }
    }

    /** 完成分片上传（XML 按 PartNumber 升序列出全部分片）。 */
    private suspend fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<Pair<Int, String>>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val xml = buildString {
                append("<CompleteMultipartUpload>")
                parts.sortedBy { it.first }.forEach { (num, etag) ->
                    val quoted = if (etag.startsWith("\"")) etag else "\"$etag\""
                    append("<Part><PartNumber>$num</PartNumber><ETag>$quoted</ETag></Part>")
                }
                append("</CompleteMultipartUpload>")
            }
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "POST",
                path = "/${key.trimStart('/')}",
                queryParams = mapOf("uploadId" to uploadId),
                payload = xml.toByteArray(Charsets.UTF_8),
                contentType = "application/xml",
            )
            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Post
                headers { signed.headers.forEach { (k, v) -> append(k, v) } }
                setBody(xml)
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw S3Exception("Complete multipart failed: ${response.status}", errorBody)
            }
            Unit
        }
    }

    /** 中止分片上传（清理服务端残余分片，避免计费/配额残留）。 */
    private suspend fun abortMultipartUpload(key: String, uploadId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val signed = AwsSignatureV4.sign(
                    config = config,
                    method = "DELETE",
                    path = "/${key.trimStart('/')}",
                    queryParams = mapOf("uploadId" to uploadId),
                )
                httpClient.request(signed.url) { method = HttpMethod.Delete }
                Unit
            }
        }

    private fun parseUploadId(xml: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "UploadId") {
                if (parser.next() == XmlPullParser.TEXT) return parser.text
            }
            parser.next()
        }
        return null
    }

    suspend fun getObject(key: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "getObject failed: ${response.status} - $errorBody")
                throw S3Exception("Failed to get object: ${response.status}", errorBody)
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().readBytes()
        }
    }

    suspend fun getObjectStream(key: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "getObjectStream failed: ${response.status} - $errorBody")
                throw S3Exception("Failed to get object stream: ${response.status}", errorBody)
            }

            response.bodyAsChannel().toInputStream()
        }
    }

    suspend fun downloadObjectToFile(
        key: String,
        targetFile: File,
        onProgress: ((downloaded: Long, total: Long?) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = path,
            )

            Log.d(TAG, "GET (download to file): $key")

            httpClient.prepareRequest(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "downloadObjectToFile failed: ${response.status} - $errorBody")
                    throw S3Exception("Failed to download object: ${response.status}", errorBody)
                }

                val total = response.headers["Content-Length"]?.toLongOrNull()
                response.bodyAsChannel().toInputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                }
                Log.d(TAG, "downloadObjectToFile success: downloaded ${targetFile.length()} bytes")
            }
            Unit
        }
    }

    suspend fun deleteObject(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "DELETE",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Delete
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "deleteObject failed: ${response.status} - $errorBody")
                throw S3Exception("Failed to delete object: ${response.status}", errorBody)
            }

            Log.d(TAG, "deleteObject success: $key")
            Unit
        }
    }

    suspend fun headObject(key: String): Result<S3ObjectMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "HEAD",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Head
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                throw S3Exception("Object not found: ${response.status}", "")
            }

            S3ObjectMetadata(
                key = key,
                size = response.headers["content-length"]?.toLongOrNull() ?: 0,
                contentType = response.headers["content-type"] ?: "application/octet-stream",
                etag = response.headers["etag"]?.trim('"'),
                lastModified = response.headers["last-modified"],
            )
        }
    }

    suspend fun listObjects(
        prefix: String = "",
        delimiter: String = "",
        maxKeys: Int = 1000,
        continuationToken: String? = null,
    ): Result<S3ListResult> = withContext(Dispatchers.IO) {
        runCatching {
            val queryParams = mutableMapOf(
                "list-type" to "2",
                "max-keys" to maxKeys.toString(),
            )
            if (prefix.isNotEmpty()) queryParams["prefix"] = prefix
            if (delimiter.isNotEmpty()) queryParams["delimiter"] = delimiter
            continuationToken?.let { queryParams["continuation-token"] = it }

            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = "/",
                queryParams = queryParams,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "listObjects failed: ${response.status} - $errorBody")
                throw S3Exception("Failed to list objects: ${response.status}", errorBody)
            }

            val xmlBody = response.bodyAsText()
            parseListObjectsResponse(xmlBody)
        }
    }

    suspend fun objectExists(key: String): Boolean {
        return headObject(key).isSuccess
    }

    fun getPublicUrl(key: String): String {
        val path = "/${key.trimStart('/')}"
        return if (config.pathStyle) {
            "${config.endpoint.trimEnd('/')}/${config.bucket}$path"
        } else {
            val scheme = if (config.isHttps) "https://" else "http://"
            "$scheme${config.bucket}.${config.host}$path"
        }
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(this).joinToString("") { "%02x".format(it) }
    }

    private fun File.sha256Hex(onProgress: ((Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var hashed = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                hashed += read
                onProgress?.invoke(hashed)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseListObjectsResponse(xml: String): S3ListResult {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val objects = mutableListOf<S3Object>()
        val commonPrefixes = mutableListOf<String>()
        var isTruncated = false
        var nextContinuationToken: String? = null
        var keyCount = 0

        var currentKey: String? = null
        var currentSize: Long = 0
        var currentEtag: String? = null
        var currentLastModified: Instant? = null
        var currentStorageClass: String? = null
        var currentPrefix: String? = null

        var inContents = false
        var inCommonPrefixes = false
        var currentTag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "Contents" -> {
                            inContents = true
                            currentKey = null
                            currentSize = 0
                            currentEtag = null
                            currentLastModified = null
                            currentStorageClass = null
                        }

                        "CommonPrefixes" -> {
                            inCommonPrefixes = true
                            currentPrefix = null
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when {
                            inContents -> {
                                when (currentTag) {
                                    "Key" -> currentKey = text
                                    "Size" -> currentSize = text.toLongOrNull() ?: 0
                                    "ETag" -> currentEtag = text.trim('"')
                                    "LastModified" -> currentLastModified =
                                        runCatching { Instant.parse(text) }.getOrNull()

                                    "StorageClass" -> currentStorageClass = text
                                }
                            }

                            inCommonPrefixes -> {
                                if (currentTag == "Prefix") currentPrefix = text
                            }

                            else -> {
                                when (currentTag) {
                                    "IsTruncated" -> isTruncated = text.toBoolean()
                                    "NextContinuationToken" -> nextContinuationToken = text
                                    "KeyCount" -> keyCount = text.toIntOrNull() ?: 0
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Contents" -> {
                            currentKey?.let { key ->
                                objects.add(
                                    S3Object(
                                        key = key,
                                        size = currentSize,
                                        etag = currentEtag,
                                        lastModified = currentLastModified,
                                        storageClass = currentStorageClass,
                                    )
                                )
                            }
                            inContents = false
                        }

                        "CommonPrefixes" -> {
                            currentPrefix?.let { commonPrefixes.add(it) }
                            inCommonPrefixes = false
                        }
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return S3ListResult(
            objects = objects,
            commonPrefixes = commonPrefixes,
            isTruncated = isTruncated,
            nextContinuationToken = nextContinuationToken,
            keyCount = if (keyCount > 0) keyCount else objects.size,
        )
    }
}

data class S3Object(
    val key: String,
    val size: Long,
    val etag: String?,
    val lastModified: Instant?,
    val storageClass: String?,
)

data class S3ObjectMetadata(
    val key: String,
    val size: Long,
    val contentType: String,
    val etag: String?,
    val lastModified: String?,
)

data class S3ListResult(
    val objects: List<S3Object>,
    val commonPrefixes: List<String>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
    val keyCount: Int,
)

class S3Exception(
    message: String,
    val responseBody: String,
) : Exception(message)
