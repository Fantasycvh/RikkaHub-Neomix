package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.utils.fileSizeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BackupArchive"

/** Writes the standard backup format without owning SettingsStore or Room. */
internal class BackupArchiveWriter(
    private val context: Context,
    private val json: Json,
    // When absent, the caller has paused startup and can copy the offline database and its journal.
    private val vacuumDatabase: ((File) -> Unit)? = null,
) {
    suspend fun prepare(
        items: Collection<BackupItem>,
        scope: BackupScope,
        onProgress: (BackupProgress) -> Unit = {},
        readSettings: suspend () -> Settings,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        // 空间预检：保守按「输入不压缩」估算；不足则提前失败（2.8GB 跑到一半失败代价太大）
        val plannedInputs = collectBackupInputs(items, scope)
        val estimatedBytes = BackupSpaceCheck.estimateBackupBytes(plannedInputs)
        val available = context.cacheDir.usableSpace
        if (!BackupSpaceCheck.hasEnoughSpace(available, estimatedBytes)) {
            throw IllegalStateException(
                "Not enough cache space for backup: need ~${estimatedBytes.fileSizeToString()}, " +
                    "available ${available.fileSizeToString()}"
            )
        }
        Log.i(
            TAG,
            "prepareBackupFile: space check ok (need ~${estimatedBytes.fileSizeToString()}, " +
                "available ${available.fileSizeToString()})",
        )

        val throttle = ProgressThrottle()
        var processedBytes = 0L
        fun report(delta: Long) {
            coroutineContext.ensureActive()
            processedBytes += delta
            val progress = BackupProgress(BackupPhase.PACKING, processedBytes, estimatedBytes)
            if (throttle.shouldEmit(progress)) onProgress(progress)
        }

        val backupFile = if (vacuumDatabase == null) {
            File.createTempFile("backup_recovery_$timestamp-", ".zip", context.cacheDir)
        } else {
            File(context.cacheDir, "backup_$timestamp.zip")
        }
        try {
            ManifestZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
                // settings.json 恒含（首个 section；manifest 记其 SHA-256 供恢复侧校验）。
                // scope 排除的类别在 JSON 键级剔除（BackupContentFilter），恢复侧据此回填本机值；
                // 设备态字段（launchCount 等）再经 BackupPortability 剥离——备份只描述用户数据。
                val settingsContent = BackupContentFilter
                    .filterSettingsJson(
                        settings = json.encodeToJsonElement(readSettings()).jsonObject,
                        scope = scope,
                    )
                    .let(BackupPortability::portable)
                    .toString()
                val settingsRaw = settingsContent.toByteArray(Charsets.UTF_8)
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = settingsContent,
                    onBytes = ::report,
                )

                var databaseSha256: String? = null
                var databaseBytes: Long? = null
                if (BackupItem.DATABASE in items) {
                    val (sha, bytes) = addDatabaseToZip(zipOut, ::report)
                    if (bytes > 0L) {
                        databaseSha256 = sha
                        databaseBytes = bytes
                    }
                    // bytes == 0（新装设备库尚未创建）：manifest 保持 null——写成 ""/0 会让恢复侧
                    // 校验永远失败（空库备份变成死包），见 verifyBackupManifest 的可空约定
                }
                if (BackupItem.FILES in items) {
                    addAppFilesToZip(zipOut, scope, ::report)
                }
                if (BackupItem.WORKSPACE_FILES in items) {
                    val wsDir = WorkspaceBackupHelper.workspacesDir(context)
                    val roots = WorkspaceBackupHelper.listWorkspaceRoots(wsDir)
                    val count = WorkspaceBackupHelper.addWorkspaceFilesToZip(
                        zipOut = zipOut,
                        workspaceBaseDir = wsDir,
                        roots = roots,
                        onBytes = ::report,
                    )
                    Log.i(TAG, "prepareBackupFile: backed up $count workspace file(s) from ${roots.size} workspace(s)")
                }

                // manifest v3（v2 基础上新增 scope）：zip 末条目。
                // 恢复侧读毕统一校验（双 SHA-256 + 各 section 条目数），通过才执行破坏性步骤。
                val manifest = BackupManifest(
                    createdAt = timestamp,
                    items = items.distinct(),
                    sections = zipOut.sectionStats(),
                    settingsSha256 = sha256Hex(settingsRaw),
                    settingsBytes = settingsRaw.size.toLong(),
                    databaseSha256 = databaseSha256,
                    databaseBytes = databaseBytes,
                    scope = scope,
                )
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = BackupManifest.ZIP_ENTRY,
                    content = json.encodeToString(manifest),
                    onBytes = ::report,
                )
            }
            coroutineContext.ensureActive()
            onProgress(BackupProgress(BackupPhase.PACKING, estimatedBytes, estimatedBytes))
        } catch (failure: Throwable) {
            backupFile.delete()
            throw failure
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created ${backupFile.name} (${backupFile.length().fileSizeToString()})",
        )
        backupFile
    }

    /**
     * 备份将读取的源（用于空间预检与进度分母）。
     * 与打包分支一一对应：DB 三件套 / 应用文件区 / 工作区文件区。
     */
    private fun collectBackupInputs(items: Collection<BackupItem>, scope: BackupScope): List<File> {
        val inputs = mutableListOf<File>()
        if (BackupItem.DATABASE in items) {
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            inputs += dbFile
            DatabaseRestoreFiles.sidecarSuffixes().forEach { suffix ->
                inputs += File(dbFile.path + suffix)
            }
        }
        if (BackupItem.FILES in items) {
            // 目录清单统一由 BACKED_UP_FILE_FOLDERS 驱动（见其注释），三处共用不再手列；
            // v4 起再按 BackupScope 的文件分组过滤（空间预检与打包同一口径）
            BACKED_UP_FILE_FOLDERS
                .filter { scope.includesFileFolder(it, ::backupFileGroupFor) }
                .forEach { inputs += File(context.filesDir, it) }
        }
        if (BackupItem.WORKSPACE_FILES in items) {
            val wsDir = WorkspaceBackupHelper.workspacesDir(context)
            WorkspaceBackupHelper.listWorkspaceRoots(wsDir).forEach { root ->
                inputs += File(wsDir, "$root/files")
            }
        }
        return inputs
    }

    private class ManifestZipOutputStream(out: java.io.OutputStream) : ZipOutputStream(out) {
        private val entries = mutableMapOf<BackupManifestSection, Int>()
        private val bytes = mutableMapOf<BackupManifestSection, Long>()
        private var currentSection: BackupManifestSection? = null

        override fun putNextEntry(entry: ZipEntry) {
            currentSection = classifyBackupEntry(entry.name)
            currentSection?.let { entries.merge(it, 1, Int::plus) }
            super.putNextEntry(entry)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            currentSection?.let { bytes.merge(it, len.toLong(), Long::plus) }
            super.write(b, off, len)
        }

        fun sectionStats(): Map<BackupManifestSection, BackupSectionStats> =
            BackupManifestSection.entries.associateWith { section ->
                BackupSectionStats(
                    entries = entries.getOrDefault(section, 0),
                    uncompressedBytes = bytes.getOrDefault(section, 0L),
                )
            }.filterValues { it.entries > 0 || it.uncompressedBytes > 0 }
    }

    private fun addDatabaseToZip(zipOut: ZipOutputStream, onBytes: (Long) -> Unit): Pair<String, Long> {
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        if (!dbFile.exists()) return "" to 0L

        // 一致性快照（Yuihub `47e7dfaf` 对照）：VACUUM INTO 在**单个读事务**里导出，
        // 天然包含已提交的 WAL 内容——比热拷 db/wal/shm 三件套稳（拷贝期间写入不可见，
        // 且可能拷到半页）。09-15 的 WAL 修复只治了恢复方向，备份方向的一致性由此补齐。
        // VACUUM INTO 需要 SQLite 3.27+（API 30+）；minSdk 26 的老设备或异常时回退热拷，
        // 恢复路径两种形态都已处理（db-only 时恢复前会清陈旧 -wal/-shm）。
        val snapshotDir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "backup-db-").toFile()
        try {
            val snapshot = File(snapshotDir, "rikka_hub.db")
            val vacuumed = vacuumDatabase?.let { vacuum ->
                try {
                    vacuum(snapshot)
                    snapshot.isFile && snapshot.length() > 0
                } catch (failure: Exception) {
                    Log.w(TAG, "addDatabaseToZip: VACUUM INTO failed, falling back to file copy", failure)
                    false
                }
            } ?: false
            if (!vacuumed) {
                // Recover on the copy so SQLite never opens the source in recovery mode.
                dbFile.copyTo(snapshot, overwrite = true)
                val sidecars = if (vacuumDatabase == null) listOf("-wal", "-journal")
                else DatabaseRestoreFiles.sidecarSuffixes()
                sidecars.forEach { suffix ->
                    File(dbFile.path + suffix).takeIf { it.exists() }?.copyTo(File(snapshot.path + suffix), overwrite = true)
                }
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    snapshot.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Backup snapshot WAL recovery failed" }
                    }
                    db.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true))
                    }
                    if (vacuumDatabase == null) {
                        check(db.version in 1..AppDatabase.SCHEMA_VERSION) { "Unsupported recovery database version" }
                    }
                }
            }
            validateBackupDatabase(snapshot)
            if (vacuumDatabase == null) {
                check(LegacyBackupConverter.classify(snapshot) != LegacyDatabaseFamily.UNKNOWN) {
                    "Unknown recovery database format"
                }
            }
            val mainSha = sha256Of(snapshot)
            addFileToZip(zipOut, snapshot, "rikka_hub.db", onBytes, ::backupLog)
            return mainSha to snapshot.length()
        } finally {
            snapshotDir.deleteRecursively()
        }
    }

    /**
     * files 下由 [BACKED_UP_FILE_FOLDERS] 声明的用户数据目录（打包形态/递归由
     * `BackupFolderRegistry` 的 `BACKUP_FOLDER_SPECS` 单一真源决定；恢复分派同理）。
     *
     * **不含 tool_outputs**（2026-09-15 决策，选项①）：它是会话级临时产物——[cleanupToolOutputs]
     * 在每次启动时清空该目录，恢复流程末尾必然重启，刚恢复的文件立刻又被删；打包它只是徒增
     * zip 体积。历史消息里 `/tool_outputs/<id>.txt` 的引用本就可能悬空，截断消息已内联前 4KB
     * 预览（`TOOL_OUTPUT_PREVIEW_CHARS`）兜底。
     */
    private fun addAppFilesToZip(zipOut: ZipOutputStream, scope: BackupScope, onBytes: (Long) -> Unit) {
        // 打包实现移到顶层 packAppFiles（可注入 filesDir ⇒ 能被 JVM 测试覆盖，
        // 见 AppFilesPacking.kt 顶部记录的真机事故）；v4 起按 BackupScope 文件分组过滤
        packAppFiles(
            filesDir = context.filesDir,
            zipOut = zipOut,
            plan = appFilesPackingPlan(
                folders = BACKED_UP_FILE_FOLDERS.filter { scope.includesFileFolder(it, ::backupFileGroupFor) },
            ),
            onBytes = onBytes,
            onLog = { message -> Log.i(TAG, message) },
        )
    }

    private fun addVirtualFileToZip(
        zipOut: ZipOutputStream,
        name: String,
        content: String,
        onBytes: (Long) -> Unit = {},
    ) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(bytes)
        zipOut.closeEntry()
        onBytes(bytes.size.toLong())
    }

    /** 打包实现的日志出口（顶层函数不依赖 android.util.Log，由引擎统一接线）。 */
    private fun backupLog(message: String) = Log.i(TAG, message)
}

/** 流式 SHA-256（数据库可能数百 MB，不能 readBytes 进内存）。 */
internal fun sha256Of(file: File): String =
    java.security.MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
