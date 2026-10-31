package app.androidrik.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 单个镜像的测速结果。[bytesPerSecond] 为 0 表示该源不可用。 */
data class RootfsMirrorSpeed(
    val mirror: RootfsMirror,
    val url: String,
    val latencyMillis: Long,
    val bytesPerSecond: Long,
    val bytesSampled: Long,
    val error: String? = null,
) {
    val usable: Boolean get() = error == null && bytesPerSecond > 0
}

/**
 * 对候选镜像做真实下载测速，挑出最快的那个。
 *
 * 只测「首字节延迟 + 一小段 Range 吞吐」：rootfs 有约 28MB，全量下载测速会让用户
 * 在对话框里干等，而 cdimage 与各镜像站的带宽差异用 512KB 已足够区分。
 */
class RootfsMirrorSelector(
    private val sampleBytes: Long = DEFAULT_SAMPLE_BYTES,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
) {

    /**
     * 并行测速所有镜像，返回按「可用优先 → 吞吐降序 → 延迟升序」排序的结果。
     *
     * 全部失败时返回的结果里每项都带 [RootfsMirrorSpeed.error]，调用方仍可选一个
     * 去试装（镜像站可能只是临时限流），因此这里不做「必须成功」的假设。
     */
    suspend fun select(fileName: String): List<RootfsMirrorSpeed> = withContext(Dispatchers.IO) {
        coroutineScope {
            RootfsCatalog.MIRRORS
                .map { mirror ->
                    async { probe(mirror, mirror.urlFor(fileName)) }
                }
                .awaitAll()
        }.sortedWith(
            compareByDescending<RootfsMirrorSpeed> { it.usable }
                .thenByDescending { it.bytesPerSecond }
                .thenBy { it.latencyMillis },
        )
    }

    private fun probe(mirror: RootfsMirror, url: String): RootfsMirrorSpeed {
        val result = HttpProbe.probe(
            url = url,
            sampleBytes = sampleBytes,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            userAgent = USER_AGENT,
        )
        return RootfsMirrorSpeed(
            mirror = mirror,
            url = url,
            latencyMillis = result.latencyMillis,
            bytesPerSecond = result.bytesPerSecond,
            bytesSampled = result.bytesSampled,
            error = result.error,
        )
    }

    private companion object {
        private const val DEFAULT_SAMPLE_BYTES = 512L * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val USER_AGENT = RootfsCatalog.DOWNLOAD_USER_AGENT
    }
}
