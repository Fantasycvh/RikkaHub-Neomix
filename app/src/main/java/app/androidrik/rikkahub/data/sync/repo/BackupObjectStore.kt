package app.androidrik.rikkahub.data.sync.repo

import java.io.File

internal data class BackupStoredObject(val key: String, val bytes: Long?, val directory: Boolean = false)

/** null stat means missing; permission, transport and query failures MUST throw. */
internal interface BackupObjectStore {
    val destinationId: String
    val allowNonEmptyInitialization: Boolean get() = false
    suspend fun listChildren(prefix: String): List<BackupStoredObject>
    suspend fun stat(key: String): BackupStoredObject?
    suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit = {})
    suspend fun put(key: String, source: File, onBytes: (Long) -> Unit = {})
    suspend fun delete(key: String)
    suspend fun ensureDir(prefix: String)
}
