package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.account.renewCatalogSessions
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.datastore.migration.SettingsCompatDecoder
import app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FileRefBackfillPending
import app.androidrik.rikkahub.data.files.SkillPaths
import app.androidrik.rikkahub.data.sync.repo.DatabaseObjectCapture
import app.androidrik.rikkahub.data.sync.repo.DatabaseObjectMerge
import app.androidrik.rikkahub.utils.fileSizeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 备份归档引擎：把「打包成 zip」与「从 zip 恢复」的逻辑集中一处。
 *
 * **背景**：原先这套逻辑在 `S3Sync` 与 `WebDavSync` 各有一份近乎逐字复制粘贴的实现
 * （`prepareBackupFile` 81 行仅 6 行不同、`restoreFromBackupFile` 仅 10 行不同、
 * zip 辅助函数 100% 相同）。每新增一项备份内容都要改两遍且极易漏改。
 * 现统一到本类，两个 Sync 只保留各自的**传输**逻辑并委托到这里。
 *
 * 参数化为 `items: Collection<BackupItem>` 而非整个 config，从而与传输层解耦（S3/WebDav 通用）。
 *
 * 设计见 `docs/design/design-backup-workspace-files.md`。
 */
class BackupArchiveEngine(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val appDatabase: AppDatabase,
) {
    private val TAG = "BackupArchive"

    /**
     * 生成备份 zip（写入 cacheDir）。
     *
     * 内容：settings.json（恒含，键按 [scope] 剔除被排除类别）+ 按 [items] 选择性打包
     * DATABASE / FILES / WORKSPACE_FILES。
     *
     * @param scope 备份范围（excluded-set 语义，见 [BackupScope]）；记入 manifest 供恢复侧回填本机值
     * @param onProgress 阶段进度回调（打包阶段按**读入字节**计）；IO 线程调用
     * @throws IllegalStateException cacheDir 空间不足（见 §9.1 空间预检）
     */
    suspend fun prepareBackupFile(
        items: Collection<BackupItem>,
        scope: BackupScope = BackupScope(),
        onProgress: (BackupProgress) -> Unit = {},
    ): File = DataOperationLock.runWaiting(DataOperation.BACKUP) {
        prepareBackupFileLocked(items, scope, onProgress)
    }

    private val archiveWriter = BackupArchiveWriter(context, json) { target ->
        appDatabase.openHelper.writableDatabase.execSQL(
            "VACUUM main INTO ?",
            arrayOf(target.absolutePath),
        )
    }

    private suspend fun prepareBackupFileLocked(
        items: Collection<BackupItem>,
        scope: BackupScope,
        onProgress: (BackupProgress) -> Unit = {},
    ): File = archiveWriter.prepare(items, scope, onProgress) { settingsStore.settingsFlow.value }

    /**
     * 从备份 zip 恢复。
     *
     * @param mode 恢复模式（默认 OVERWRITE = 历史行为）；MERGE 时数据库按会话幂等合并、
     *   settings 的 providers/assistants 按 id 合并，仍走 staged 换库与安全备份
     * @param scopeOverride 用户在恢复确认框临时勾掉的类别/文件分组（exclusion 并入 manifest
     *   scope——只能缩小不能扩大）；null = 按 manifest 原样
     * @param onProgress 解压阶段进度回调（按已处理的未压缩字节计，总量取自 zip 中央目录）
     * @return 恢复结果（工作区文件数 + 设置降级提示 + merge 统计）
     * @throws IllegalStateException 空间不足（解压产物 + zip 需同时存在于 cacheDir，见 §9.1）
     */
    suspend fun restoreFromBackupFile(
        backupFile: File,
        items: Collection<BackupItem>,
        mode: RestoreMode = RestoreMode.OVERWRITE,
        scopeOverride: BackupScope? = null,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome = DataOperationLock.runWaiting(DataOperation.RESTORE) {
        restoreFromBackupFileLocked(backupFile, items, mode, scopeOverride, onProgress)
    }

    private suspend fun restoreFromBackupFileLocked(
        backupFile: File,
        items: Collection<BackupItem>,
        mode: RestoreMode,
        scopeOverride: BackupScope?,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome = restoreBackupArchive(
        context = context,
        json = json,
        backupFile = backupFile,
        items = items,
        mode = mode,
        scopeOverride = scopeOverride,
        onProgress = onProgress,
        createSafetyBackup = { createSafetyBackup() },
        mergeStagedDatabase = if (mode == RestoreMode.MERGE) { stagedMain ->
            mergeStagedDatabaseIntoCurrent(stagedMain)
        } else null,
        updateSettings = { transform -> settingsStore.update(transform) },
    )

    /**
     * A 档安全备份（design-backup-manifest-and-safety §3.4）：把当前 settings+数据库
     * 打进 `filesDir/safety_backup/`，滚动保留 3 份（按文件名时间戳，新者留）。
     * 必须直接调 [prepareBackupFileLocked]——调用点已持有 RESTORE 锁，[DataOperationLock]
     * 不可重入。失败只记 warning：恢复是用户已经选定的动作，保险丝失效不应当成为第二道墙。
     */
    private suspend fun createSafetyBackup() {
        runCatching { writeSafetyBackup() }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "createSafetyBackup failed; continuing restore", it)
        }
    }

    /** The caller holds the restore lock. LAN deletes leave attachment files in place for this archive. */
    internal suspend fun writeSafetyBackup() {
        val dir = File(context.filesDir, FileFolders.SAFETY_BACKUP).apply { mkdirs() }
        val staged = prepareBackupFileLocked(listOf(BackupItem.DATABASE), BackupScope())
        try {
            val target = File(dir, "safety_${staged.name}")
            val part = File.createTempFile("safety-", ".part", dir)
            try {
                staged.copyTo(part, overwrite = true)
                check(part.renameTo(target)) { "Could not publish safety backup" }
            } finally {
                part.delete()
            }
            dir.listFiles { f -> f.name.startsWith("safety_") && f.extension == "zip" }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
            Log.i(TAG, "createSafetyBackup: ${target.name} (${target.length().fileSizeToString()})")
        } finally {
            staged.delete()
        }
    }

    /**
     * 恢复前巡检（design-backup-manifest-and-safety §3.5）：`ZipFile` 随机访问读 manifest
     * 与中央目录（**不全量解压**）。v2 备份返回 manifest；legacy 备份 manifest=null、
     * sections 由条目名推断（中央目录尺寸）。UI 用于恢复前预览卡。
     */
    suspend fun inspectBackup(backupFile: File): BackupInspection = withContext(Dispatchers.IO) {
        java.util.zip.ZipFile(backupFile).use { zipFile ->
            check(zipFile.getEntry(RESCUE_REPORT_ENTRY) == null) {
                context.getString(R.string.safe_mode_rescue_manual_only)
            }
            val manifestEntry = zipFile.getEntry(BackupManifest.ZIP_ENTRY)
            val manifest: BackupManifest? = manifestEntry?.let {
                json.decodeFromString<BackupManifest>(zipFile.getInputStream(it).readBytes().decodeToString())
            }
            val sections = mutableMapOf<BackupManifestSection, Int>()
            val bytes = mutableMapOf<BackupManifestSection, Long>()
            var count = 0
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                classifyBackupEntry(entry.name)?.let { section ->
                    sections.merge(section, 1, Int::plus)
                    bytes.merge(section, entry.size.coerceAtLeast(0), Long::plus)
                }
            }
            BackupInspection(
                manifest = manifest,
                sections = sections.mapValues { (section, entries) ->
                    BackupSectionStats(entries = entries, uncompressedBytes = bytes.getOrDefault(section, 0L))
                },
                entryCount = count,
            )
        }
    }

    /**
     * merge 模式：把 staged 快照中「本地没有的会话」合并进现库的一致性副本。
     *
     * 步骤：① 现库 `VACUUM INTO` 出基底副本（当前 schema、含已提交 WAL 内容）；
     * ② staged 快照先 checkpoint（旧格式备份的热拷 wal 三件套，合并读必须看到全部已提交数据）；
     * ③ 从快照捕获会话逻辑对象（复用增量备份的 [DatabaseObjectCapture] 底座）；
     * ④ [DatabaseObjectMerge.mergeMissingConversations] 按 id 幂等插入（快照有、本地无 → 插入；
     *    同 id → 跳过保留本地），FK 校验失败整体失败（现库未动）。
     *
     * 返回的 mergedDb 由调用方移交 [PendingRestoreStore.stage]（move 语义）；本函数内任何异常
     * 都会由调用方的 finally 清理 staged 文件，现库不受影响。
     */
    private fun mergeStagedDatabaseIntoCurrent(stagedSnapshot: File): MergeDatabaseOutcome {
        val workDir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "restore-merge-").toFile()
        try {
            val base = File(workDir, "rikka_hub.db")
            appDatabase.openHelper.writableDatabase.execSQL(
                "VACUUM main INTO ?",
                arrayOf(base.absolutePath),
            )
            check(base.exists() && base.length() > 0) { "Cannot snapshot current database for merge restore" }

            val objectsDir = File(workDir, "objects")
            check(objectsDir.isDirectory || objectsDir.mkdirs()) { "Cannot create merge staging directory" }
            val objects = captureConversationObjectsFromSnapshot(stagedSnapshot, objectsDir)
            if (objects.isEmpty()) {
                // 快照无会话（空库备份 + merge）：无新增内容，stage 现库副本 = 无损空操作
                Log.i(TAG, "mergeStagedDatabaseIntoCurrent: snapshot has no conversations; staging current copy")
                return MergeDatabaseOutcome(base, workDir, mergedConversations = 0, skippedConversations = 0)
            }
            val (merged, skipped) = DatabaseObjectMerge.mergeMissingConversations(base, objects)
            // 会话对象载荷已读入合并结果，暂存目录（objects/）不再需要，只留基底待 stage
            objectsDir.deleteRecursively()
            return MergeDatabaseOutcome(base, workDir, mergedConversations = merged, skippedConversations = skipped)
        } finally {
            // staged 快照的 sidecar（-wal/-shm，若还有）由捕获端删除；这里只兜底清孤儿
            listOf("-wal", "-shm").forEach { suffix -> File(stagedSnapshot.path + suffix).delete() }
        }
    }

    /**
     * 从 staged 快照捕获会话逻辑对象（merge 换库路径与「仅恢复对话对象」共用）。
     *
     * 快照先 checkpoint（旧格式备份是热拷 db+wal 三件套，读前必须把 wal 合入主文件），
     * 再用增量备份的 [DatabaseObjectCapture] 底座捕获（VACUUM 快照无 wal，自然跳过）。
     * @return 对象列表（key → 载荷文件），可能为空（空库备份）
     */
    private fun captureConversationObjectsFromSnapshot(
        stagedSnapshot: File,
        objectsDir: File,
    ): List<Pair<String, File>> {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            stagedSnapshot.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Merge snapshot WAL recovery failed" }
            }
            db.rawQuery("PRAGMA journal_mode = DELETE", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true))
            }
        }
        return DatabaseObjectCapture.captureConversationsOnly(stagedSnapshot, objectsDir)
    }

    /**
     * 「仅恢复对话对象」（不换库）：只从备份库读会话对象幂等并入**当前库**，
     * settings/文件/其余表全部不动，也不走 staged 换库与重启应用。
     *
     * 流程：单遍流式读 zip（只暂存数据库家族条目）→ manifest 校验（免 settings 双校验，
     * settings 条目不读）→ 安全备份 → 家族分流/迁移（兼容原版库）→ checkpoint + 捕获会话对象 →
     * [DatabaseObjectMerge.mergeMissingConversationsLive] 经第二连接直写现库（WAL 多连接安全，
     * 单事务原子，FK 失败回滚 = 现库无痕）。
     *
     * 并发口径：[DataOperationLock.RESTORE] 串行化恢复族操作；与聊天期间的会话写入由
     * SQLite 写锁 + busy_timeout 排队，事务原子性兜底。
     */
    suspend fun restoreConversationsFromBackupFile(
        backupFile: File,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome = DataOperationLock.runWaiting(DataOperation.RESTORE) {
        restoreConversationsLocked(backupFile, onProgress)
    }

    private suspend fun restoreConversationsLocked(
        backupFile: File,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreConversationsFromBackupFile: starting (conversations-only)")
            val stagedDatabaseFiles = linkedMapOf<String, File>()
            var stagedMainSha256: String? = null
            var stagedMainBytes = 0L
            var manifest: BackupManifest? = null
            val seenEntries = mutableSetOf<String>()
            val sectionEntries = mutableMapOf<BackupManifestSection, Int>()

            // 空间预检：本路径只暂存数据库条目，但保险按整包未压缩体积把关（与整库恢复同口径）
            val uncompressedTotal = BackupSpaceCheck.uncompressedSizeOfZip(backupFile)
            if (uncompressedTotal != null && uncompressedTotal > BackupManifest.MAX_TOTAL_BYTES) {
                throw IllegalStateException(
                    "Backup decompressed size ${uncompressedTotal.fileSizeToString()} exceeds the " +
                        "${BackupManifest.MAX_TOTAL_BYTES.fileSizeToString()} restore limit"
                )
            }

            val throttle = ProgressThrottle()
            val archiveTotal = backupFile.length()
            val countingIn = CountingInputStream(FileInputStream(backupFile)) { consumed ->
                val progress = BackupProgress(BackupPhase.EXTRACTING, consumed, archiveTotal)
                if (throttle.shouldEmit(progress)) onProgress(progress)
            }

            try {
                ZipInputStream(countingIn).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        val zipEntry = entry ?: continue
                        if (!seenEntries.add(zipEntry.name)) {
                            throw Exception("Duplicate backup entry: ${zipEntry.name}")
                        }
                        classifyBackupEntry(zipEntry.name)?.let { section ->
                            sectionEntries.merge(section, 1, Int::plus)
                        }
                        when (zipEntry.name) {
                            BackupManifest.ZIP_ENTRY -> {
                                manifest = json.decodeFromString<BackupManifest>(zipIn.readBytes().decodeToString())
                            }

                            "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                val staged = File(context.cacheDir, "restore_conv_${zipEntry.name}")
                                boundedEntryCopy(zipIn, zipEntry) { FileOutputStream(staged) }
                                stagedDatabaseFiles[zipEntry.name] = staged
                                if (zipEntry.name == "rikka_hub.db") {
                                    stagedMainSha256 = sha256Of(staged)
                                    stagedMainBytes = staged.length()
                                }
                            }

                            else -> {
                                // settings/FILES/WORKSPACE_FILES：仅记账（classify 已计数），
                                // closeEntry 消费内容，不落盘
                            }
                        }
                        zipIn.closeEntry()
                    }
                }

                // manifest 校验（restoreDatabase=true 核对主库哈希；settings 条目未读，免双校验）
                val m = manifest ?: throw Exception("Backup manifest missing (legacy packages are not supported here)")
                verifyBackupManifest(
                    manifest = m,
                    settingsSha256 = null,
                    settingsBytes = 0L,
                    stagedMainSha256 = stagedMainSha256,
                    stagedMainBytes = stagedMainBytes,
                    restoreDatabase = true,
                    sectionEntries = sectionEntries,
                    verifySettings = false,
                )

                if (stagedDatabaseFiles.isEmpty()) {
                    Log.w(TAG, "restoreConversationsFromBackupFile: backup has no database entries")
                    return@withContext RestoreOutcome()
                }

                // A 档安全备份：合并是现库写操作，先留后悔药（失败降级不阻断，同整库恢复）
                createSafetyBackup()

                // 家族分流/迁移（原版库的 schema 转换与整库恢复同一口径），失败则现库未动
                prepareStagedDatabase(context, stagedDatabaseFiles)
                val stagedMain = stagedDatabaseFiles["rikka_hub.db"]
                    ?: return@withContext RestoreOutcome()

                val workDir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "restore-conv-").toFile()
                try {
                    val objectsDir = File(workDir, "objects")
                    check(objectsDir.isDirectory || objectsDir.mkdirs()) { "Cannot create staging directory" }
                    val objects = captureConversationObjectsFromSnapshot(stagedMain, objectsDir)
                    if (objects.isEmpty()) {
                        Log.i(TAG, "restoreConversationsFromBackupFile: snapshot has no conversations")
                        return@withContext RestoreOutcome()
                    }
                    val liveDb = context.getDatabasePath(AppDatabase.NAME)
                    val (merged, skipped) = DatabaseObjectMerge.mergeMissingConversationsLive(liveDb, objects)
                    Log.i(
                        TAG,
                        "restoreConversationsFromBackupFile: merged into live db " +
                            "(+$merged new, $skipped skipped)",
                    )
                    RestoreOutcome(mergedConversations = merged, skippedConversations = skipped)
                } finally {
                    workDir.deleteRecursively()
                }
            } finally {
                stagedDatabaseFiles.values.forEach { it.takeIf(File::exists)?.delete() }
            }
        }
}
