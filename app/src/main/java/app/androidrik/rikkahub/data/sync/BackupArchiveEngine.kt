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
    ): RestoreOutcome =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting from ${backupFile.absolutePath} (mode=$mode)")
            var workspaceFilesRestored = 0
            var mergedConversations = 0
            var skippedConversations = 0
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
            // 恢复读 zip 两遍（第一遍校验记账、第二遍落盘用户文件），进度总量 ×2。
            val archiveTotal = backupFile.length()
            val archiveTotalTwice = archiveTotal * 2
            val countingIn = CountingInputStream(FileInputStream(backupFile)) { consumed ->
                val progress = BackupProgress(BackupPhase.EXTRACTING, consumed, archiveTotalTwice)
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

            // FILES / WORKSPACE_FILES 条目的落盘逻辑，第二遍（校验通过后）调用；
            // 第一遍对这两类条目只记账、不写任何用户文件（见下方 else 分支注释）。
            // 生效排除组在 manifest 校验后赋值（zip 读序 manifest 在前、文件条目第二遍在后）。
            var effectiveExcludedFileGroups: Set<BackupFileGroup> = emptySet()
            fun restoreUserFileEntry(zipIn: ZipInputStream, zipEntry: ZipEntry) {
                val bounded = boundedEntryStream(zipIn)
                // 恢复分派由 BackupFolderRegistry 的规格驱动：新增目录只要登记就会自动获得恢复分支，
                // 避免旧实现「打包清单 / 恢复 when / 测试集合」三份清单漂移（backgrounds 曾因此恢复丢文件）。
                val spec = backupFolderSpecFor(zipEntry.name)
                if (spec != null && BackupItem.FILES in items) {
                    // v4：manifest scope（∪ 用户裁剪）排除的文件分组，条目直接跳过。
                    // 旧包的条目组映射照常解析（目录→组是静态登记），被排除的组同样跳过。
                    val group = spec.group
                    if (group != null && group in effectiveExcludedFileGroups) return
                    when (spec.restoreKind) {
                        BackupRestoreKind.FLAT -> restoreFlatEntry(bounded, zipEntry.name, spec.folder)
                        BackupRestoreKind.FONTS -> restoreFontEntry(bounded, zipEntry.name)
                        BackupRestoreKind.SKILLS -> restoreSkillEntry(bounded, zipEntry.name)
                    }
                    return
                }
                // legacy：旧备份里可能有 tool_outputs/（2026-09-15 起移出备份），仅兼容旧包；
                // 新备份不含它，恢复后下次启动也会被 cleanup 清掉。
                if (BackupItem.FILES in items && zipEntry.name.startsWith("${FileFolders.TOOL_OUTPUTS}/")) {
                    restoreFlatEntry(bounded, zipEntry.name, FileFolders.TOOL_OUTPUTS)
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
                                    // 仅暂存，不直接写现库（现库正被 Room 连接持有）
                                    val staged = File(context.cacheDir, "restore_${zipEntry.name}")
                                    // openOutput 只负责打开；关闭由 boundedEntryCopy 统一做。
                                    // （在此处 .use 会先关流再返回，copyTo 对已关闭流写入 → "Stream Closed"，真机踩过）
                                    boundedEntryCopy(zipIn, zipEntry) { FileOutputStream(staged) }
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
                                // FILES / WORKSPACE_FILES 是用户数据：第一遍只记账（classify
                                // 已在 when 之前计数），不落盘——manifest 校验在读毕后才进行，
                                // 先写就把「校验通过才破坏」的承诺漏掉了。条目剩余字节由下方的
                                // closeEntry 跳过；总量上限已由 BackupSpaceCheck 把关。
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

                // 生效范围 = manifest scope ∪ 用户临时勾掉的类别/分组（exclusion 并集：
                // 用户只能缩小，不能把 manifest 排除的内容勾回来）。
                // legacy：v3 及更早的包用旧类别映射（v4 拆出的细分类别键按 ABOUT 处理）。
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

                // FILES / WORKSPACE_FILES 第二遍流式写入（第一遍只记账，见上）：
                // 「校验通过才破坏」由此覆盖用户文件区，而不只 settings+数据库
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

                // B 档：**不换库**——staged 家族移交 PendingRestoreStore，重启后由
                // RikkaHubApp 在 startKoin 之前应用并失败回滚（design-backup-staged-restore §5）。
                // 现库全程保持打开：本进程不再有「Koin 持有已关闭单例」的窗口。
                // **先 stage 再写 settings**：stage 失败（典型：上次恢复未重启就再次恢复）时
                // settings 必须保持原样，否则用户面对「报错但设置已换、库没换」的半套恢复状态。
                if (BackupItem.DATABASE in items) {
                    // 换库后 message_file_ref 可能缺本表（旧版本备份）或与会话不一致：
                    // 清回填完成标记，下次启动（staged 库已应用）重灌（design-file-conversation-link.md §4.4）
                    FileRefBackfillPending.clear(context.filesDir)
                    prepareStagedDatabase(stagedDatabaseFiles)
                    val stagedMain = stagedDatabaseFiles["rikka_hub.db"]
                    if (stagedMain != null) {
                        if (mode == RestoreMode.MERGE) {
                            // merge：快照只贡献「本地没有的会话对象」，其余整表数据不覆盖——
                            // 现库 VACUUM 副本 + 会话对象插入，得到合并后的基底再走同一换库管线
                            val outcome = mergeStagedDatabaseIntoCurrent(stagedMain)
                            // 快照的 wal/shm 已在 merge 前 checkpoint 合入主文件，显式清掉
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
                            stagedDatabaseFiles.clear() // stagedMain 已被 stage move 走
                            outcome.workDir.deleteRecursively() // 基底已 move 走，清掉残留目录
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
                        // 备份来自无库环境（新装设备）：无条目可换，跳过数据库恢复（保留现库），
                        // settings/FILES 照常——空库备份不再变成永远校验失败的死包
                        Log.i(TAG, "restoreFromBackupFile: no database entry in backup; skipping database restore")
                    }
                }

                settingsJson?.let { settingsString ->
                    Log.i(TAG, "restoreFromBackupFile: Restoring settings (mode=$mode)")
                    try {
                        // 旧包名（me.rerere.*）备份的迁移必不可少
                        val migratedJson = SettingsJsonMigrator.migrate(settingsString)
                        val incomingJson = Json.parseToJsonElement(migratedJson).jsonObject
                        // v3 scope：被排除的类别保留本机值；v2 旧包 scope=null 按全类别处理（历史行为）。
                        // 回填在 update transform 内取 current 计算（单向数据流，禁锁外快照写）。
                        val scope = effectiveScope
                        var droppedEntries: List<String> = emptyList()
                        settingsStore.update { current ->
                            val currentJson = json.encodeToJsonElement(current).jsonObject
                            // 设备态字段（launchCount/importedSources/lastBackupTime）一律保留本机值，
                            // 即使旧包（本阶段之前）未清洗过也以本机为准（BackupPortability）。
                            val mergedJson = BackupPortability.preserveDeviceState(
                                incoming = BackupContentFilter.preserveUnselected(
                                    incomingJson,
                                    currentJson,
                                    scope,
                                    legacy = legacyScope,
                                ),
                                current = currentJson,
                            )
                            // 原版 3.1.0 可能带本应用没有的多态子类（chatgpt/grok/volcengine…）：
                            // 逐条降级丢弃而不是整份失败，否则连会话都恢复不了
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
                // 收尾：确保 UI 到达 100%（节流可能吞掉末次）
                onProgress(BackupProgress(BackupPhase.EXTRACTING, archiveTotalTwice, archiveTotalTwice))
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
                    mergedConversations = mergedConversations,
                    skippedConversations = skippedConversations,
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
            val staged = prepareBackupFileLocked(
                items = listOf(BackupItem.DATABASE),
                scope = BackupScope(), // 安全备份恒为全量设置
                onProgress = {},
            )
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

    // 恢复侧单条目解压上限（zip 炸弹兜底，§3.3）：`ZipEntry.size` 在 data descriptor
    // 模式下常为 -1，不能信 size 字段——用包装流边拷边数，超限抛错。
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

            // 不透传 close：FilterInputStream.close() 会关掉底层 ZipInputStream，
            // 而恢复循环还要继续读后续条目（条目末尾由外层 closeEntry 收尾）。
            override fun close() {}

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
     * stage 前的**数据库家族分流**。
     *
     * 本应用历史库（含 2.5.6 的 v25）：直接 stage，Room 启动期打开时沿迁移链升到当前版本。
     * 原版 3.x 的库（文件式记忆 + 媒体/翻译表）与本应用 v30 后 schema 分叉，
     * Room 迁移链既缺原版表、又多了本应用独有表 ⇒ 先做数据级转换，再走同一套 stage。
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
                prepareStagedDatabase(stagedDatabaseFiles)
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

    private data class MergeDatabaseOutcome(
        val mergedDb: File,
        val workDir: File,
        val mergedConversations: Int,
        val skippedConversations: Int,
    )

    // ---- 打包辅助 ----

    /**
     * 打包期的 section 记账流（design-backup-manifest-and-safety §3.1）：按条目名归类
     * （[classifyBackupEntry]，与恢复侧同一份分类防漂移），逐条目计数、按未压缩字节累加，
     * 供 manifest 写入。manifest.json 自身不进统计。
     */
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
