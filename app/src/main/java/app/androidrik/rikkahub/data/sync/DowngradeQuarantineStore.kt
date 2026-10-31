package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * 数据库降级隔离（设计稿 `docs/design/design-db-downgrade-rebuild.md`）。
 *
 * 应用被降级安装后，现库的 schema 版本高于本应用能开的版本——Room 一开就死。本对象在
 * **startKoin 之前**（doBoot 第 0 步，与 [PendingRestoreStore] 同一封闭窗口）把降级库三件套
 * **copy** 到 `filesDir/downgrade_pending/<millis>/` 并删除原件，让 Room 按当前版本全新建库。
 * 原数据留档，升级回对应版本后经 `stageForRestore` 转接进 PendingRestoreStore 的完整
 * 校验/回滚管线恢复。
 *
 * 目录约定（状态由文件存在性表达）：
 * - `tmp-<millis>/`      ⇒ 隔离进行中（未 commit）；每次隔离开始先清残留；
 * - `<millis>/`          ⇒ 已 commit 的隔离件（目录改名即 commit，marker 先于改名写入）；
 * - `<millis>/dismissed.marker` ⇒ 用户对恢复提示选择了「不再提示」。
 *
 * **fail-closed 与选 copy 不选 move 的理由**（设计稿 §4.2）：move 到一半崩溃会留下
 * 「主库已走、wal 残留原地」的窗口，陈旧 WAL 对着新建空库回放会造出混合数据；copy 期间
 * 原件始终完整，最坏情况是多占一份空间。copy 校验失败抛异常（原件未动、tmp 清理），
 * 由调用方进 `StartupState.Failed(canRetry = true)`——磁盘满等瞬时故障重试即可。
 *
 * 本对象只依赖 File 与 kotlinx.serialization（不碰 android.*），可 JVM 单测；
 * `PRAGMA user_version` 读取由调用方经 [quarantineIfDowngraded] 的 readVersion lambda 注入
 * （生产用 SQLiteDatabase，测试用假实现）。
 */
object DowngradeQuarantineStore {
    private const val DIR = "downgrade_pending"
    private const val TMP_PREFIX = "tmp-"
    private const val MARKER = "quarantine.marker"
    private const val DISMISSED = "dismissed.marker"

    /** 隔离件内的主库文件名 = 现库文件名（const 内联，JVM 单测不初始化 AppDatabase）。 */
    private const val DATABASE_FILE = app.androidrik.rikkahub.data.db.AppDatabase.NAME

    /** 滚动保留份数，与 A 档 safety_backup 对齐（2026-10-31 拍板）。 */
    private const val KEEP = 3

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class QuarantineMarker(
        /** 隔离时现库的 schema 版本（PRAGMA user_version）。 */
        val schemaVersion: Int,
        /** 隔离时间（epoch millis），即目录名。 */
        val quarantinedAt: Long,
        val databaseSize: Long,
        val walSize: Long? = null,
        val shmSize: Long? = null,
    )

    /** 一份已 commit 的隔离件。[dismissed] 表示用户对启动恢复提示选了「不再提示」。 */
    data class QuarantineEntry(
        val dir: File,
        val marker: QuarantineMarker,
        val dismissed: Boolean,
    )

    fun quarantineDir(filesDir: File): File = File(filesDir, DIR)

    /**
     * doBoot 第 0 步：现库版本高于本应用可开版本时执行隔离。
     *
     * @return 实际发生隔离时的 marker（驱动 Ready 后的一次性告知弹窗）；无降级返回 null。
     * @throws IllegalStateException copy 校验失败等——原件未动，调用方进 Failed(canRetry)。
     */
    @Synchronized
    fun quarantineIfDowngraded(
        filesDir: File,
        databaseFile: File,
        currentSchemaVersion: Int,
        readVersion: (File) -> Int,
    ): QuarantineMarker? {
        cleanupTmpDirs(filesDir)
        if (!databaseFile.isFile) return null
        val version = readVersion(databaseFile)
        if (version <= currentSchemaVersion) return null

        val root = quarantineDir(filesDir)
        check(root.isDirectory || root.mkdirs()) { "Cannot create downgrade quarantine directory" }
        val tmp = File(root, "$TMP_PREFIX${System.currentTimeMillis()}")
        check(tmp.isDirectory || tmp.mkdirs()) { "Cannot create quarantine temp directory" }

        // copy 三件套并逐一比对 size（wal/shm 不存在是正常形态，记 null）
        val main = databaseFile
        val wal = File(databaseFile.path + DatabaseRestoreFiles.sidecarSuffixes()[0])
        val shm = File(databaseFile.path + DatabaseRestoreFiles.sidecarSuffixes()[1])
        copyVerified(main, File(tmp, DATABASE_FILE), "database")
        val walSize = wal.takeIf(File::isFile)?.let { copyVerified(it, File(tmp, "$DATABASE_FILE-wal"), "wal") }
        val shmSize = shm.takeIf(File::isFile)?.let { copyVerified(it, File(tmp, "$DATABASE_FILE-shm"), "shm") }

        val marker = QuarantineMarker(
            schemaVersion = version,
            quarantinedAt = tmp.name.removePrefix(TMP_PREFIX).toLongOrNull() ?: System.currentTimeMillis(),
            databaseSize = main.length(),
            walSize = walSize,
            shmSize = shmSize,
        )
        // marker 先于改名写（sync 落盘）：改名的瞬间隔离件即完整可见
        writeSynced(
            File(tmp, MARKER),
            json.encodeToString(QuarantineMarker.serializer(), marker).toByteArray(),
        )

        // 目录改名 = commit（同卷原子）。此后任何崩溃点状态都收敛：
        // - 改名前死 → tmp 孤儿，下次启动 cleanupTmpDirs 清掉，原件还在可重试；
        // - 改名后、删原件前死 → 原件还在、降级还在 → 下次启动重新隔离（多一份 committed 副本，prune 兜底）。
        val committed = File(root, marker.quarantinedAt.toString())
        check(tmp.renameTo(committed)) { "Cannot commit quarantine directory" }

        // 提交后才删原件，Room 才会按当前版本全新建库
        DatabaseRestoreFiles.deleteSidecarFiles(databaseFile.parentFile!!, databaseFile.name)
        check(databaseFile.delete()) { "Cannot delete downgraded database after quarantine" }

        prune(filesDir)
        return marker
    }

    /** 全部已 commit 的隔离件（含 dismissed），按时间倒序（新者在前）。 */
    fun listEntries(filesDir: File): List<QuarantineEntry> {
        val root = quarantineDir(filesDir)
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && !f.name.startsWith(TMP_PREFIX) }
            .orEmpty()
            .mapNotNull { dir ->
                val marker = runCatching {
                    json.decodeFromString<QuarantineMarker>(File(dir, MARKER).readText())
                }.getOrNull() ?: return@mapNotNull null
                QuarantineEntry(dir, marker, File(dir, DISMISSED).exists())
            }
            .sortedByDescending { it.marker.quarantinedAt }
    }

    /** 升级回归后可恢复的隔离件：版本闸（本应用能开）+ 未被用户忽略。 */
    fun recoverableEntries(filesDir: File, currentSchemaVersion: Int): List<QuarantineEntry> =
        listEntries(filesDir)
            .filter { it.marker.schemaVersion in 1..currentSchemaVersion && !it.dismissed }

    /** 用户对启动恢复提示选「不再提示」：写入 dismissed.marker，扫描时跳过。 */
    @Synchronized
    fun markDismissed(filesDir: File, dirName: String) {
        val dir = File(quarantineDir(filesDir), dirName)
        check(dir.isDirectory) { "Quarantine entry not found: $dirName" }
        if (!File(dir, DISMISSED).exists()) writeSynced(File(dir, DISMISSED), ByteArray(0))
    }

    /** 删除一份隔离件（设置页手动清理空间用；payload 残缺的孤儿也走这里）。 */
    @Synchronized
    fun deleteEntry(filesDir: File, dirName: String) {
        val dir = File(quarantineDir(filesDir), dirName)
        check(dir.isDirectory) { "Quarantine entry not found: $dirName" }
        check(dir.deleteRecursively()) { "Cannot delete quarantine entry: $dirName" }
    }

    /**
     * 恢复：把隔离件三件套转接进 [PendingRestoreStore.stage]（move 语义），隔离件目录随之清掉。
     * 真正换库发生在**下一次启动**——验证（user_version + integrity_check）、rollback、
     * 开库确认全部复用既有管线（设计稿 §5）。调用方随后应触发重启（RestoreRestartSignal）。
     *
     * @throws IllegalStateException 已有待应用换库 / 版本闸不过 / payload 与 marker 对不上。
     */
    @Synchronized
    fun stageForRestore(filesDir: File, dirName: String, currentSchemaVersion: Int) {
        check(!PendingRestoreStore.hasPending(filesDir)) {
            "A restore is already pending; restart the app first"
        }
        val entry = listEntries(filesDir).firstOrNull { it.dir.name == dirName }
            ?: throw IllegalStateException("Quarantine entry not found: $dirName")
        check(entry.marker.schemaVersion in 1..currentSchemaVersion) {
            "Quarantine entry is from an incompatible app version (${entry.marker.schemaVersion})"
        }
        val stagedMain = File(entry.dir, DATABASE_FILE)
        check(stagedMain.isFile && stagedMain.length() == entry.marker.databaseSize) {
            "Quarantine entry payload is missing or incomplete (database)"
        }
        val stagedWal = entry.marker.walSize?.let {
            val f = File(entry.dir, "$DATABASE_FILE-wal")
            check(f.isFile && f.length() == it) { "Quarantine entry payload is missing or incomplete (wal)" }
            f
        }
        val stagedShm = entry.marker.shmSize?.let {
            val f = File(entry.dir, "$DATABASE_FILE-shm")
            check(f.isFile && f.length() == it) { "Quarantine entry payload is missing or incomplete (shm)" }
            f
        }
        PendingRestoreStore.stage(filesDir, stagedMain, stagedWal, stagedShm)
        // move 语义：三件套已走，只剩 marker/ dismissed 等元数据，整目录清掉
        entry.dir.deleteRecursively()
    }

    /** 滚动保留 [KEEP] 份：按隔离时间删最旧。 */
    private fun prune(filesDir: File) {
        listEntries(filesDir).drop(KEEP).forEach { it.dir.deleteRecursively() }
    }

    /** 清理未 commit 的 tmp 孤儿目录（隔离中途崩溃的残留）。 */
    private fun cleanupTmpDirs(filesDir: File) {
        val root = quarantineDir(filesDir)
        if (!root.isDirectory) return
        root.listFiles { f -> f.isDirectory && f.name.startsWith(TMP_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    private fun copyVerified(source: File, target: File, what: String): Long {
        source.copyTo(target, overwrite = true)
        check(target.length() == source.length()) { "Quarantine copy size mismatch ($what)" }
        return target.length()
    }

    private fun writeSynced(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}
