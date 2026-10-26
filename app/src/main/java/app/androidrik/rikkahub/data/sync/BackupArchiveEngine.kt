package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.datastore.migration.SettingsCompatDecoder
import app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.SkillPaths
import app.androidrik.rikkahub.utils.fileSizeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
internal class BackupArchiveEngine(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val appDatabase: AppDatabase,
) {
    private val TAG = "BackupArchive"

    /**
     * 生成备份 zip（写入 cacheDir）。
     *
     * 内容：settings.json（恒含）+ 按 [items] 选择性打包 DATABASE / FILES / WORKSPACE_FILES。
     *
     * @param onProgress 阶段进度回调（打包阶段按**读入字节**计）；IO 线程调用
     * @throws IllegalStateException cacheDir 空间不足（见 §9.1 空间预检）
     */
    suspend fun prepareBackupFile(
        items: Collection<BackupItem>,
        onProgress: (BackupProgress) -> Unit = {},
    ): File = DataOperationLock.runWaiting(DataOperation.BACKUP) {
        prepareBackupFileLocked(items, onProgress)
    }

    private suspend fun prepareBackupFileLocked(
        items: Collection<BackupItem>,
        onProgress: (BackupProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")
        if (backupFile.exists()) backupFile.delete()

        // 空间预检：保守按「输入不压缩」估算；不足则提前失败（2.8GB 跑到一半失败代价太大）
        val plannedInputs = collectBackupInputs(items)
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
            processedBytes += delta
            val progress = BackupProgress(BackupPhase.PACKING, processedBytes, estimatedBytes)
            if (throttle.shouldEmit(progress)) onProgress(progress)
        }

        ManifestZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            // settings.json 恒含（首个 section；manifest 记其 SHA-256 供恢复侧校验）
            val settingsContent = json.encodeToString(settingsStore.settingsFlow.value)
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
                databaseSha256 = sha
                databaseBytes = bytes
            }
            if (BackupItem.FILES in items) {
                addAppFilesToZip(zipOut, ::report)
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

            // manifest v2（design-backup-manifest-and-safety §3.1）：zip 末条目。
            // 恢复侧读毕统一校验（双 SHA-256 + 各 section 条目数），通过才执行破坏性步骤。
            val manifest = BackupManifest(
                createdAt = timestamp,
                items = items.distinct(),
                sections = zipOut.sectionStats(),
                settingsSha256 = sha256Hex(settingsRaw),
                settingsBytes = settingsRaw.size.toLong(),
                databaseSha256 = databaseSha256,
                databaseBytes = databaseBytes,
            )
            addVirtualFileToZip(
                zipOut = zipOut,
                name = BackupManifest.ZIP_ENTRY,
                content = json.encodeToString(manifest),
                onBytes = ::report,
            )
        }

        // 收尾：确保 UI 到达 100%（节流可能吞掉末次）
        onProgress(BackupProgress(BackupPhase.PACKING, estimatedBytes, estimatedBytes))

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
    private fun collectBackupInputs(items: Collection<BackupItem>): List<File> {
        val inputs = mutableListOf<File>()
        if (BackupItem.DATABASE in items) {
            val dbFile = context.getDatabasePath("rikka_hub")
            inputs += dbFile
            DatabaseRestoreFiles.sidecarSuffixes().forEach { suffix ->
                inputs += File(dbFile.parentFile, "rikka_hub$suffix")
            }
        }
        if (BackupItem.FILES in items) {
            // 目录清单统一由 BACKED_UP_FILE_FOLDERS 驱动（见其注释），三处共用不再手列
            BACKED_UP_FILE_FOLDERS
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

    /**
     * 从备份 zip 恢复。
     *
     * @param onProgress 解压阶段进度回调（按已处理的未压缩字节计，总量取自 zip 中央目录）
     * @return 恢复结果（工作区文件数 + 设置降级提示）
     * @throws IllegalStateException 空间不足（解压产物 + zip 需同时存在于 cacheDir，见 §9.1）
     */
    suspend fun restoreFromBackupFile(
        backupFile: File,
        items: Collection<BackupItem>,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome = DataOperationLock.runWaiting(DataOperation.RESTORE) {
        restoreFromBackupFileLocked(backupFile, items, onProgress)
    }

    private suspend fun restoreFromBackupFileLocked(
        backupFile: File,
        items: Collection<BackupItem>,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting from ${backupFile.absolutePath}")
            var workspaceFilesRestored = 0
            // 设置里因「当前版本不支持」被降级丢弃的条目（原版 3.1.0 的 chatgpt/grok 提供商等）
            val settingsWarnings = mutableListOf<String>()
            // 数据库文件先落到 cacheDir 暂存，等整个 zip 读完、确认拿到主库后再关库交换。
            // 这样「读到一半失败」不会先把现库关掉却没有可用的替代文件。
            val stagedDatabaseFiles = linkedMapOf<String, File>()

            // A 档（design-backup-manifest-and-safety §3.4）：任何变更之前先给现库留后悔药。
            // runCatching——安全备份失败只降级记日志，不阻断恢复（保险丝不能变成第二道墙）
            if (BackupItem.DATABASE in items) {
                createSafetyBackup()
            }

            // 空间预检：解压产物与 zip 同时存在（cacheDir 与 filesDir 同为内部存储分区，查一次即可）
            val uncompressedTotal = BackupSpaceCheck.uncompressedSizeOfZip(backupFile)
            if (uncompressedTotal != null && uncompressedTotal > 0) {
                if (uncompressedTotal > BackupManifest.MAX_TOTAL_BYTES) {
                    throw IllegalStateException(
                        "Backup decompressed size ${uncompressedTotal.fileSizeToString()} exceeds the " +
                            "${BackupManifest.MAX_TOTAL_BYTES.fileSizeToString()} restore limit"
                    )
                }
                val available = context.cacheDir.usableSpace
                if (!BackupSpaceCheck.hasEnoughSpace(available, uncompressedTotal)) {
                    throw IllegalStateException(
                        "Not enough cache space for restore: need ~${uncompressedTotal.fileSizeToString()}, " +
                            "available ${available.fileSizeToString()}"
                    )
                }
                Log.i(
                    TAG,
                    "restoreFromBackupFile: space check ok (need ~${uncompressedTotal.fileSizeToString()}, " +
                        "available ${available.fileSizeToString()})",
                )
            }

            val throttle = ProgressThrottle()
            // 进度按「已消费的归档字节」计（= backupFile 长度），比逐条目 size 稳——
            // ZipInputStream 流式读时条目 size 常为 -1（本地头用 data descriptor）。
            val archiveTotal = backupFile.length()
            val countingIn = CountingInputStream(FileInputStream(backupFile)) { consumed ->
                val progress = BackupProgress(BackupPhase.EXTRACTING, consumed, archiveTotal)
                if (throttle.shouldEmit(progress)) onProgress(progress)
            }

            // §3.2：settings 缓冲不即时写（原实现流中即时 update，损坏包会写一半）；
            // 数据库暂存即哈希；条目数按名字归类统计（与打包侧同一份 classify 防漂移）。
            // zip 读毕对照 manifest 统一校验，通过后才执行两个破坏性步骤。
            val seenEntries = mutableSetOf<String>()
            var settingsJson: String? = null
            var settingsSha256: String? = null
            var settingsBytes = 0L
            var stagedMainSha256: String? = null
            var stagedMainBytes = 0L
            val sectionEntries = mutableMapOf<BackupManifestSection, Int>()
            var manifest: BackupManifest? = null

            try {
                ZipInputStream(countingIn).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        val zipEntry = entry ?: continue
                        if (!seenEntries.add(zipEntry.name)) {
                            throw Exception("Duplicate backup entry: ${zipEntry.name}")
                        }
                        Log.i(TAG, "restoreFromBackupFile: entry ${zipEntry.name}")
                        classifyBackupEntry(zipEntry.name)?.let { section ->
                            sectionEntries.merge(section, 1, Int::plus)
                        }
                        when (zipEntry.name) {
                            BackupManifest.ZIP_ENTRY -> {
                                manifest = json.decodeFromString<BackupManifest>(zipIn.readBytes().decodeToString())
                            }

                            "settings.json" -> {
                                val raw = zipIn.readBytes()
                                settingsJson = raw.toString(Charsets.UTF_8)
                                settingsSha256 = sha256Hex(raw)
                                settingsBytes = raw.size.toLong()
                                Log.i(TAG, "restoreFromBackupFile: buffered settings ($settingsBytes bytes)")
                            }

                            "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                if (BackupItem.DATABASE in items) {
                                    // 仅暂存，不直接写现库（现库正被 Room 连接持有）
                                    val staged = File(context.cacheDir, "restore_${zipEntry.name}")
                                    boundedEntryCopy(zipIn, zipEntry) { FileOutputStream(staged).use { it } }
                                    stagedDatabaseFiles[zipEntry.name] = staged
                                    if (zipEntry.name == "rikka_hub.db") {
                                        stagedMainSha256 = sha256Of(staged)
                                        stagedMainBytes = staged.length()
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: staged ${zipEntry.name} " +
                                            "(${staged.length()} bytes)",
                                    )
                                }
                            }

                            else -> {
                                val bounded = boundedEntryStream(zipIn)
                                when {
                                    BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.UPLOAD}/") -> {
                                        restoreFlatEntry(bounded, zipEntry.name, FileFolders.UPLOAD)
                                    }
                                    BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.IMAGES}/") -> {
                                        // images（imggen 产物）此前不进备份——旧备份无此条目自然跳过
                                        restoreFlatEntry(bounded, zipEntry.name, FileFolders.IMAGES)
                                    }
                                    BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.AVATARS}/") -> {
                                        // avatars（助手/用户头像）2026-10-13 从 upload/ 独立出来（#2127）；
                                        // **旧备份里头像在 upload/ 下**，由上面那条分支照常恢复到 upload/，
                                        // 再于下次启动被 AvatarMigration 搬进 avatars/ ⇒ 新旧备份都能恢复出头像
                                        restoreFlatEntry(bounded, zipEntry.name, FileFolders.AVATARS)
                                    }
                                    BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.SKILLS}/") -> {
                                        restoreSkillEntry(bounded, zipEntry.name)
                                    }
                                    BackupItem.FILES in items &&
                                        zipEntry.name.startsWith("${FileFolders.MEDIA_CREATION}/") -> {
                                        // 媒体创作产物：media_creation/<sessionId>/… 递归；复用带 zip-slip 防护的平铺写入
                                        restoreFlatEntry(bounded, zipEntry.name, FileFolders.MEDIA_CREATION)
                                    }
                                    BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.FONTS}/") -> {
                                        restoreFontEntry(bounded, zipEntry.name)
                                    }
                                    BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.TOOL_OUTPUTS}/") -> {
                                        // 仅兼容旧备份（新备份已不含它）；恢复后也会在下次启动被 cleanup 清掉
                                        restoreFlatEntry(bounded, zipEntry.name, FileFolders.TOOL_OUTPUTS)
                                    }
                                    BackupItem.WORKSPACE_FILES in items &&
                                        zipEntry.name.startsWith(WorkspaceBackupHelper.ZIP_PREFIX) -> {
                                        val restored = WorkspaceBackupHelper.restoreEntry(
                                            context = context,
                                            workspaceBaseDir = WorkspaceBackupHelper.workspacesDir(context),
                                            zipEntryName = zipEntry.name,
                                        ) { output -> bounded.copyTo(output) }
                                        if (restored) {
                                            workspaceFilesRestored++
                                            Log.i(TAG, "restoreFromBackupFile: restored workspace file ${zipEntry.name}")
                                        } else {
                                            Log.w(TAG, "restoreFromBackupFile: skipped workspace entry ${zipEntry.name}")
                                        }
                                    }
                                }
                            }
                        }
                        zipIn.closeEntry()
                    }
                }

                // §3.2 统一校验：manifest 存在（v2 备份）时，破坏性步骤之前核对全部记账
                manifest?.let { m ->
                    verifyBackupManifest(
                        manifest = m,
                        settingsSha256 = settingsSha256,
                        settingsBytes = settingsBytes,
                        stagedMainSha256 = stagedMainSha256,
                        stagedMainBytes = stagedMainBytes,
                        restoreDatabase = BackupItem.DATABASE in items,
                        sectionEntries = sectionEntries,
                    )
                    Log.i(TAG, "restoreFromBackupFile: manifest verification passed (${m.createdAt})")
                }

                // ---- 校验通过，破坏性步骤开始 ----
                settingsJson?.let { settingsString ->
                    Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                    try {
                        // 旧包名（me.rerere.*）备份的迁移必不可少
                        val migratedJson = SettingsJsonMigrator.migrate(settingsString)
                        // 原版 3.1.0 可能带本应用没有的多态子类（chatgpt/grok/volcengine…）：
                        // 逐条降级丢弃而不是整份失败，否则连会话都恢复不了
                        val decoded = SettingsCompatDecoder.decode(json, migratedJson)
                        settingsStore.update(decoded.settings)
                        settingsWarnings += decoded.droppedEntries
                        if (decoded.droppedEntries.isNotEmpty()) {
                            Log.w(
                                TAG,
                                "restoreFromBackupFile: settings restored with " +
                                    "${decoded.droppedEntries.size} unsupported entr(ies) dropped: " +
                                    decoded.droppedEntries.joinToString(", "),
                            )
                        } else {
                            Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                        throw Exception("Failed to restore settings: ${e.message}")
                    }
                }

                // 关库 → 清陈旧 -wal/-shm → 放入暂存的备份数据库
                if (BackupItem.DATABASE in items) {
                    prepareStagedDatabase(stagedDatabaseFiles)
                    applyStagedDatabaseFiles(stagedDatabaseFiles)
                }

                Log.i(TAG, "restoreFromBackupFile: completed")
                // 收尾：确保 UI 到达 100%（节流可能吞掉末次）
                onProgress(BackupProgress(BackupPhase.EXTRACTING, archiveTotal, archiveTotal))
                if (workspaceFilesRestored > 0) {
                    // rootfs 不在备份内（见设计稿）：工作区文件可用，但 shell/终端需重装 rootfs
                    Log.i(
                        TAG,
                        "restoreFromBackupFile: $workspaceFilesRestored workspace file(s) restored - " +
                            "rootfs NOT included; reinstall to use shell/terminal",
                    )
                }
                RestoreOutcome(
                    workspaceFilesRestored = workspaceFilesRestored,
                    settingsWarnings = settingsWarnings.toList(),
                )
            } finally {
                stagedDatabaseFiles.values.forEach { it.takeIf(File::exists)?.delete() }
            }
        }

    /**
     * A 档安全备份（design-backup-manifest-and-safety §3.4）：把当前 settings+数据库
     * 打进 `filesDir/safety_backup/`，滚动保留 3 份（按文件名时间戳，新者留）。
     * 必须直接调 [prepareBackupFileLocked]——调用点已持有 RESTORE 锁，[DataOperationLock]
     * 不可重入。失败只记 warning：恢复是用户已经选定的动作，保险丝失效不应当成为第二道墙。
     */
    private suspend fun createSafetyBackup() {
        runCatching {
            val dir = File(context.filesDir, FileFolders.SAFETY_BACKUP).apply { mkdirs() }
            val staged = prepareBackupFileLocked(items = listOf(BackupItem.DATABASE), onProgress = {})
            val target = File(dir, "safety_${staged.name}")
            staged.copyTo(target, overwrite = true)
            staged.delete()
            dir.listFiles { f -> f.name.startsWith("safety_") }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
            Log.i(TAG, "createSafetyBackup: ${target.name} (${target.length().fileSizeToString()})")
        }.onFailure {
            Log.w(TAG, "createSafetyBackup failed; continuing restore", it)
        }
    }

    /**
     * 恢复前巡检（design-backup-manifest-and-safety §3.5）：`ZipFile` 随机访问读 manifest
     * 与中央目录（**不全量解压**）。v2 备份返回 manifest；legacy 备份 manifest=null、
     * sections 由条目名推断（中央目录尺寸）。UI 用于恢复前预览卡。
     */
    suspend fun inspectBackup(backupFile: File): BackupInspection = withContext(Dispatchers.IO) {
        java.util.zip.ZipFile(backupFile).use { zipFile ->
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
     * 恢复侧单条目解压上限（zip 炸弹兜底，§3.3）：`ZipEntry.size` 在 data descriptor
     * 模式下常为 -1，不能信 size 字段——用包装流边拷边数，超限抛错。
     */
    private fun boundedEntryStream(zipIn: ZipInputStream): java.io.InputStream =
        object : java.io.FilterInputStream(zipIn) {
            private var total = 0L
            override fun read(): Int {
                val result = super.read()
                if (result >= 0) count(result.toLong())
                return result
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val result = super.read(b, off, len)
                if (result > 0) count(result.toLong())
                return result
            }

            private fun count(delta: Long) {
                total += delta
                if (total > BackupManifest.MAX_ENTRY_BYTES) {
                    throw Exception("Backup entry exceeds the ${BackupManifest.MAX_ENTRY_BYTES.fileSizeToString()} restore limit")
                }
            }
        }

    private inline fun boundedEntryCopy(
        zipIn: ZipInputStream,
        entry: ZipEntry,
        openOutput: () -> java.io.OutputStream,
    ) {
        if (entry.size >= 0 && entry.size > BackupManifest.MAX_ENTRY_BYTES) {
            throw Exception("Backup entry exceeds the ${BackupManifest.MAX_ENTRY_BYTES.fileSizeToString()} restore limit")
        }
        boundedEntryStream(zipIn).use { bounded ->
            openOutput().use { output -> bounded.copyTo(output) }
        }
    }

    /**
     * 换库前的**数据库家族分流**。
     *
     * 本应用历史库（含 2.5.6 的 v25）：直接换库，Room 沿自己的迁移链升到当前版本。
     * 原版 3.x 的库（文件式记忆 + 媒体/翻译表）与本应用 v30 后 schema 分叉，
     * Room 迁移链既缺原版表、又多了本应用独有表 ⇒ 先做数据级转换，再走同一套换库。
     * 未知家族直接报错，不猜、不覆盖。
     */
    private fun prepareStagedDatabase(staged: MutableMap<String, File>) {
        val main = staged["rikka_hub.db"] ?: return
        when (LegacyBackupConverter.classify(main)) {
            LegacyDatabaseFamily.CURRENT -> {
                // 旧包名附件路径就地改写；改过就必须丢弃备份里的陈旧 WAL，否则会盖掉改写
                if (LegacyBackupConverter(context).rewriteLegacyPathsInPlace(main)) {
                    staged.keys.filter { it != "rikka_hub.db" }.toList().forEach { name ->
                        staged.remove(name)?.delete()
                    }
                    Log.i(TAG, "prepareStagedDatabase: rewrote legacy attachment paths in staged database")
                }
            }
            LegacyDatabaseFamily.LEGACY_UPSTREAM -> {
                Log.i(TAG, "prepareStagedDatabase: legacy upstream database, converting to current schema")
                val converted = LegacyBackupConverter(context).convert(main)
                staged.keys.toList().forEach { name -> staged.remove(name)?.delete() }
                staged["rikka_hub.db"] = converted
            }
            LegacyDatabaseFamily.UNKNOWN ->
                throw Exception("Unsupported backup database: neither this app's history nor a known RikkaHub backup")
        }
    }

    /**
     * 用暂存的备份数据库文件替换现库。
     *
     * **为什么必须关库**：恢复期间 Room 连接一直开着，持有旧库的页缓存与 `-wal`/`-shm`
     * 文件映射。若原地覆盖主库、而备份又未带 `-wal`（上游导出包即只含主库），重启时
     * 那条陈旧 WAL 会被回放，把刚恢复的数据整体盖掉——表现为「提示恢复成功、聊天记录没变」。
     * 故先 `close()`（WAL 模式下会 checkpoint 并释放文件），清掉残留伴随文件，再放入备份。
     *
     * **失败顺序**：先把备份复制到目标目录内的临时文件（同文件系统，重命名快且原子），
     * 再关库；这样「复制失败」发生在关库之前，不会留下一个已关闭、无法使用却又没换成功的库。
     *
     * 调用方随后**必须重启应用**（现流程已有「重启」弹窗）——连接已关闭，不能继续使用。
     */
    private fun applyStagedDatabaseFiles(staged: Map<String, File>) {
        val stagedMain = staged["rikka_hub.db"] ?: run {
            Log.w(TAG, "applyStagedDatabaseFiles: no staged main db, skip database replace")
            return
        }
        val dbPath = context.getDatabasePath("rikka_hub")
        val dbDir = dbPath.parentFile ?: return
        dbDir.mkdirs()

        // 1) 先把全部暂存文件复制到目标目录（重命名前的重活全在关库之前完成）
        val prepared = staged.mapValues { (name, src) ->
            val tmp = File(dbDir, "$name.restore-tmp")
            try {
                src.copyTo(tmp, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "applyStagedDatabaseFiles: staging $name failed", e)
                throw Exception("Failed to prepare database file $name: ${e.message}")
            }
            tmp
        }
        val preparedMain = prepared.getValue("rikka_hub.db")

        // 0) 换库前校验暂存主库完整性（Yuihub `34f1a976` 隐患 2 同源）：打开即自动回放 WAL，
        //    不过则中止——此时现库尚未关闭，中止即安全回退（旧格式/外来改包备份的兜底）
        runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                stagedMain.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            val result = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
            db.close()
            if (!result.equals("ok", ignoreCase = true)) {
                throw IllegalStateException("integrity_check: $result")
            }
            Log.i(TAG, "applyStagedDatabaseFiles: staged db integrity check ok")
        }.onFailure {
            throw Exception("Backup database failed integrity check: ${it.message}")
        }

        try {
            // 2) 释放连接；失败则中止（继续覆盖可能得到「新旧混杂」的损坏库）
            try {
                appDatabase.close()
                Log.i(TAG, "applyStagedDatabaseFiles: database closed")
            } catch (e: Exception) {
                Log.e(TAG, "applyStagedDatabaseFiles: closing database failed", e)
                throw Exception("Failed to close database before restore: ${e.message}")
            }

            // 3) 清理陈旧伴随文件（备份未提供时它们会盖掉恢复结果）
            val deleted = DatabaseRestoreFiles.deleteSidecarFiles(dbDir, "rikka_hub")
            if (deleted.isNotEmpty()) {
                Log.i(TAG, "applyStagedDatabaseFiles: removed stale ${deleted.joinToString()}")
            }

            // 4) 换到位：同目录内重命名（回退复制）——主库必需，wal/shm 备份里带则一并还原
            DatabaseRestoreFiles.moveIntoPlace(preparedMain, dbPath)
            DatabaseRestoreFiles.sidecarSuffixes().forEach { suffix ->
                prepared["rikka_hub$suffix"]?.let {
                    DatabaseRestoreFiles.moveIntoPlace(it, File(dbDir, "rikka_hub$suffix"))
                }
            }
            Log.i(TAG, "applyStagedDatabaseFiles: database replaced (${dbPath.length()} bytes)")
        } finally {
            // 未换走的临时文件（异常路径）不留在数据库目录里
            prepared.values.forEach { it.takeIf(File::exists)?.delete() }
        }
    }

    // ---- 打包辅助 ----

    /**
     * 打包期的 section 记账流（design-backup-manifest-and-safety §3.1）：按条目名归类
     * （[classifyBackupEntry]，与恢复侧同一份分类防漂移），逐条目计数、按未压缩字节累加，
     * 供 manifest 写入。manifest.json 自身不进统计。
     */
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
        val dbFile = context.getDatabasePath("rikka_hub")
        if (!dbFile.exists()) return "" to 0L

        // 一致性快照（Yuihub `47e7dfaf` 对照）：VACUUM INTO 在**单个读事务**里导出，
        // 天然包含已提交的 WAL 内容——比热拷 db/wal/shm 三件套稳（拷贝期间写入不可见，
        // 且可能拷到半页）。09-15 的 WAL 修复只治了恢复方向，备份方向的一致性由此补齐。
        // VACUUM INTO 需要 SQLite 3.27+（API 30+）；minSdk 26 的老设备或异常时回退热拷，
        // 恢复路径两种形态都已处理（db-only 时恢复前会清陈旧 -wal/-shm）。
        val snapshot = File(context.cacheDir, "backup_db_snapshot.db")
        snapshot.delete() // VACUUM INTO 要求目标文件不存在
        val vacuumed = runCatching {
            appDatabase.openHelper.writableDatabase.execSQL(
                "VACUUM main INTO ?",
                arrayOf(snapshot.absolutePath),
            )
            snapshot.exists() && snapshot.length() > 0
        }.onFailure {
            Log.w(TAG, "addDatabaseToZip: VACUUM INTO failed, falling back to file copy", it)
        }.getOrDefault(false)

        try {
            if (vacuumed) {
                Log.i(TAG, "addDatabaseToZip: consistent snapshot via VACUUM INTO (${snapshot.length()} bytes)")
                val mainSha = sha256Of(snapshot)
                addFileToZip(zipOut, snapshot, "rikka_hub.db", onBytes, ::backupLog)
                // VACUUM 快照已含已提交的 WAL 内容：不打包热 wal/shm（原行为）
                return mainSha to snapshot.length()
            } else {
                val mainSha = sha256Of(dbFile)
                addFileToZip(zipOut, dbFile, "rikka_hub.db", onBytes, ::backupLog)
                listOf("rikka_hub-wal", "rikka_hub-shm").forEach { name ->
                    val f = File(dbFile.parentFile, name)
                    if (f.exists()) addFileToZip(zipOut, f, name, onBytes, ::backupLog)
                }
                return mainSha to dbFile.length()
            }
        } finally {
            snapshot.delete()
        }
    }

    /** 流式 SHA-256（数据库可能数百 MB，不能 readBytes 进内存）。 */
    private fun sha256Of(file: File): String =
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

    /**
     * files 下由 [BACKED_UP_FILE_FOLDERS] 声明的用户数据目录（平铺：upload/fonts；递归：skills/images）。
     *
     * **不含 tool_outputs**（2026-09-15 决策，选项①）：它是会话级临时产物——[cleanupToolOutputs]
     * 在每次启动时清空该目录，恢复流程末尾必然重启，刚恢复的文件立刻又被删；打包它只是徒增
     * zip 体积。历史消息里 `/tool_outputs/<id>.txt` 的引用本就可能悬空，截断消息已内联前 4KB
     * 预览（`TOOL_OUTPUT_PREVIEW_CHARS`）兜底。
     */
    private fun addAppFilesToZip(zipOut: ZipOutputStream, onBytes: (Long) -> Unit) {
        // 打包实现移到顶层 packAppFiles（可注入 filesDir ⇒ 能被 JVM 测试覆盖，
        // 见 AppFilesPacking.kt 顶部记录的真机事故）
        packAppFiles(
            filesDir = context.filesDir,
            zipOut = zipOut,
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


    // ---- 恢复辅助（严格对齐原 S3Sync/WebDavSync 行为）----

    /**
     * 恢复 `upload/` 与（旧备份的）`tool_outputs/` 条目到同名目录。
     *
     * 安全：canonical 解析后必须仍位于该目录内（防 zip-slip）。
     * 注：upload 是**平铺**文件（子目录不会被打包），但仍接受带子目录的条目（向后兼容）。
     */
    private fun restoreFlatEntry(zipIn: java.io.InputStream, entryName: String, folder: String) {
        val fileName = entryName.substringAfter("$folder/")
        if (fileName.isEmpty()) return
        val destDir = File(context.filesDir, folder).apply { mkdirs() }
        val targetFile = destDir.resolve(fileName).canonicalFile
        if (!targetFile.path.startsWith(destDir.canonicalFile.path + File.separator)) {
            Log.w(TAG, "restoreFromBackupFile: Skipping path-traversal entry $entryName")
            return
        }
        targetFile.parentFile?.mkdirs()
        try {
            FileOutputStream(targetFile).use { outputStream -> zipIn.copyTo(outputStream) }
            Log.i(TAG, "restoreFromBackupFile: Restored $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore $entryName", e)
            throw Exception("Failed to restore file $entryName: ${e.message}")
        }
    }

    /**
     * 恢复 `fonts/` 条目：严格对齐原版——**仅接受不含子目录的文件名**（字体目录不打包子目录）。
     */
    private fun restoreFontEntry(zipIn: java.io.InputStream, entryName: String) {
        val fileName = entryName.substringAfter("${FileFolders.FONTS}/")
        if (fileName.isEmpty() || fileName.contains('/')) {
            Log.w(TAG, "restoreFromBackupFile: Skipping invalid font entry $entryName")
            return
        }
        val fontsFolder = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
        val targetFile = File(fontsFolder, fileName)
        FileOutputStream(targetFile).use { outputStream -> zipIn.copyTo(outputStream) }
        Log.i(TAG, "restoreFromBackupFile: Restored $entryName (${targetFile.length()} bytes)")
    }

    /**
     * 恢复 `skills/` 条目：严格对齐原版——用 [SkillPaths] 做目录/文件路径合法性校验
     * （拒绝 `..`、绝对路径、跳出技能目录等）。
     */
    private fun restoreSkillEntry(zipIn: java.io.InputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")
        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }
        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")
        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()
        try {
            FileOutputStream(targetFile).use { outputStream -> zipIn.copyTo(outputStream) }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }
}
