package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.datastore.Settings
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
 * 设计见 `docs/design-backup-workspace-files.md`。
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

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value),
                onBytes = ::report,
            )

            if (BackupItem.DATABASE in items) {
                addDatabaseToZip(zipOut, ::report)
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
            // 不含 tool_outputs：会话级临时产物，启动即被 cleanupToolOutputs 清空（见 BackupItem 注释）
            listOf(FileFolders.UPLOAD, FileFolders.FONTS, FileFolders.SKILLS)
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
     * @return 恢复的工作区文件数（0 = 未包含/未恢复）；调用方据此提示「rootfs 需重装」
     * @throws IllegalStateException 空间不足（解压产物 + zip 需同时存在于 cacheDir，见 §9.1）
     */
    suspend fun restoreFromBackupFile(
        backupFile: File,
        items: Collection<BackupItem>,
        onProgress: (BackupProgress) -> Unit = {},
    ): Int =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting from ${backupFile.absolutePath}")
            var workspaceFilesRestored = 0
            // 数据库文件先落到 cacheDir 暂存，等整个 zip 读完、确认拿到主库后再关库交换。
            // 这样「读到一半失败」不会先把现库关掉却没有可用的替代文件。
            val stagedDatabaseFiles = linkedMapOf<String, File>()

            // 空间预检：解压产物与 zip 同时存在（cacheDir 与 filesDir 同为内部存储分区，查一次即可）
            val uncompressedTotal = BackupSpaceCheck.uncompressedSizeOfZip(backupFile)
            if (uncompressedTotal != null && uncompressedTotal > 0) {
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

            try {
                ZipInputStream(countingIn).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        val zipEntry = entry ?: continue
                        Log.i(TAG, "restoreFromBackupFile: entry ${zipEntry.name}")
                        when (zipEntry.name) {
                            "settings.json" -> {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                try {
                                    // 旧包名（me.rerere.*）备份的迁移必不可少
                                    val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                    val settings = json.decodeFromString<Settings>(migratedJson)
                                    settingsStore.update(settings)
                                    Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                    throw Exception("Failed to restore settings: ${e.message}")
                                }
                            }

                            "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                if (BackupItem.DATABASE in items) {
                                    // 仅暂存，不直接写现库（现库正被 Room 连接持有）
                                    val staged = File(context.cacheDir, "restore_${zipEntry.name}")
                                    FileOutputStream(staged).use { zipIn.copyTo(it) }
                                    stagedDatabaseFiles[zipEntry.name] = staged
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: staged ${zipEntry.name} " +
                                            "(${staged.length()} bytes)",
                                    )
                                }
                            }

                            else -> {
                                if (BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.UPLOAD}/")) {
                                    restoreFlatEntry(zipIn, zipEntry.name, FileFolders.UPLOAD)
                                } else if (BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.SKILLS}/")) {
                                    restoreSkillEntry(zipIn, zipEntry.name)
                                } else if (BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.FONTS}/")) {
                                    restoreFontEntry(zipIn, zipEntry.name)
                                } else if (BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.TOOL_OUTPUTS}/")) {
                                    // 仅兼容旧备份（新备份已不含它）；恢复后也会在下次启动被 cleanup 清掉
                                    restoreFlatEntry(zipIn, zipEntry.name, FileFolders.TOOL_OUTPUTS)
                                } else if (BackupItem.WORKSPACE_FILES in items &&
                                    zipEntry.name.startsWith(WorkspaceBackupHelper.ZIP_PREFIX)
                                ) {
                                    val restored = WorkspaceBackupHelper.restoreEntry(
                                        context = context,
                                        workspaceBaseDir = WorkspaceBackupHelper.workspacesDir(context),
                                        zipEntryName = zipEntry.name,
                                    ) { output -> zipIn.copyTo(output) }
                                    if (restored) {
                                        workspaceFilesRestored++
                                        Log.i(TAG, "restoreFromBackupFile: restored workspace file ${zipEntry.name}")
                                    } else {
                                        Log.w(TAG, "restoreFromBackupFile: skipped workspace entry ${zipEntry.name}")
                                    }
                                }
                            }
                        }
                        zipIn.closeEntry()
                    }
                }

                // 关库 → 清陈旧 -wal/-shm → 放入暂存的备份数据库
                if (BackupItem.DATABASE in items) {
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
                workspaceFilesRestored
            } finally {
                stagedDatabaseFiles.values.forEach { it.takeIf(File::exists)?.delete() }
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

    private fun addDatabaseToZip(zipOut: ZipOutputStream, onBytes: (Long) -> Unit) {
        val dbFile = context.getDatabasePath("rikka_hub")
        if (!dbFile.exists()) return
        addFileToZip(zipOut, dbFile, "rikka_hub.db", onBytes)
        listOf("rikka_hub-wal", "rikka_hub-shm").forEach { name ->
            val f = File(dbFile.parentFile, name)
            if (f.exists()) addFileToZip(zipOut, f, name, onBytes)
        }
    }

    /**
     * files/upload、files/skills、files/fonts。
     *
     * **不含 tool_outputs**（2026-09-15 决策，选项①）：它是会话级临时产物——[cleanupToolOutputs]
     * 在每次启动时清空该目录，恢复流程末尾必然重启，刚恢复的文件立刻又被删；打包它只是徒增
     * zip 体积。历史消息里 `/tool_outputs/<id>.txt` 的引用本就可能悬空，截断消息已内联前 4KB
     * 预览（`TOOL_OUTPUT_PREVIEW_CHARS`）兜底。
     */
    private fun addAppFilesToZip(zipOut: ZipOutputStream, onBytes: (Long) -> Unit) {
        // upload / fonts 是平铺文件（非递归）
        listOf(FileFolders.UPLOAD, FileFolders.FONTS).forEach { folder ->
            val dir = File(context.filesDir, folder)
            if (dir.isDirectory) {
                Log.i(TAG, "prepareBackupFile: packing $folder (${dir.absolutePath})")
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) addFileToZip(zipOut, file, "$folder/${file.name}", onBytes)
                }
            } else {
                Log.w(TAG, "prepareBackupFile: $folder does not exist")
            }
        }
        // skills 含子目录 -> 递归
        val skillsDir = File(context.filesDir, FileFolders.SKILLS)
        if (skillsDir.isDirectory) {
            Log.i(TAG, "prepareBackupFile: packing skills (${skillsDir.absolutePath})")
            addDirectoryToZip(
                zipOut = zipOut,
                rootDir = skillsDir,
                currentDir = skillsDir,
                entryPrefix = "${FileFolders.SKILLS}/",
                onBytes = onBytes,
            )
        } else {
            Log.w(TAG, "prepareBackupFile: skills does not exist")
        }
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

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String, onBytes: (Long) -> Unit = {}) {
        val entry = prepareZipEntry(file, entryName)
        FileInputStream(file).use { fis ->
            zipOut.putNextEntry(entry)
            // 边拷贝边上报：单个大文件（85MB DB / 2.8GB 克隆）内部也要推进，否则进度条会冻住
            copyReporting(fis, zipOut, onBytes)
            zipOut.closeEntry()
        }
    }

    /**
     * 按可压缩性准备条目（大文件加固 P0，设计稿 §九）：
     * 头部采样判定为不可压缩（git pack / 图片 / 视频等）→ `STORED` 原样拷贝，
     * **DEFLATE 的单核 CPU 成本归零**（实测 24 MB/s × 2.8GB ≈ 数分钟白烧）。
     *
     * Java API 要求 STORED 条目在 `putNextEntry` 前预置 size/crc ⇒ 一次 CRC 预读。
     * 该预读是纯顺序读（无 DEFLATE CPU），远比「1 次读 + 压缩」便宜；进度只按写入
     * 遍计（预读不报，避免双倍计数）。
     */
    private fun prepareZipEntry(file: File, entryName: String): ZipEntry {
        val entry = ZipEntry(entryName)
        if (file.length() < BackupZipStrategy.SAMPLE_SIZE) return entry
        val sample = FileInputStream(file).use { input ->
            val buf = ByteArray(BackupZipStrategy.SAMPLE_SIZE)
            val read = input.read(buf)
            if (read <= 0) null else buf.copyOf(read)
        } ?: return entry
        if (!BackupZipStrategy.shouldStoreUncompressed(sample)) return entry

        val crc = java.util.zip.CRC32()
        var size = 0L
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
                size += n
            }
        }
        Log.i(TAG, "prepareZipEntry: $entryName stored uncompressed (${size} bytes, incompressible)")
        return entry.apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
        onBytes: (Long) -> Unit,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(zipOut, rootDir, file, entryPrefix, onBytes)
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath", onBytes)
            }
        }
    }

    // ---- 恢复辅助（严格对齐原 S3Sync/WebDavSync 行为）----

    /**
     * 恢复 `upload/` 与（旧备份的）`tool_outputs/` 条目到同名目录。
     *
     * 安全：canonical 解析后必须仍位于该目录内（防 zip-slip）。
     * 注：upload 是**平铺**文件（子目录不会被打包），但仍接受带子目录的条目（向后兼容）。
     */
    private fun restoreFlatEntry(zipIn: ZipInputStream, entryName: String, folder: String) {
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
    private fun restoreFontEntry(zipIn: ZipInputStream, entryName: String) {
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
    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
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
