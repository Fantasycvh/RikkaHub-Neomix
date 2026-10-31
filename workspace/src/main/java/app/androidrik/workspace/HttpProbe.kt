package app.androidrik.workspace

import java.net.HttpURLConnection
import java.net.URL

/** 一次 HTTP 下载测速的原始结果。[bytesPerSecond] 为 0 表示该地址不可用。 */
data class HttpProbeResult(
    val latencyMillis: Long,
    val bytesPerSecond: Long,
    val bytesSampled: Long,
    val error: String? = null,
) {
    val usable: Boolean get() = error == null && bytesPerSecond > 0
}

/**
 * 只取「首字节延迟 + 一小段 Range 吞吐」的下载测速。
 *
 * 测的是连通性与带宽量级，不下载完整文件：镜像站之间的差异用几十 KB 已足够区分，
 * 而全量下载会让用户在一个对话框里干等。
 */
object HttpProbe {

    fun probe(
        url: String,
        sampleBytes: Long,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        userAgent: String = RootfsCatalog.DOWNLOAD_USER_AGENT,
    ): HttpProbeResult {
        val startAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = true
                // Range 不被支持时服务端会回 200 全量，靠 sampleBytes 上限截断
                setRequestProperty("Range", "bytes=0-${sampleBytes - 1}")
                setRequestProperty("User-Agent", userAgent)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                return fail(startAt, "HTTP $code")
            }
            var sampled = 0L
            var firstByteAt = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (sampled < sampleBytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (firstByteAt == 0L) firstByteAt = System.nanoTime()
                    sampled += read
                }
            }
            val finishedAt = System.nanoTime()
            if (sampled <= 0L) {
                return fail(startAt, "empty response")
            }
            val elapsedMillis = ((finishedAt - startAt) / 1_000_000L).coerceAtLeast(1L)
            HttpProbeResult(
                latencyMillis = ((firstByteAt - startAt) / 1_000_000L).coerceAtLeast(0L),
                bytesPerSecond = sampled * 1000L / elapsedMillis,
                bytesSampled = sampled,
            )
        } catch (e: Exception) {
            fail(startAt, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun fail(startAt: Long, reason: String): HttpProbeResult = HttpProbeResult(
        latencyMillis = (System.nanoTime() - startAt) / 1_000_000L,
        bytesPerSecond = 0L,
        bytesSampled = 0L,
        error = reason,
    )
}
