package app.androidrik.rikkahub.data.sync

import java.io.File

/**
 * DB **升级前**快照（借鉴 NMoi `65e13bf`「迁移前留一份副本」，台账
 * `docs/references/other projects/nmoi-agent.md` §13.4 候选 4）。
 *
 * 降级已有隔离重建（[DowngradeQuarantineStore]），但**升级路径**此前没有任何安全网：
 * Room 迁移链一旦中途失败，主库停在半迁移状态且无副本可回。本对象在 Room 打开前
 * （doBoot 的封闭窗口内）对「版本落后于目标」的库做一份 VACUUM INTO 快照，
 * 迁移失败时至少留有迁移前数据。
 *
 * 目录约定：`filesDir/migration_snapshots/pre-migration-v<from>.bak`（单文件，非目录——
 * 快照由 VACUUM INTO 生成、本身是完整一致库，无需三件套）。
 *
 * 语义：
 * - **fail-open**：快照失败只记日志不拦启动（调用方 runCatching）——失败快照 + 成功迁移
 *   = 无损；失败快照 + 失败迁移 = 与没有本特性等价；拦启动反而把增强变故障。
 * - **只留最新一份**：新快照 commit 后清旧份与残留 tmp（快照是「上一个版本」的
 *   取证物，多份只有磁盘成本；真正的备份管线走 BackupArchiveEngine）。
 * - 版本 0（全新库）/ 版本已达标 = 无迁移发生，不快照。
 *
 * 本对象只依赖 File（不碰 android.*），可 JVM 单测；`PRAGMA user_version` 读取与
 * VACUUM INTO 执行由调用方注入（生产用 SQLiteDatabase，测试用假实现）——与
 * [DowngradeQuarantineStore] 的 readVersion lambda 同款做法。
 */
object MigrationSnapshotStore {
    private const val DIR = "migration_snapshots"
    private const val PREFIX = "pre-migration-v"
    private const val SUFFIX = ".bak"
    private const val TMP_SUFFIX = ".tmp-"
    private const val KEEP = 1

    /**
     * 需要升级（`0 < version < targetVersion`）时把 [dbFile] 快照到快照目录，返回快照文件；
     * 无需快照返回 null。`readVersion`/`vacuumInto` 由调用方注入。
     */
    fun snapshotBeforeUpgrade(
        filesDir: File,
        dbFile: File,
        targetVersion: Int,
        readVersion: (File) -> Int,
        vacuumInto: (sourcePath: String, targetPath: String) -> Unit,
    ): File? {
        if (!dbFile.isFile) return null
        val version = readVersion(dbFile)
        if (version <= 0 || version >= targetVersion) return null
        val dir = File(filesDir, DIR).apply { mkdirs() }
        // 先清残留 tmp（上次快照中途崩溃的孤儿件）
        dir.listFiles()?.filter { it.name.contains(TMP_SUFFIX) }?.forEach { it.delete() }
        val final = File(dir, "$PREFIX$version$SUFFIX")
        if (final.isFile) return final // 同版本已有快照（上次升级中断后重试），直接复用
        val tmp = File(dir, "$PREFIX$version$SUFFIX$TMP_SUFFIX${System.currentTimeMillis()}")
        try {
            vacuumInto(dbFile.absolutePath, tmp.absolutePath)
            if (!tmp.isFile || tmp.length() == 0L) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(final)) {
                tmp.delete()
                return null
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        prune(dir, keep = KEEP)
        return final
    }

    /** 快照目录保留最新 [keep] 份，其余删除（纯文件操作，便于单测）。 */
    internal fun prune(dir: File, keep: Int) {
        val snapshots = dir.listFiles()
            ?.filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: return
        snapshots.drop(keep).forEach { it.delete() }
    }
}
