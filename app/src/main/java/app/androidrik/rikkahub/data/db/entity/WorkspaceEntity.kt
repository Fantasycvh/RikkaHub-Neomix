package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.workspace.Workspace
import app.androidrik.workspace.WorkspaceShellStatus

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // Shell 兼容模式：开启后为新终端及后续命令禁用 PRoot seccomp 加速（对齐上游 03246406）
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    /**
     * `workspace_read_file` 的**单次读取上限**（字节）：整文件读取与图片读取都受它约束。
     *
     * 为什么放工作区而不是全局：上限是**沙箱的属性**（分析型工作区想读大文件，聊天型不需要），
     * 而且工作区详情页已经有工具审批卡，用户能就地调整。
     *
     * **窗口读取（offset/limit）不受它约束**：窗口是按行流式读的，所以「读一个 200MB 日志的第
     * 1 万行」不该靠调大这个上限，而是用窗口参数——这条也是它没做成"必需调大才可用"的原因。
     */
    @ColumnInfo("max_read_file_bytes", defaultValue = "8388608")
    val maxReadFileBytes: Long = DEFAULT_MAX_READ_FILE_BYTES,
) {
    companion object {
        /** 默认 8MB（与迁移前的硬编码上限一致：老工作区行为零变化） */
        const val DEFAULT_MAX_READ_FILE_BYTES = 8L * 1024 * 1024
    }

    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
