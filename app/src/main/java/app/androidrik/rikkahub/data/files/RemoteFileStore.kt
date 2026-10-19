package app.androidrik.rikkahub.data.files

import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.sync.s3.S3Client
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.data.sync.s3.S3ListResult
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

private const val TAG = "RemoteFileStore"

/**
 * 把本地文件传到 S3 换成临时公网地址，供只接受 URL 的接口使用（媒体生成的参考素材、聊天附件等）。
 *
 * 远程对象只是缓存，本地文件始终是源：key 由文件内容的 SHA-256 决定，相同内容不会重复上传；
 * 调用方不应持久化返回的地址，需要时重新调用 [upload] 即可。超过 [RETENTION] 的对象会被清扫删除。
 *
 * 返回的地址是带签名的临时下载地址（不依赖桶的公开读 ACL）：不要写进数据库或日志，也不要把对象
 * 设成公开可读。上传域要求 HTTPS——地址要交给第三方接口，显式 `http://` 的配置会直接失败。
 */
class RemoteFileStore internal constructor(
    private val scope: CoroutineScope,
    private val config: () -> S3Config,
    private val storage: (S3Config) -> RemoteObjectStorage,
    private val now: () -> Instant = Instant::now,
) {
    constructor(settingsStore: SettingsStore, httpClient: HttpClient, scope: CoroutineScope) : this(
        scope = scope,
        // 签出的地址要交给只接受 HTTPS 素材的接口，没写协议时不能落到 http
        config = { settingsStore.settingsFlow.value.uploadS3Config.withHttpsByDefault() },
        storage = { S3ObjectStorage(S3Client(it, httpClient)) },
    )

    private val mutex = Mutex()
    private val hashes = ConcurrentHashMap<String, FileHash>()

    // 以下状态只在持有 mutex 时读写
    private var knownConfig: S3Config? = null
    private val knownObjects = HashMap<String, KnownObject>()
    private var lastSweepAt: Instant? = null

    val isConfigured: Boolean
        get() = config().isUsableForUpload()

    /**
     * 确保 [file] 在桶里，并返回 [expires] 内有效的下载地址。内容已上传过时不会重传，只重新签发地址。
     *
     * 只接受 HTTPS endpoint：返回的地址要交给第三方接口，显式 `http://` 的配置直接返回失败。
     */
    suspend fun upload(
        file: File,
        contentType: String = guessContentType(file),
        expires: Duration = DEFAULT_URL_EXPIRES,
    ): Result<RemoteFile> = runCatching {
        require(expires.isPositive() && expires <= RETENTION) { "expires must be between 0 and $RETENTION" }
        val config = config()
        config.requireUsableForUpload()

        val key = objectKey(file)
        val storage = storage(config)
        val sweepDue = mutex.withLock {
            if (config != knownConfig) {
                knownObjects.clear()
                knownConfig = config
                // 换桶/换凭据之后要立刻按新配置重新清扫，不能沿用旧桶的清扫时间
                lastSweepAt = null
            }
            if (!isReusable(storage, key, expires)) {
                storage.put(key, file, contentType)
                knownObjects[key] = KnownObject(lastModified = now(), checkedAt = now())
            }
            val due = lastSweepAt?.let { now() >= it + SWEEP_INTERVAL.toJavaDuration() } ?: true
            if (due) lastSweepAt = now()
            due
        }
        if (sweepDue) {
            scope.launch {
                runCatching { sweep() }.onFailure { Log.w(TAG, "sweep failed", it) }
            }
        }

        RemoteFile(
            key = key,
            url = storage.presign(key, expires),
            expiresAt = now() + expires.toJavaDuration(),
        )
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * 用当前配置访问一次桶，配置缺失或请求失败时返回失败。
     */
    suspend fun testConnection(): Result<Unit> = runCatching {
        val config = config()
        config.requireUsableForUpload()
        storage(config).list(KEY_PREFIX, null)
        Unit
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * 删除超过 [RETENTION] 的对象。
     *
     * 只清扫 [KEY_PREFIX] 下的对象：桶里还可能放着备份（`rikkahub_plus_backups/`）等别的包存的文件，
     * 就算 list 实现不守 prefix 约定、把整桶对象都返回回来，这里也只删上传前缀内的过期对象。
     */
    internal suspend fun sweep() {
        val config = config()
        if (!config.isUsableForUpload()) return
        val storage = storage(config)

        var token: String? = null
        do {
            val previous = token
            // 逐页加锁：清扫不会和上传交错，上传也不用等整轮清扫结束
            token = mutex.withLock {
                val page = storage.list(KEY_PREFIX, previous)
                val deadline = now() - RETENTION.toJavaDuration()
                page.objects
                    // 防 list 返回前缀外的对象（恶意/兼容实现）：备份等其它前缀一律不动
                    .filter { it.key.startsWith(KEY_PREFIX) }
                    .filter { it.lastModified?.isBefore(deadline) == true }
                    .forEach {
                        storage.delete(it.key)
                        knownObjects.remove(it.key)
                    }
                val next = page.nextContinuationToken.takeIf { page.isTruncated }
                // 说还有下一页却没给 token、或反复给同一个 token 的坏实现都要停下，避免清扫死循环
                next?.takeIf { it != previous }
            }
        } while (token != null)
    }

    // 对象已在桶里，并且在地址过期前不会被清扫掉
    private suspend fun isReusable(storage: RemoteObjectStorage, key: String, expires: Duration): Boolean {
        val now = now()
        val known = knownObjects[key]?.takeIf { now < it.checkedAt + RECHECK_INTERVAL.toJavaDuration() }
        val lastModified = known?.lastModified ?: storage.lastModified(key)
        if (lastModified == null) {
            knownObjects.remove(key)
            return false
        }
        if (known == null) knownObjects[key] = KnownObject(lastModified = lastModified, checkedAt = now)
        return now + expires.toJavaDuration() <= lastModified + RETENTION.toJavaDuration()
    }

    private suspend fun objectKey(file: File): String = withContext(Dispatchers.IO) {
        require(file.isFile) { "File does not exist: ${file.path}" }
        val length = file.length()
        val lastModified = file.lastModified()
        val cached = hashes[file.path]?.takeIf { it.length == length && it.lastModified == lastModified }
        val sha256 = cached?.sha256
            ?: file.sha256Hex().also { hashes[file.path] = FileHash(length, lastModified, it) }
        val extension = file.extension.lowercase()
            .takeIf { ext -> ext.isNotEmpty() && ext.all { it.isLetterOrDigit() } }
        KEY_PREFIX + sha256 + extension?.let { ".$it" }.orEmpty()
    }

    private data class FileHash(val length: Long, val lastModified: Long, val sha256: String)

    private data class KnownObject(val lastModified: Instant, val checkedAt: Instant)

    companion object {
        const val KEY_PREFIX = "rikkahub_uploads/"

        /** 对象的保留时长，同时也是 SigV4 预签名地址的最长有效期。 */
        val RETENTION = 7.days
        val DEFAULT_URL_EXPIRES = 24.hours

        // 超过这个时间就重新向桶确认对象还在，避免对象被桶的生命周期规则或手动删除后仍然签出地址
        private val RECHECK_INTERVAL = 1.hours
        private val SWEEP_INTERVAL = 24.hours
    }
}

/**
 * 一次上传的结果。
 *
 * [url] 是带签名的临时下载地址：不要持久化到数据库、不要写日志，过期后重新调用 [RemoteFileStore.upload] 即可。
 */
data class RemoteFile(
    val key: String,
    val url: String,
    val expiresAt: Instant,
)

/**
 * [RemoteFileStore] 用到的对象存储操作。
 */
internal interface RemoteObjectStorage {
    /** 对象不存在，或拿不到修改时间时返回 null。 */
    suspend fun lastModified(key: String): Instant?

    suspend fun put(key: String, file: File, contentType: String)

    suspend fun list(prefix: String, continuationToken: String?): S3ListResult

    suspend fun delete(key: String)

    fun presign(key: String, expires: Duration): String
}

private class S3ObjectStorage(private val client: S3Client) : RemoteObjectStorage {
    override suspend fun lastModified(key: String): Instant? =
        client.headObject(key).getOrNull()?.lastModified?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }

    override suspend fun put(key: String, file: File, contentType: String) =
        client.putObject(key, file, contentType).getOrThrow()

    override suspend fun list(prefix: String, continuationToken: String?): S3ListResult =
        client.listObjects(prefix = prefix, continuationToken = continuationToken).getOrThrow()

    override suspend fun delete(key: String) = client.deleteObject(key).getOrThrow()

    override fun presign(key: String, expires: Duration): String = client.presignGetUrl(key, expires)
}

private fun S3Config.isUsable(): Boolean =
    endpoint.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && bucket.isNotBlank()

private fun S3Config.isUsableForUpload(): Boolean = isUsable() && endpoint.startsWith("https://")

private fun S3Config.requireUsableForUpload() {
    check(isUsable()) { "S3 is not configured" }
    // 临时签名地址要交给第三方接口：上传域只接受 HTTPS，显式 http 的配置直接失败。
    // 备份用的 S3 配置不经过这里，withHttpsByDefault() 对显式 http 的旧行为保持不变。
    check(endpoint.startsWith("https://")) { "Upload S3 endpoint must use HTTPS" }
}

private fun guessContentType(file: File): String =
    URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
