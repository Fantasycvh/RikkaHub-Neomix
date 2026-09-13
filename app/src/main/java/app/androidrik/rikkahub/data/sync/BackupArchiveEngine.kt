package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator
import app.androidrik.rikkahub.data.datastore.SettingsStore
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
) {
    private val TAG = "BackupArchive"

    /**
     * 生成备份 zip（写入 cacheDir）。
     *
     * 内容：settings.json（恒含）+ 按 [items] 选择性打包 DATABASE / FILES / WORKSPACE_FILES。
     */
    suspend fun prepareBackupFile(items: Collection<BackupItem>): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")
        if (backupFile.exists()) backupFile.delete()

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value),
            )

            if (BackupItem.DATABASE in items) {
                addDatabaseToZip(zipOut)
            }
            if (BackupItem.FILES in items) {
                addAppFilesToZip(zipOut)
            }
            if (BackupItem.WORKSPACE_FILES in items) {
                val wsDir = WorkspaceBackupHelper.workspacesDir(context)
                val roots = WorkspaceBackupHelper.listWorkspaceRoots(wsDir)
                val count = WorkspaceBackupHelper.addWorkspaceFilesToZip(
                    zipOut = zipOut,
                    workspaceBaseDir = wsDir,
                    roots = roots,
                )
                Log.i(TAG, "prepareBackupFile: backed up $count workspace file(s) from ${roots.size} workspace(s)")
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created ${backupFile.name} (${backupFile.length().fileSizeToString()})",
        )
        backupFile
    }

    /**
     * 从备份 zip 恢复。
     *
     * @return 恢复的工作区文件数（0 = 未包含/未恢复）；调用方据此提示「rootfs 需重装」
     */
    suspend fun restoreFromBackupFile(backupFile: File, items: Collection<BackupItem>): Int =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting from ${backupFile.absolutePath}")
            var workspaceFilesRestored = 0

            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
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
                                    val target = when (zipEntry.name) {
                                        "rikka_hub.db" -> context.getDatabasePath("rikka_hub")
                                        "rikka_hub-wal" -> File(
                                            context.getDatabasePath("rikka_hub").parentFile,
                                            "rikka_hub-wal",
                                        )

                                        else -> File(
                                            context.getDatabasePath("rikka_hub").parentFile,
                                            "rikka_hub-shm",
                                        )
                                    }
                                    Log.i(TAG, "restoreFromBackupFile: Restoring ${zipEntry.name} -> ${target.absolutePath}")
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { zipIn.copyTo(it) }
                                    Log.i(TAG, "restoreFromBackupFile: Restored ${zipEntry.name} (${target.length()} bytes)")
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
            }

            Log.i(TAG, "restoreFromBackupFile: completed")
            if (workspaceFilesRestored > 0) {
                // rootfs 不在备份内（见设计稿）：工作区文件可用，但 shell/终端需重装 rootfs
                Log.i(
                    TAG,
                    "restoreFromBackupFile: $workspaceFilesRestored workspace file(s) restored - " +
                        "rootfs NOT included; reinstall to use shell/terminal",
                )
            }
            workspaceFilesRestored
        }

    // ---- 打包辅助 ----

    private fun addDatabaseToZip(zipOut: ZipOutputStream) {
        val dbFile = context.getDatabasePath("rikka_hub")
        if (!dbFile.exists()) return
        addFileToZip(zipOut, dbFile, "rikka_hub.db")
        listOf("rikka_hub-wal", "rikka_hub-shm").forEach { name ->
            val f = File(dbFile.parentFile, name)
            if (f.exists()) addFileToZip(zipOut, f, name)
        }
    }

    /** files/upload、files/skills、files/fonts、files/tool_outputs */
    private fun addAppFilesToZip(zipOut: ZipOutputStream) {
        // upload / fonts / tool_outputs 是平铺文件（非递归）
        listOf(FileFolders.UPLOAD, FileFolders.FONTS, FileFolders.TOOL_OUTPUTS).forEach { folder ->
            val dir = File(context.filesDir, folder)
            if (dir.isDirectory) {
                Log.i(TAG, "prepareBackupFile: packing $folder (${dir.absolutePath})")
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) addFileToZip(zipOut, file, "$folder/${file.name}")
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
            )
        } else {
            Log.w(TAG, "prepareBackupFile: skills does not exist")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(content.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            zipOut.putNextEntry(ZipEntry(entryName))
            fis.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(zipOut, rootDir, file, entryPrefix)
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    // ---- 恢复辅助（严格对齐原 S3Sync/WebDavSync 行为）----

    /**
     * 恢复 `upload/` 与 `tool_outputs/` 条目到同名目录。
     *
     * 安全：canonical 解析后必须仍位于该目录内（防 zip-slip）。
     * 注：upload 与 tool_outputs 都是**平铺**文件（子目录不会被打包），但仍接受带子目录的条目（向后公容）。
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
