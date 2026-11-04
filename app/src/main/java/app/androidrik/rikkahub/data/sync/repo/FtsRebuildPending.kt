package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileOutputStream

/**
 * P2 逻辑对象快照恢复后的搜索索引重建标记。
 *
 * FTS（`message_fts*`）是可重建派生数据且体积占比最大，不进入 DB 对象快照；恢复换库后
 * 必须按恢复后的消息重新分词。标记在**恢复已 stage、进程重启前**写入，启动期 Room 打开后
 * 由 [app.androidrik.rikkahub.RikkaHubApp] 触发全量重建，成功后删除。
 *
 * 文件存在即待重建（空文件也算），所以写入无需原子换名——只可能「多重建一次」，不会漏。
 */
internal object FtsRebuildPending {
    private const val MARKER = "fts_rebuild_pending"

    fun markerFile(filesDir: File): File = File(File(filesDir, FileFolders.BACKUP_INDEX), MARKER)

    fun isPending(filesDir: File): Boolean = markerFile(filesDir).isFile

    fun mark(filesDir: File) {
        val marker = markerFile(filesDir)
        val dir = marker.parentFile ?: return
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create backup index directory" }
        FileOutputStream(marker).use { out ->
            out.write(System.currentTimeMillis().toString().toByteArray())
            out.fd.sync()
        }
    }

    fun clear(filesDir: File) {
        markerFile(filesDir).delete()
    }
}
