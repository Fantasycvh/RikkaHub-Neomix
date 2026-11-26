package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.ai.account.renewCatalogSessions
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.migration.SettingsCompatDecoder
import app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FileRefBackfillPending
import app.androidrik.rikkahub.data.files.SkillPaths
import app.androidrik.rikkahub.utils.fileSizeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "BackupArchive"

internal data class MergeDatabaseOutcome(
    val mergedDb: File,
    val workDir: File,
    val mergedConversations: Int,
    val skippedConversations: Int,
)

/**
 * 共享的备份归档恢复实现：同时服务于常规 [BackupArchiveEngine] 与安全模式 [RecoveryBackupImporter]。
 *
 * 与 [BackupArchiveWriter] 同思路——不持有 `SettingsStore` 或 `AppDatabase`，由调用方注入
 * 安全备份、设置读写与（可选的）merge 数据库钩子，使安全模式在 Koin/Room 未启动时也能复用
 * 完整校验（manifest 双 SHA-256、解压字节上限、zip-slip、原版库转换、staged 换库）。
 */
internal suspend fun restoreBackupArchive(
    context: Context,
    json: Json,
    backupFile: File,
    items: Collection<BackupItem>,
    mode: RestoreMode,
    scopeOverride: BackupScope?,
    onProgress: (BackupProgress) -> Unit = {},
    createSafetyBackup: suspend () -> Unit = {},
    mergeStagedDatabase: (suspend (File) -> MergeDatabaseOutcome)? = null,
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    requirePayload: Boolean = false,
): RestoreOutcome = withContext(Dispatchers.IO) {
    Log.i(TAG, "restoreFromBackupFile: Starting from ${backupFile.absolutePath} (mode=$mode)")
    var workspaceFilesRestored = 0
    var mergedConversations = 0
    var skippedConversations = 0
    val settingsWarnings = mutableListOf<String>()
    val stagedDatabaseFiles = linkedMapOf<String, File>()

    if (BackupItem.DATABASE in items) {
        createSafetyBackup()
    }

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
    val archiveTotal = backupFile.length()
    val archiveTotalTwice = archiveTotal * 2
    val countingIn = CountingInputStream(FileInputStream(backupFile)) { consumed ->
        val progress = BackupProgress(BackupPhase.EXTRACTING, consumed, archiveTotalTwice)
        if (throttle.shouldEmit(progress)) onProgress(progress)
    }

    val seenEntries = mutableSetOf<String>()
    var settingsJson: String? = null
    var settingsSha256: String? = null
    var settingsBytes = 0L
    var stagedMainSha256: String? = null
    var stagedMainBytes = 0L
    val sectionEntries = mutableMapOf<BackupManifestSection, Int>()
    var manifest: BackupManifest? = null

    var effectiveExcludedFileGroups: Set<BackupFileGroup> = emptySet()
    fun restoreUserFileEntry(zipIn: ZipInputStream, zipEntry: ZipEntry) {
        val bounded = boundedEntryStream(zipIn)
        val spec = backupFolderSpecFor(zipEntry.name)
        if (spec != null && BackupItem.FILES in items) {
            val group = spec.group
            if (group != null && group in effectiveExcludedFileGroups) return
            when (spec.restoreKind) {
                BackupRestoreKind.FLAT -> restoreFlatEntry(context, bounded, zipEntry.name, spec.folder)
                BackupRestoreKind.FONTS -> restoreFontEntry(context, bounded, zipEntry.name)
                BackupRestoreKind.SKILLS -> restoreSkillEntry(context, bounded, zipEntry.name)
            }
            return
        }
        if (BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.TOOL_OUTPUTS}/")) {
            restoreFlatEntry(context, bounded, zipEntry.name, FileFolders.TOOL_OUTPUTS)
            return
        }
        if (BackupItem.WORKSPACE_FILES in items &&
            zipEntry.name.startsWith(WorkspaceBackupHelper.ZIP_PREFIX)
        ) {
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
                            val staged = File(context.cacheDir, "restore_${zipEntry.name}")
                            boundedEntryCopy(zipIn, zipEntry) { FileOutputStream(staged) }
                            stagedDatabaseFiles[zipEntry.name] = staged
                            if (zipEntry.name == "rikka_hub.db") {
                                stagedMainSha256 = sha256Of(staged)
                                stagedMainBytes = staged.length()
                            }
                            Log.i(
                                TAG,
                                "restoreFromBackupFile: staged ${zipEntry.name} (${staged.length()} bytes)",
                            )
                        }
                    }

                    else -> Unit
                }
                zipIn.closeEntry()
            }
        }

        if (requirePayload) {
            require(settingsJson != null || stagedDatabaseFiles.containsKey("rikka_hub.db")) {
                "Archive is not a valid Neomix/RikkaHub backup"
            }
        }

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

        val manifestScope = manifest?.scope ?: BackupScope()
        val effectiveScope = if (scopeOverride != null) {
            manifestScope.copy(
                excluded = manifestScope.excluded + scopeOverride.excluded,
                excludedFileGroups = manifestScope.excludedFileGroups + scopeOverride.excludedFileGroups,
            )
        } else {
            manifestScope
        }
        val legacyScope = (manifest?.formatVersion ?: BackupManifest.FORMAT_VERSION) < 4
        effectiveExcludedFileGroups = effectiveScope.excludedFileGroups

        if (BackupItem.FILES in items || BackupItem.WORKSPACE_FILES in items) {
            val secondPassIn = CountingInputStream(FileInputStream(backupFile)) { consumed ->
                val progress = BackupProgress(BackupPhase.EXTRACTING, archiveTotal + consumed, archiveTotalTwice)
                if (throttle.shouldEmit(progress)) onProgress(progress)
            }
            ZipInputStream(secondPassIn).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    val zipEntry = entry ?: continue
                    restoreUserFileEntry(zipIn, zipEntry)
                    zipIn.closeEntry()
                }
            }
        }

        if (BackupItem.DATABASE in items) {
            FileRefBackfillPending.clear(context.filesDir)
            prepareStagedDatabase(context, stagedDatabaseFiles)
            val stagedMain = stagedDatabaseFiles["rikka_hub.db"]
            if (stagedMain != null) {
                if (mode == RestoreMode.MERGE) {
                    val merger = requireNotNull(mergeStagedDatabase) {
                        "Merge restore requires a live database merger"
                    }
                    val outcome = merger(stagedMain)
                    stagedDatabaseFiles.keys.filter { it != "rikka_hub.db" }.forEach { name ->
                        stagedDatabaseFiles.remove(name)?.delete()
                    }
                    PendingRestoreStore.stage(
                        filesDir = context.filesDir,
                        stagedMain = outcome.mergedDb,
                        stagedWal = null,
                        stagedShm = null,
                    )
                    mergedConversations = outcome.mergedConversations
                    skippedConversations = outcome.skippedConversations
                    stagedDatabaseFiles.clear()
                    outcome.workDir.deleteRecursively()
                    Log.i(
                        TAG,
                        "restoreFromBackupFile: merged database staged " +
                            "(+${outcome.mergedConversations} new, ${outcome.skippedConversations} skipped)",
                    )
                } else {
                    PendingRestoreStore.stage(
                        filesDir = context.filesDir,
                        stagedMain = stagedMain,
                        stagedWal = stagedDatabaseFiles["rikka_hub-wal"],
                        stagedShm = stagedDatabaseFiles["rikka_hub-shm"],
                    )
                    Log.i(TAG, "restoreFromBackupFile: database staged for next startup (${stagedMain.length()} bytes)")
                }
            } else {
                Log.i(TAG, "restoreFromBackupFile: no database entry in backup; skipping database restore")
            }
        }

        settingsJson?.let { settingsString ->
            Log.i(TAG, "restoreFromBackupFile: Restoring settings (mode=$mode)")
            try {
                val migratedJson = SettingsJsonMigrator.migrate(settingsString)
                val incomingJson = Json.parseToJsonElement(migratedJson).jsonObject
                val scope = effectiveScope
                var droppedEntries: List<String> = emptyList()
                updateSettings { current ->
                    val currentJson = json.encodeToJsonElement(current).jsonObject
                    val mergedJson = BackupPortability.preserveDeviceState(
                        incoming = BackupContentFilter.preserveUnselected(
                            incomingJson,
                            currentJson,
                            scope,
                            legacy = legacyScope,
                        ),
                        current = currentJson,
                    )
                    val decoded = SettingsCompatDecoder.decode(json, mergedJson.toString())
                    droppedEntries = decoded.droppedEntries
                    val base = decoded.settings.copy(
                        providers = decoded.settings.providers.renewCatalogSessions(),
                    )
                    if (mode == RestoreMode.MERGE) {
                        BackupContentFilter.mergeSettingsById(base, current)
                    } else {
                        base
                    }
                }
                settingsWarnings += droppedEntries
                if (droppedEntries.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "restoreFromBackupFile: settings restored with " +
                            "${droppedEntries.size} unsupported entr(ies) dropped: " +
                            droppedEntries.joinToString(", "),
                    )
                } else {
                    Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                throw Exception("Failed to restore settings: ${e.message}")
            }
        }

        Log.i(TAG, "restoreFromBackupFile: completed")
        onProgress(BackupProgress(BackupPhase.EXTRACTING, archiveTotalTwice, archiveTotalTwice))
        if (workspaceFilesRestored > 0) {
            Log.i(
                TAG,
                "restoreFromBackupFile: $workspaceFilesRestored workspace file(s) restored - " +
                    "rootfs NOT included; reinstall to use shell/terminal",
            )
        }
        RestoreOutcome(
            workspaceFilesRestored = workspaceFilesRestored,
            settingsWarnings = settingsWarnings.toList(),
            mergedConversations = mergedConversations,
            skippedConversations = skippedConversations,
        )
    } finally {
        stagedDatabaseFiles.values.forEach { it.takeIf(File::exists)?.delete() }
    }
}

internal fun boundedEntryStream(zipIn: ZipInputStream): InputStream =
    object : java.io.FilterInputStream(zipIn) {
        private var total = 0L
        override fun read(): Int {
            val result = super.read()
            if (result >= 0) count(1L)
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = super.read(b, off, len)
            if (result > 0) count(result.toLong())
            return result
        }

        override fun close() {}

        private fun count(delta: Long) {
            total += delta
            if (total > BackupManifest.MAX_ENTRY_BYTES) {
                throw Exception("Backup entry exceeds the ${BackupManifest.MAX_ENTRY_BYTES.fileSizeToString()} restore limit")
            }
        }
    }

internal inline fun boundedEntryCopy(
    zipIn: ZipInputStream,
    entry: ZipEntry,
    openOutput: () -> OutputStream,
) {
    if (entry.size >= 0 && entry.size > BackupManifest.MAX_ENTRY_BYTES) {
        throw Exception("Backup entry exceeds the ${BackupManifest.MAX_ENTRY_BYTES.fileSizeToString()} restore limit")
    }
    boundedEntryStream(zipIn).use { bounded ->
        openOutput().use { output -> bounded.copyTo(output) }
    }
}

internal fun prepareStagedDatabase(context: Context, staged: MutableMap<String, File>) {
    val main = staged["rikka_hub.db"] ?: return
    when (LegacyBackupConverter.classify(main)) {
        LegacyDatabaseFamily.CURRENT -> {
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

private fun restoreFlatEntry(context: Context, zipIn: InputStream, entryName: String, folder: String) {
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

private fun restoreFontEntry(context: Context, zipIn: InputStream, entryName: String) {
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

private fun restoreSkillEntry(context: Context, zipIn: InputStream, entryName: String) {
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
