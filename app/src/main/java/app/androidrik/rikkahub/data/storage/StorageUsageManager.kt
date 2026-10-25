package app.androidrik.rikkahub.data.storage

import android.content.Context
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.files.FileFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * 可清理的缓存分类。**只收录有明确写入方、删除后可再生**的目录；
 * 用户数据（附件/头像/生成产物/工作区/数据库）不进这里，见
 * `docs/design/design-storage-management.md` §五。
 */
enum class StorageCategoryId {
    IMAGE_CACHE,
    TEMP,
    WEBVIEW,
    THUMBS,
    TEX,
    LOGS,
    BACKUP_TEMP;

    /** 是否有可展示的明细（逐文件列表）。 */
    val canExpand: Boolean
        get() = this == LOGS || this == BACKUP_TEMP
}

data class StorageDetail(
    val name: String,
    val sizeBytes: Long,
)

data class StorageCategory(
    val id: StorageCategoryId,
    val sizeBytes: Long,
    val itemCount: Int,
    /** false = 目录存在但不可读，此时大小按已读到部分计算，UI 需提示。 */
    val readable: Boolean = true,
    val canExpand: Boolean = false,
)

data class ClearResult(
    val id: StorageCategoryId,
    val freedBytes: Long,
    val failures: List<String>,
) {
    val success: Boolean get() = failures.isEmpty()
}

/**
 * 存储占用的统计与清理。
 *
 * 设计要点（对照 `docs/design/design-storage-management.md`）：
 * - **一份白名单同时驱动统计与清理**，不再出现「统计按 A 结构、清理按 B 结构」的漂移；
 * - 只删目录**内容**，保留目录本身；不整目录 `deleteRecursively`；
 * - 删除结果逐项返回，失败必须能到 UI，不吞异常；
 * - 不做全局 TTL 缓存，进页面即扫。
 */
class StorageUsageManager internal constructor(
    private val cacheDir: File,
    private val filesDir: File,
    private val clearLogs: () -> Unit,
) {

    constructor(context: Context) : this(
        cacheDir = context.cacheDir,
        filesDir = context.filesDir,
        clearLogs = Logging::clearLogFile,
    )

    suspend fun scan(): List<StorageCategory> = withContext(Dispatchers.IO) {
        coroutineScope {
            StorageCategoryId.entries
                .map { id -> async { scanCategory(id) } }
                .awaitAll()
        }
    }

    /**
     * 分类明细（目前只有备份临时文件与日志有），按大小降序。
     * 只读：不提供逐项删除，避免再引入一套与白名单并行的删除路径。
     */
    suspend fun details(id: StorageCategoryId): List<StorageDetail> = withContext(Dispatchers.IO) {
        when (id) {
            StorageCategoryId.BACKUP_TEMP -> backupTempFiles()
                .sortedByDescending { it.length() }
                .map { StorageDetail(it.name, it.length()) }

            StorageCategoryId.LOGS -> File(filesDir, FileFolders.LOGS)
                .listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.length() }
                ?.map { StorageDetail(it.name, it.length()) }
                .orEmpty()

            else -> emptyList()
        }
    }

    suspend fun clear(id: StorageCategoryId): ClearResult = withContext(Dispatchers.IO) {
        val before = scanCategory(id).sizeBytes
        val failures = when (id) {
            StorageCategoryId.LOGS ->
                runCatching { clearLogs() }
                    .exceptionOrNull()
                    ?.let { listOf(it.message ?: "logs") }
                    .orEmpty()

            StorageCategoryId.BACKUP_TEMP ->
                backupTempFiles().filterNot(::deleteTree).map { it.name }

            else -> categoryDirs(id).flatMap(::clearDirContents)
        }
        val after = scanCategory(id).sizeBytes
        ClearResult(
            id = id,
            freedBytes = (before - after).coerceAtLeast(0L),
            failures = failures,
        )
    }

    private fun scanCategory(id: StorageCategoryId): StorageCategory = when (id) {
        StorageCategoryId.LOGS -> {
            val stats = statsOf(File(filesDir, FileFolders.LOGS))
            StorageCategory(id, stats.bytes, stats.files, stats.readable, id.canExpand)
        }

        StorageCategoryId.BACKUP_TEMP -> {
            val files = backupTempFiles()
            StorageCategory(id, files.sumOf { it.length() }, files.size, canExpand = id.canExpand)
        }

        else -> {
            var bytes = 0L
            var files = 0
            var readable = true
            for (dir in categoryDirs(id)) {
                val stats = statsOf(dir)
                bytes += stats.bytes
                files += stats.files
                readable = readable && stats.readable
            }
            StorageCategory(id, bytes, files, readable)
        }
    }

    private fun categoryDirs(id: StorageCategoryId): List<File> = when (id) {
        StorageCategoryId.IMAGE_CACHE -> listOf(File(cacheDir, StoragePaths.IMAGE_CACHE))
        StorageCategoryId.TEMP -> listOf(File(cacheDir, StoragePaths.TEMP))
        StorageCategoryId.WEBVIEW -> listOf(File(cacheDir, StoragePaths.WEBVIEW))
        StorageCategoryId.THUMBS -> listOf(File(cacheDir, StoragePaths.THUMBS))
        StorageCategoryId.TEX -> listOf(File(cacheDir, StoragePaths.TEX))
        StorageCategoryId.LOGS, StorageCategoryId.BACKUP_TEMP -> emptyList()
    }

    /**
     * 备份/恢复的中间文件。只认三类白名单，避免把 `cacheDir` 里其他下载件误删。
     */
    private fun backupTempFiles(): List<File> {
        val children = cacheDir.listFiles() ?: return emptyList()
        return children.filter { it.isFile && isBackupTemp(it.name) }
    }

    private fun isBackupTemp(name: String): Boolean =
        (name.startsWith("backup_") && name.endsWith(".zip")) ||
            name.startsWith("restore_") ||
            name == "backup_db_snapshot.db"

    private fun clearDirContents(dir: File): List<String> {
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return listOf(dir.name)
        return children.filterNot(::deleteTree).map { it.name }
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (isSymlink(file)) {
            return runCatching { file.delete() }.getOrDefault(false) || !file.exists()
        }
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            var allDeleted = true
            for (child in children) {
                if (!deleteTree(child)) allDeleted = false
            }
            if (!allDeleted) return false
        }
        return runCatching { file.delete() }.getOrDefault(false) || !file.exists()
    }

    private data class DirStats(val bytes: Long, val files: Int, val readable: Boolean)

    private fun statsOf(dir: File): DirStats {
        if (!dir.exists()) return DirStats(0, 0, true)
        if (!dir.isDirectory) {
            return if (dir.isFile) DirStats(dir.length(), 1, true) else DirStats(0, 0, true)
        }

        var bytes = 0L
        var files = 0
        var readable = true
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles()
            if (children == null) {
                readable = false
                continue
            }
            for (child in children) {
                // 符号链接不跟随：避免统计到目录外，也避免环路
                if (isSymlink(child)) continue
                when {
                    child.isDirectory -> stack.addLast(child)
                    child.isFile -> {
                        bytes += child.length()
                        files++
                    }
                }
            }
        }
        return DirStats(bytes, files, readable)
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
