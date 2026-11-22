package app.androidrik.rikkahub.data.files

import java.io.File

/**
 * 文件→会话关联表（message_file_ref）的**存量回填完成标记**（design-file-conversation-link.md §4.4）。
 *
 * 语义与 FtsRebuildPending 相反：这里用「done 标记」——文件不存在 = 待回填。
 * 新装/升级（v80 新表为空）首次 Ready 后后台扫一遍全量灌入；恢复换库（快照可能不含
 * 本表或来自旧版本）由恢复路径 [clear] 掉标记，下次启动重跑（全量重写幂等）。
 */
object FileRefBackfillPending {
    private const val FILE_NAME = "file_ref_backfill_done"

    fun isDone(filesDir: File): Boolean = File(filesDir, FILE_NAME).exists()

    fun markDone(filesDir: File) {
        runCatching { File(filesDir, FILE_NAME).writeText("ok") }
    }

    fun clear(filesDir: File) {
        runCatching { File(filesDir, FILE_NAME).delete() }
    }
}
