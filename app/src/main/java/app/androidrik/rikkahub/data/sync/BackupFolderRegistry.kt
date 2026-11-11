package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.files.FileFolders

/**
 * 备份目录的**唯一真源**（P0，见 `docs/design/drafts/design-incremental-backup.md` §2.4）。
 *
 * 旧实现把「哪些目录进备份」拆成三份互不驱动的清单：打包常量、恢复 `when` 手写分支、
 * 护栏测试里手写的 `declared` 集合。三者漂移的直接代价已发生过两次：
 * - `backgrounds/` 进了打包清单但恢复没有分支（恢复后助手背景文件不落盘）；
 * - `avatar-frames/` 既不在打包清单也不在豁免清单（静默不备份，护栏测试也发现不了）。
 *
 * 现在收敛为 [BACKUP_FOLDER_SPECS] 一份数据，其余全部派生：
 * - 打包 / 空间预检：`BACKED_UP_FILE_FOLDERS` + [appFilesPackingPlan]；
 * - 恢复分派：[backupFolderSpecFor] + [BackupRestoreKind]（引擎不再手写目录 `when`）；
 * - 护栏测试：用反射读 [FileFolders] 全集比对（新增目录不改这里就会红）。
 *
 * 不变量：每个目录**要么进备份、要么在 [EXCLUDED_FILE_FOLDERS] 里写明理由**，两者互斥且并集 =
 * [FileFolders] 全集（由 `BackupCoverageTest` 钉住）。
 */
internal enum class BackupRestoreKind {
    /**
     * 平铺/递归写入 `files/<folder>/<相对路径>`：已存在同名文件则覆盖；
     * 子目录按需创建；canonical 校验仍由 [BackupArchiveEngine.restoreFlatEntry] 做（防 zip-slip）。
     */
    FLAT,

    /** 技能目录：用 [SkillPaths] 校验技能名与相对路径（拒绝 `..`、绝对路径等）。 */
    SKILLS,

    /** 字体：只接受不含子目录的文件名（字体目录不打包子目录）。 */
    FONTS,
}

/**
 * 一个备份目录的规格。
 *
 * @param folder `FileFolders` 中的目录名，也是 zip 条目前缀的来源（前缀不允许硬编码）
 * @param recursive 打包是否递归（`true` = 保留子目录层级）
 * @param restoreKind 恢复分派方式（见 [BackupRestoreKind]）
 * @param excludedReason null = 进备份；非 null = 豁免理由（测试强制非空）
 */
internal data class BackupFolderSpec(
    val folder: String,
    val recursive: Boolean,
    val restoreKind: BackupRestoreKind = BackupRestoreKind.FLAT,
    val excludedReason: String? = null,
) {
    val backedUp: Boolean get() = excludedReason == null
}

/**
 * 全部目录规格（顺序 = 打包顺序，影响 zip 内条目顺序，但恢复不依赖顺序）。
 *
 * 进备份的判定原则（与既有设计稿一致）：用户不可再生的数据；排除「可重建/可再生/临时」目录
 * （理由逐条写在 [EXCLUDED_FILE_FOLDERS] 的派生值里）。
 */
internal val BACKUP_FOLDER_SPECS: List<BackupFolderSpec> = listOf(
    // ---- 进备份 ----
    BackupFolderSpec(FileFolders.UPLOAD, recursive = false, restoreKind = BackupRestoreKind.FLAT),
    BackupFolderSpec(FileFolders.IMAGES, recursive = true, restoreKind = BackupRestoreKind.FLAT),
    BackupFolderSpec(FileFolders.AVATARS, recursive = false, restoreKind = BackupRestoreKind.FLAT),
    BackupFolderSpec(FileFolders.BACKGROUNDS, recursive = false, restoreKind = BackupRestoreKind.FLAT),
    // 2026-11-03（P0）：头像框素材此前既不进备份也不在豁免清单，属「静默不备份」。
    // 它是用户配置的持久图片，体积小，按平铺进备份（旧的备份包没有该目录，恢复时自然跳过）。
    BackupFolderSpec(FileFolders.AVATAR_FRAMES, recursive = false, restoreKind = BackupRestoreKind.FLAT),
    BackupFolderSpec(FileFolders.BUBBLE_TEXTURES, recursive = false, restoreKind = BackupRestoreKind.FLAT),
    BackupFolderSpec(FileFolders.SKIN_PACKAGES, recursive = false, restoreKind = BackupRestoreKind.FLAT),
    BackupFolderSpec(FileFolders.FONTS, recursive = false, restoreKind = BackupRestoreKind.FONTS),
    BackupFolderSpec(FileFolders.SKILLS, recursive = true, restoreKind = BackupRestoreKind.SKILLS),
    BackupFolderSpec(FileFolders.MEDIA_CREATION, recursive = true, restoreKind = BackupRestoreKind.FLAT),

    // ---- 豁免（理由必填）----
    BackupFolderSpec(
        FileFolders.BUILTIN_SKILLS,
        recursive = false,
        excludedReason = "启动时从 assets 解压重建",
    ),
    BackupFolderSpec(
        FileFolders.TOOL_OUTPUTS,
        recursive = false,
        excludedReason = "会话级临时产物，启动即清（2026-09-15 用户拍板；旧备份仍按 legacy 分支恢复）",
    ),
    BackupFolderSpec(
        FileFolders.PHONE_USE,
        recursive = false,
        excludedReason = "Phone Use 截图可能包含屏幕上的敏感内容，不进备份",
    ),
    BackupFolderSpec(
        FileFolders.LOGS,
        recursive = false,
        excludedReason = "诊断日志，可再生",
    ),
    BackupFolderSpec(
        FileFolders.EXPORTS,
        recursive = false,
        excludedReason = "导出留存区（带 TTL 的可再生副本，源对话已在 DATABASE 里）",
    ),
    BackupFolderSpec(
        FileFolders.SAFETY_BACKUP,
        recursive = false,
        excludedReason = "恢复前安全备份，滚动保留 3 份（design-backup-manifest-and-safety §3.4）",
    ),
    BackupFolderSpec(
        FileFolders.BACKUP_INDEX,
        recursive = false,
        excludedReason = "本地增量备份索引（哈希缓存/快照状态），可由源文件重算，不进备份",
    ),
    BackupFolderSpec(
        FileFolders.PLUGIN_REVISIONS,
        recursive = false,
        excludedReason = "插件修订历史（开发者通道，design-plugin-dev-revision-channel §1.2：" +
            "初版不入备份，重装即丢历史；生效定义本体在 DataStore，随既有路径走）",
    ),
)

/** `FILES` 项覆盖的用户数据目录（打包 / 空间预检 / 恢复分派共用）。 */
internal val BACKED_UP_FILE_FOLDERS: List<String> =
    BACKUP_FOLDER_SPECS.filter { it.backedUp }.map { it.folder }

/** [BACKED_UP_FILE_FOLDERS] 里需要递归打包的目录（其余按平铺文件打包）。 */
internal val RECURSIVE_FILE_FOLDERS: Set<String> =
    BACKUP_FOLDER_SPECS.filter { it.backedUp && it.recursive }.map { it.folder }.toSet()

/** 有意豁免的目录及理由（护栏测试会逐一断言理由仍非空）。 */
internal val EXCLUDED_FILE_FOLDERS: Map<String, String> =
    BACKUP_FOLDER_SPECS.filterNot { it.backedUp }
        .associate { it.folder to requireNotNull(it.excludedReason) }

/** 按目录名取规格（含豁免目录；找不到返回 null）。 */
internal fun backupFolderSpec(folder: String): BackupFolderSpec? =
    BACKUP_FOLDER_SPECS.firstOrNull { it.folder == folder }

/**
 * 按 zip 条目名反查**进备份**目录的规格；只匹配 `<folder>/` 前缀，避免把 `upload2/x` 误判成 `upload`。
 *
 * 恢复侧用它替代手写 `when`：新增目录只要在 [BACKUP_FOLDER_SPECS] 里登记就会自动获得恢复分支，
 * 不再出现「打包了但恢复没有分支」的漂移。
 */
internal fun backupFolderSpecFor(entryName: String): BackupFolderSpec? =
    BACKUP_FOLDER_SPECS.firstOrNull { spec ->
        spec.backedUp && entryName.startsWith("${spec.folder}/")
    }

/** 一个目录的打包方式：递归或平铺，以及 zip 内的条目前缀。 */
internal data class AppFolderPacking(
    val folder: String,
    val recursive: Boolean,
    val entryPrefix: String,
)

/**
 * `FILES` 备份的**打包计划**：把「哪些目录、是否递归、前缀是什么」变成一份可测数据。
 *
 * 抽出来的直接原因是一次真机事故（2026-10-04）：判断条件从 `== SKILLS` 改成
 * `in RECURSIVE_FILE_FOLDERS` 时，`entryPrefix` 忘了同步改、仍是硬编码的 `skills/`
 * ⇒ 新增的递归目录（images）被打成 `skills/...`：图片既不在 `images/` 下（记录找不到），
 * 恢复时还会被解到 `files/skills/`。前缀**由目录名派生**是这里的硬约束，
 * 用测试钉住比靠人眼可靠。
 */
internal fun appFilesPackingPlan(
    folders: List<String> = BACKED_UP_FILE_FOLDERS,
    recursiveFolders: Set<String> = RECURSIVE_FILE_FOLDERS,
): List<AppFolderPacking> = folders.map { folder ->
    AppFolderPacking(
        folder = folder,
        recursive = folder in recursiveFolders,
        entryPrefix = "$folder/",
    )
}
