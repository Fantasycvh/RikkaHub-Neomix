package app.androidrik.rikkahub.data.sync.repo

import java.io.File
import java.time.Instant

internal data class BackupStoredObject(
    val key: String,
    val bytes: Long?,
    val directory: Boolean = false,
    /** 远端最后修改时间；provider 没给就是 null（GC 对未知时间一律保守保留）。 */
    val lastModified: Instant? = null,
)

/** null stat means missing; permission, transport and query failures MUST throw. */
/**
 * 备份对象存储边界。
 *
 * 并发约定：引擎会**并行**调用相互独立的对象的 [stat]/[put]/[get]/[ensureDir]（同一次扫描/提交内，
 * 并发度见 `PARALLEL_OBJECTS`）。实现必须保证这些调用可并发执行（SAF ContentResolver、S3/WebDAV
 * 的 HTTP 客户端天然满足；自研实现请加锁，不要依赖调用方串行化）。
 */
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

    /**
     * 覆盖写一个元数据键（仓库租约等「内容每次都不同」的可变文件）。
     *
     * [put] 面向内容寻址对象，已存在的文件内容不一致必须报错；租约反过来就是要覆盖。
     * 默认实现退回 [put]（S3/WebDAV/内存 store 本身就是覆盖语义），SAF 需要单独走真覆盖。
     */
    suspend fun putOverwrite(key: String, source: File, onBytes: (Long) -> Unit = {}) = put(key, source, onBytes)
    suspend fun delete(key: String)
    suspend fun ensureDir(prefix: String)
}
