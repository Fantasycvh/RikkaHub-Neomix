package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.s3.S3Client
import app.androidrik.rikkahub.data.sync.s3.S3Exception
import app.androidrik.rikkahub.data.sync.webdav.WebDavClient
import app.androidrik.rikkahub.data.sync.webdav.WebDavException
import java.io.File

internal class S3BackupObjectStore(private val client: S3Client, override val destinationId: String) : BackupObjectStore {
    private val root = "rikkahub_plus_backups/"
    override val allowNonEmptyInitialization = true
    override suspend fun listChildren(prefix: String): List<BackupStoredObject> {
        val fullPrefix = root + prefix.let { if (it.isEmpty()) "" else "$it/" }
        return client.listAllObjects(prefix = fullPrefix).getOrThrow().mapNotNull {
            require(it.key.startsWith(fullPrefix)) { "S3 returned a key outside the requested prefix" }
            val relative = it.key.removePrefix(root)
            if (!relative.removePrefix(if (prefix.isEmpty()) "" else "$prefix/").contains('/')) BackupStoredObject(relative, it.size) else null
        }
    }
    override suspend fun listAll(prefix: String): List<BackupStoredObject> {
        val fullPrefix = root + prefix.let { if (it.isEmpty()) "" else "$it/" }
        return client.listAllObjects(prefix = fullPrefix).getOrThrow().map {
            require(it.key.startsWith(fullPrefix)) { "S3 returned a key outside the requested prefix" }
            BackupStoredObject(it.key.removePrefix(root), it.size)
        }
    }
    override suspend fun stat(key: String): BackupStoredObject? {
        validateKey(key)
        return client.headObject(root + key).fold(
            onSuccess = { BackupStoredObject(key, it.size) },
            onFailure = { if (it is S3Exception && it.statusCode == 404) null else throw it },
        )
    }
    override suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit) {
        validateKey(key)
        var previous = 0L
        client.downloadObjectToFile(root + key, target, maxBytes = maxBytes) { downloaded, total ->
            require(downloaded <= maxBytes && (total == null || total <= maxBytes)) { "Remote object exceeds size budget" }
            onBytes(downloaded - previous); previous = downloaded
        }.getOrThrow()
    }
    override suspend fun put(key: String, source: File, onBytes: (Long) -> Unit) {
        validateKey(key)
        var previous = 0L
        client.uploadObject(root + key, source, "application/octet-stream", onUploadProgress = { sent, _ ->
            onBytes((sent - previous).coerceAtLeast(0)); previous = sent
        }).getOrThrow()
    }
    override suspend fun delete(key: String) { validateKey(key); client.deleteObject(root + key).getOrThrow() }
    override suspend fun ensureDir(prefix: String) { validateKey(prefix) }
}

internal class WebDavBackupObjectStore(private val client: WebDavClient, override val destinationId: String) : BackupObjectStore {
    override val allowNonEmptyInitialization = true
    override suspend fun listChildren(prefix: String): List<BackupStoredObject> {
        val resources = client.list(path = prefix).fold(onSuccess = { it }, onFailure = {
            if (it is WebDavException && it.statusCode == 404) return emptyList() else throw it
        })
        return resources.map { item ->
            BackupStoredObject((if (prefix.isEmpty()) "" else "$prefix/") + item.displayName, item.contentLength, item.isCollection)
        }
    }
    override suspend fun stat(key: String): BackupStoredObject? {
        validateKey(key)
        return client.head(key).fold(onSuccess = { BackupStoredObject(key, it.contentLength) },
            onFailure = { if (it is WebDavException && it.statusCode == 404) null else throw it })
    }
    override suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit) {
        validateKey(key)
        var previous = 0L
        client.downloadToFile(key, target, maxBytes = maxBytes) { downloaded, total ->
            require(downloaded <= maxBytes && (total == null || total <= maxBytes)) { "Remote object exceeds size budget" }
            onBytes(downloaded - previous); previous = downloaded
        }.getOrThrow()
    }
    override suspend fun put(key: String, source: File, onBytes: (Long) -> Unit) {
        validateKey(key)
        client.mkdirs(key.substringBeforeLast('/', "")).getOrThrow()
        var previous = 0L
        client.put(key, source, "application/octet-stream") { sent, _ ->
            onBytes((sent - previous).coerceAtLeast(0)); previous = sent
        }.getOrThrow()
    }
    override suspend fun delete(key: String) { validateKey(key); client.delete(key).getOrThrow() }
    override suspend fun ensureDir(prefix: String) { client.mkdirs(prefix).getOrThrow() }
}
