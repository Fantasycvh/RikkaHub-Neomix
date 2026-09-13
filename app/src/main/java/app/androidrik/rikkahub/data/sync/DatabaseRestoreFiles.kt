package app.androidrik.rikkahub.data.sync

import java.io.File

/**
 * 数据库恢复的**纯文件**操作（与 Android/SQLite 无关，可 JVM 单测）。
 *
 * 与 [BackupArchiveEngine] 配合：后者负责「关库」等平台动作，这里只做
 * 「清理陈旧 -wal/-shm」与「把暂存文件换到位」这类文件系统操作。
 *
 * **背景**：SQLite 以 WAL 模式运行时，`rikka_hub.db` 旁边可能有 `rikka_hub-wal`
 * / `rikka_hub-shm`。恢复时若只覆盖主库而备份未带 `-wal`（例如上游导出包只含主库），
 * 残留的本地 WAL 会在下次打开时被回放，把刚恢复的数据整体盖掉。因此交换主库前
 * 必须清掉这两个同名伴随文件。
 */
internal object DatabaseRestoreFiles {

    /** SQLite WAL 模式下主库的伴随文件（恢复主库前必须清理）。 */
    fun sidecarSuffixes(): List<String> = listOf("-wal", "-shm")

    /**
     * 删除 [dbDir] 下 [dbName] 的伴随文件（`-wal`/`-shm`），不存在则忽略。
     *
     * @return 实际删除的文件名列表（供日志/测试断言）
     */
    fun deleteSidecarFiles(dbDir: File, dbName: String): List<String> {
        val deleted = mutableListOf<String>()
        sidecarSuffixes().forEach { suffix ->
            val f = File(dbDir, "$dbName$suffix")
            if (f.exists() && f.delete()) deleted += f.name
        }
        return deleted
    }

    /**
     * 把 [source] 换到 [target]。先删目标再 `renameTo`；跨文件系统导致 rename 失败时
     * 退回「复制 + 删源」。无论走哪条路，成功后 [source] 都不再存在。
     */
    fun moveIntoPlace(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        source.delete()
    }
}
