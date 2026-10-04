package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.files.FileFolders
import kotlinx.serialization.Serializable

/**
 * 备份内容项（S3 / WebDav / 本地导出**共用**）。
 *
 * 此前 `S3Config.BackupItem` 与 `WebDavConfig.BackupItem` 各有一份完全相同的枚举，
 * 导致每新增一项都要改多处（且极易漏改）。统一到此处。
 *
 * **序列化兼容**：kotlinx.serialization 按**枚举常量名**序列化，只要常量名不变，
 * 旧配置与旧备份的 JSON 无需迁移即可解码。新增常量不影响旧数据（缺省即未勾选）。
 */
@Serializable
enum class BackupItem {
    /** 对话记录（rikka_hub.db + wal + shm） */
    DATABASE,

    /**
     * 应用文件（files/upload、files/skills、files/fonts）。
     *
     * **不含 files/tool_outputs**（2026-09-15 决策，选项①）：它是**会话级临时产物**，
     * `RikkaHubApp.cleanupToolOutputs()` 每次启动无条件清空该目录——恢复流程末尾必然重启，
     * 打包进去也会立刻被删，只徒增 zip 体积。历史消息中的 `/tool_outputs/<id>.txt` 引用
     * 本就可能悬空，截断消息已内联前 4KB 预览兜底（见 `design-backup-workspace-files.md` §六）。
     */
    FILES,

    /** 工作区文件区（下层目录：每个工作区的 files 区；不含 rootfs） */
    WORKSPACE_FILES,
}

/**
 * `FILES` 项覆盖的**用户数据目录**清单（打包 / 恢复 / 空间预检三处由它统一驱动）。
 *
 * 此前三处各自手列目录（Yuihub `3eddec42` 指出的同款问题）：新增目录漏改一处
 * 就是「静默不备份」。护栏测试 `BackupCoverageTest` 断言本清单与 [FileFolders]
 * 声明的全集一一对应——新增目录只改 FileFolders 就会让测试红，强制显式决策
 * 「进备份还是豁免」。
 */
internal val BACKED_UP_FILE_FOLDERS: List<String> = listOf(
    FileFolders.UPLOAD,   // 聊天附件（图片/文档/音频等，含 AI 生成的图片附件）
    FileFolders.IMAGES,   // imggen 产物（此前不在备份内，恢复后图片全裂——Yuihub `34f1a976` 同款缺口）
    FileFolders.FONTS,    // 用户字体
    FileFolders.SKILLS,   // 用户技能（含子目录，递归打包）
)

/**
 * [FileFolders] 里**有意豁免**的目录（护栏测试会逐一断言豁免理由仍成立）：
 * - `builtin_skills`：内置技能，启动时从 assets 解压重建；
 * - `tool_outputs`：会话级临时产物，启动即清（见 [BackupItem.FILES] 注释）；
 * - `logs`：诊断日志，可再生；
 * - `exports`：导出留存区（带 TTL 的可再生副本，源对话已在 DATABASE 里）。
 */
internal val EXCLUDED_FILE_FOLDERS: Map<String, String> = mapOf(
    FileFolders.BUILTIN_SKILLS to "启动时从 assets 解压重建",
    FileFolders.TOOL_OUTPUTS to "会话级临时产物，启动即清",
    FileFolders.LOGS to "诊断日志，可再生",
    FileFolders.EXPORTS to "导出留存区，源对话在 DATABASE 内",
)
