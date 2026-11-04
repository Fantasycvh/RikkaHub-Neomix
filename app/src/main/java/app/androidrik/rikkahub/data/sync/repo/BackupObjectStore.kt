package app.androidrik.rikkahub.data.sync.repo

import java.io.File

internal data class BackupStoredObject(val key: String, val bytes: Long?, val directory: Boolean = false)

/** null stat means missing; permission, transport and query failures MUST throw. */
internal interface BackupObjectStore {
    val destinationId: String
    val allowNonEmptyInitialization: Boolean get() = false
    suspend fun listChildren(prefix: String): List<BackupStoredObject>
    suspend fun stat(key: String): BackupStoredObject?

    /**
     * 递归列举 `prefix` 下所有**文件**（不含目录项）。
     *
     * 默认实现逐层 [listChildren]；S3 没有目录概念，直接覆盖为一次前缀列举。
     * GC 必须看到完整对象集合，不能被各端「只列直接子项」的差异吃掉。
     */
    suspend fun listAll(prefix: String): List<BackupStoredObject> {
        val result = ArrayList<BackupStoredObject>()
        suspend fun walk(path: String) {
            listChildren(path).forEach { child ->
                if (child.directory) walk(child.key) else result += child
            }
        }
        walk(prefix)
        return result
    }

    suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit = {})
    suspend fun put(key: String, source: File, onBytes: (Long) -> Unit = {})
    suspend fun delete(key: String)
    suspend fun ensureDir(prefix: String)
}
