package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * B 档备份恢复的启动期换库（设计稿 `docs/design/design-backup-staged-restore.md`，参照
 * Yuihub 3.0.1 `PendingRestoreStore`）。
 *
 * 恢复流程只把换库家族**暂存**进 `filesDir/restore_pending/` 并最后原子写 `pending.marker`；
 * 真正换库发生在**下次启动、startKoin 之前**（那时 Room 未打开、没有后台写者，失败窗口天然封闭）。
 *
 * 状态由文件存在性表达，不需要额外存储：
 * - `pending.marker` 在                    ⇒ 有待应用的换库；
 * - `restore_applied/applied.marker` 在     ⇒ 换库已完成，等 Room 打开成功后确认；
 * - applied + rollback 满员 + Room 打开失败 ⇒ [rollbackIfUnconfirmed] 自动回滚。
 *
 * **fail-closed**：任何失败都不清 marker、不动现库；marker 永远最后写。
 *
 * 本对象只依赖 File 与 kotlinx.serialization（不碰 android.*），可 JVM 单测；
 * 入库校验（integrity_check/user_version）由调用方经 [applyIfPending] 的 validate lambda 注入
 * （生产实现用 SQLiteDatabase，测试用 fake——Android SQLiteDatabase 在 JVM 不可用）。
 */
object PendingRestoreStore {
    private const val PENDING_DIR = "restore_pending"
    private const val STAGED_DB = "staged.db"
    private const val ROLLBACK_DB = "rollback.db"
    private const val MARKER = "pending.marker"
    private const val APPLIED_DIR = "restore_applied"
    private const val APPLIED_MARKER = "applied.marker"

    private val json = Json { ignoreUnknownKeys = true }

    /** pending.marker 的内容：换库家族各文件的大小（size 对不上 = payload 残缺，fail-closed）。 */
    @Serializable
    private data class PendingManifest(
        val databaseSize: Long = 0,
        val databaseWalSize: Long? = null,
        val databaseShmSize: Long? = null,
    )

    /** applied.marker 的内容：换库前现库是否存在（决定回滚方向是恢复还是删除）。 */
    @Serializable
    private data class RollbackMarker(val hadDatabase: Boolean = true)

    fun pendingDir(filesDir: File): File = File(filesDir, PENDING_DIR)

    private fun appliedDir(filesDir: File): File = File(filesDir, APPLIED_DIR)

    private fun stagedDb(filesDir: File): File = File(pendingDir(filesDir), STAGED_DB)

    private fun markerFile(filesDir: File): File = File(pendingDir(filesDir), MARKER)

    fun hasPending(filesDir: File): Boolean = markerFile(filesDir).exists()

    fun hasApplied(filesDir: File): Boolean = File(appliedDir(filesDir), APPLIED_MARKER).exists()

    /**
     * 恢复流程收尾：接管 staged 家族（move 语义，源文件不再存在）并在**最后**原子写 marker。
     * marker 后写 ⇒ 不存在「marker 有、payload 无」状态；pending 未消费前二次 stage 拒绝。
     */
    @Synchronized
    fun stage(filesDir: File, stagedMain: File, stagedWal: File?, stagedShm: File?) {
        check(!hasPending(filesDir)) { "A restore is already pending; restart the app first" }
        check(stagedMain.isFile && stagedMain.length() > 0) { "Staged database is missing or empty" }
        val dir = pendingDir(filesDir)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create pending restore directory" }
        DatabaseRestoreFiles.moveIntoPlace(stagedMain, File(dir, STAGED_DB))
        stagedWal?.takeIf(File::isFile)?.let { DatabaseRestoreFiles.moveIntoPlace(it, File(dir, "$STAGED_DB-wal")) }
        stagedShm?.takeIf(File::isFile)?.let { DatabaseRestoreFiles.moveIntoPlace(it, File(dir, "$STAGED_DB-shm")) }
        val main = stagedDb(filesDir)
        val manifest = PendingManifest(
            databaseSize = main.length(),
            databaseWalSize = File(dir, "$STAGED_DB-wal").takeIf(File::isFile)?.length(),
            databaseShmSize = File(dir, "$STAGED_DB-shm").takeIf(File::isFile)?.length(),
        )
        val markerTemp = File(dir, "$MARKER.new")
        try {
            writeSynced(markerTemp, json.encodeToString(PendingManifest.serializer(), manifest).toByteArray())
            check(markerTemp.renameTo(markerFile(filesDir))) { "Cannot persist pending-restore marker" }
        } finally {
            markerTemp.delete()
        }
    }

    /** 换库已在早前完成（applied 在）但 pending 是残留：只清残留，转确认态。 */
    fun clearPending(filesDir: File) {
        markerFile(filesDir).delete()
        pendingDir(filesDir).deleteRecursively()
    }

    /**
     * 启动早期调用（startKoin 之前）。返回是否有换库被应用。
     *
     * 崩溃点覆盖：
     * - applied + pending 残留（applied 写完、pending 未清时死了）→ 只清残留；
     * - 让位后、换入前死了（rollback 满员 + pending 在）→ 先归位再重走；
     * - 换入后、applied.marker 写前死了（staged 已 move 走、现库已是新库）→ 补写 applied
     *   转入确认态（不得走 rollback 归位，那会把旧库盖回新库上）；
     * - validate 失败 / payload 残缺 → 抛错，现库未动、marker 保留（可重试）。
     */
    @Synchronized
    fun applyIfPending(filesDir: File, databaseFile: File, validate: (File) -> Unit): Boolean {
        if (!hasPending(filesDir)) return false
        if (hasApplied(filesDir)) {
            clearPending(filesDir)
            return true
        }
        val dir = pendingDir(filesDir)
        val staged = stagedDb(filesDir)
        val manifest = runCatching {
            json.decodeFromString<PendingManifest>(markerFile(filesDir).readText())
        }.getOrElse { throw IllegalStateException("Pending restore marker is corrupt", it) }

        if (!staged.isFile) {
            // staged 已消失：唯一来源是上次运行走到了换入那步（move 语义）。
            // 现库在场 ⇒ 换入实际已完成，补 applied.marker 进确认态；现库也不在场 ⇒ payload
            // 彻底丢失，若有 rollback 副本先归位并放弃本次恢复——锁死启动屏比放弃恢复更糟。
            if (databaseFile.isFile) {
                completeInterruptedSwapIn(filesDir, dir, manifest, databaseFile)
                return true
            }
            if (File(appliedDir(filesDir), ROLLBACK_DB).isFile) {
                recoverRollback(filesDir, databaseFile)
                clearPending(filesDir)
                return false
            }
            throw IllegalStateException("Pending database is missing and no swapped-in database found")
        }
        check(manifest.databaseSize > 0 && staged.length() == manifest.databaseSize) {
            "Pending database is missing or incomplete"
        }
        listOf("-wal" to manifest.databaseWalSize, "-shm" to manifest.databaseShmSize).forEach { (suffix, size) ->
            if (size != null) {
                val sidecar = File(dir, "$STAGED_DB$suffix")
                check(sidecar.isFile && sidecar.length() == size) { "Pending database sidecar$suffix is missing" }
            }
        }

        // 上次启动死在「让位后、换入前」：rollback 里是原库，先归位（幂等：缺哪补哪）
        recoverRollback(filesDir, databaseFile)

        // 入库校验必须先于任何现库变更（fail-closed）
        validate(staged)

        val rollbackDir = appliedDir(filesDir)
        check(rollbackDir.isDirectory || rollbackDir.mkdirs()) { "Cannot create rollback directory" }
        val hadDatabase = databaseFile.isFile
        if (hadDatabase) {
            // 改名让位（同卷瞬时原子；这是本设计对 Yuihub 整库复制的偏差——118MB 零拷贝）
            DatabaseRestoreFiles.moveIntoPlace(databaseFile, File(rollbackDir, ROLLBACK_DB))
            DatabaseRestoreFiles.sidecarSuffixes().forEach { suffix ->
                val sidecar = File(databaseFile.path + suffix)
                if (sidecar.isFile) DatabaseRestoreFiles.moveIntoPlace(sidecar, File(rollbackDir, ROLLBACK_DB + suffix))
            }
        } else {
            // 主库不存在时伴随文件只是垃圾（SQLite 不会为不存在的库留 WAL），删掉防陈旧 WAL 回放
            databaseFile.parentFile?.let { DatabaseRestoreFiles.deleteSidecarFiles(it, databaseFile.name) }
        }

        DatabaseRestoreFiles.moveIntoPlace(staged, databaseFile)
        listOf("-wal" to manifest.databaseWalSize, "-shm" to manifest.databaseShmSize).forEach { (suffix, size) ->
            if (size != null) {
                DatabaseRestoreFiles.moveIntoPlace(File(dir, "$STAGED_DB$suffix"), File(databaseFile.path + suffix))
            }
        }

        writeSynced(
            File(rollbackDir, APPLIED_MARKER),
            json.encodeToString(RollbackMarker.serializer(), RollbackMarker(hadDatabase)).toByteArray(),
        )
        clearPending(filesDir)
        return true
    }

    /** App 正常启动（Room 打开成功）后调用：删除回滚副本，恢复流程闭环。 */
    fun confirmApplied(filesDir: File) {
        appliedDir(filesDir).deleteRecursively()
    }

    /**
     * 换入已完成、applied.marker 未写就死了：把残留的 staged sidecar 归位、补写 applied.marker
     * 转入确认态。rollback 里是旧库，Room 打开成功后 [confirmApplied] 删之，打开失败则由
     * [rollbackIfUnconfirmed] 回滚——与正常换入完全同一条收尾路径。
     */
    private fun completeInterruptedSwapIn(
        filesDir: File,
        dir: File,
        manifest: PendingManifest,
        databaseFile: File,
    ) {
        val rollbackDir = appliedDir(filesDir)
        check(rollbackDir.isDirectory || rollbackDir.mkdirs()) { "Cannot create rollback directory" }
        listOf("-wal" to manifest.databaseWalSize, "-shm" to manifest.databaseShmSize).forEach { (suffix, size) ->
            if (size != null) {
                val sidecar = File(dir, "$STAGED_DB$suffix")
                if (sidecar.isFile) DatabaseRestoreFiles.moveIntoPlace(sidecar, File(databaseFile.path + suffix))
            }
        }
        writeSynced(
            File(rollbackDir, APPLIED_MARKER),
            json.encodeToString(
                RollbackMarker.serializer(),
                RollbackMarker(hadDatabase = File(rollbackDir, ROLLBACK_DB).isFile)
            ).toByteArray(),
        )
        clearPending(filesDir)
    }

    /**
     * 上次换库后应用没能活着确认（applied 在、rollback 还在）：自动回滚。
     * [rollbackIfUnconfirmed] 失败会抛出——调用方 runCatching 记日志后照常初始化
     * （一个可能损坏的库好过白屏；此时 safety_backup 仍在）。
     */
    @Synchronized
    fun rollbackIfUnconfirmed(filesDir: File, databaseFile: File) {
        if (!hasApplied(filesDir)) return
        val rollbackDir = appliedDir(filesDir)
        val payload = runCatching {
            json.decodeFromString<RollbackMarker>(File(rollbackDir, APPLIED_MARKER).readText())
        }.getOrDefault(RollbackMarker())
        if (payload.hadDatabase) {
            if (!File(rollbackDir, ROLLBACK_DB).isFile) {
                // rollback 副本缺失但 applied.marker 还在：只可能是 [confirmApplied] 的
                // deleteRecursively 删了一半（它只在 Room 打开成功后运行，新库已验证可用）。
                // 无法回滚也无需回滚——清残留即可，否则每次启动都重复抛 "Rollback database is missing"。
                confirmApplied(filesDir)
                return
            }
            listOf("", "-wal", "-shm").forEach { suffix ->
                val target = File(databaseFile.path + suffix)
                target.delete() // 先清换入的，防陈旧 WAL 回放
                val source = File(rollbackDir, ROLLBACK_DB + suffix)
                if (source.isFile) DatabaseRestoreFiles.moveIntoPlace(source, target)
            }
        } else {
            listOf("", "-wal", "-shm").forEach { suffix -> File(databaseFile.path + suffix).delete() }
        }
        confirmApplied(filesDir)
    }

    private fun recoverRollback(filesDir: File, databaseFile: File) {
        val rollbackDir = appliedDir(filesDir)
        if (!rollbackDir.isDirectory || hasApplied(filesDir)) return
        listOf("", "-wal", "-shm").forEach { suffix ->
            val source = File(rollbackDir, ROLLBACK_DB + suffix)
            if (source.isFile) DatabaseRestoreFiles.moveIntoPlace(source, File(databaseFile.path + suffix))
        }
        rollbackDir.deleteRecursively()
    }

    private fun writeSynced(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}
