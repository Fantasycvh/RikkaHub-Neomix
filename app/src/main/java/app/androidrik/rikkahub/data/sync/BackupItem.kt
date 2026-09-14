package app.androidrik.rikkahub.data.sync

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
