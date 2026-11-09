package app.androidrik.rikkahub.ui.pages.extensions.workspace

// 批次 6 拆出：自 WorkspaceDetailPage.kt 机械搬移（函数体零改动，private → internal）。
// 存放工作区详情「基本信息」Tab：
// WorkspaceBasicPage + SshConnectionCard + PackageMirrorCard +
// WorkspaceTasksCard/WorkspaceTaskRow/taskStatusLine +
// WorkspaceToolApprovalCard/workspaceToolApprovalItems + WorkspaceInfoRow/RootfsProgress。

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.repository.AptIndexRefresh
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.CardGroupIcon
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileSizeToString
import app.androidrik.workspace.PackageManager
import app.androidrik.workspace.PackageMirrorPick
import app.androidrik.workspace.PackageMirrorSetup
import app.androidrik.workspace.RootfsInstallProgress
import app.androidrik.workspace.WorkspaceType
import app.androidrik.workspace.RootfsInstallStage
import app.androidrik.workspace.WorkspaceShellStatus
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Zap

@Composable
internal fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    tasks: List<WorkspaceTaskSnapshot>,
    tasksNow: Long,
    diskSize: Long?,
    onRefreshDiskSize: () -> Unit,
    onKillTask: (String) -> Unit,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onMaxReadBytesChange: (Long) -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
    onEditSsh: () -> Unit,
    mirrorSetupRunning: Boolean,
    mirrorPicks: List<PackageMirrorPick>,
    aptIndexRefresh: AptIndexRefresh?,
    aptIndexDetail: String?,
    onConfigureMirrors: () -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    // 加载完成前按本地工作区展示
    val hasRootfs = workspace?.capabilities()?.rootfs ?: true
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                    // 磁盘占用：本地工作区显示 + 点击行重算；SSH 在远端不标注（v1）
                    if (workspace?.type != WorkspaceType.SSH.name) {
                        WorkspaceInfoRow(
                            label = stringResource(R.string.workspace_detail_disk_usage),
                            value = diskSize?.fileSizeToString()
                                ?: stringResource(R.string.workspace_detail_disk_usage_computing),
                            onClick = onRefreshDiskSize,
                        )
                    }

                    if (hasRootfs) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.workspace_detail_shell_compatibility),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.workspace_detail_shell_compatibility_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = workspace?.shellCompatibilityMode ?: false,
                                onCheckedChange = onShellCompatibilityModeChange,
                                enabled = workspace != null,
                            )
                        }
                    }
                }
            }
        }

        if (workspace?.isSsh() == true) {
            item {
                SshConnectionCard(workspace = workspace, onEdit = onEditSsh)
            }
        }

        if (hasRootfs) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_detail_enable_shell),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.workspace_detail_enable_shell_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Button(
                            onClick = onInstallRootfs,
                            enabled = workspace != null && !installing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.Bash, contentDescription = null)
                            Text(
                                text = installButtonText,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        installProgress?.let { progress ->
                            RootfsProgress(progress)
                        }
                    }
                }
            }
        }

        if (workspace?.capabilities()?.backgroundTasks != false) {
            item {
                WorkspaceTasksCard(tasks = tasks, now = tasksNow, onKill = onKillTask)
            }
        }

        // 镜像源只对本地 rootfs 工作区有意义：SSH 工作区没有我们可控的 rootfs 可写
        if (hasRootfs && workspace?.isSsh() != true) {
            item {
                PackageMirrorCard(
                    workspace = workspace,
                    running = mirrorSetupRunning,
                    picks = mirrorPicks,
                    aptIndex = aptIndexRefresh,
                    aptIndexDetail = aptIndexDetail,
                    onConfigure = onConfigureMirrors,
                )
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
                onMaxReadBytesChange = onMaxReadBytesChange,
            )
        }
    }
}

@Composable
internal fun SshConnectionCard(
    workspace: WorkspaceEntity,
    onEdit: () -> Unit,
) {
    val settings = workspace.sshSettings()
    CardGroup(title = { Text(stringResource(R.string.workspace_detail_ssh_connection)) }) {
        item(
            leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
            headlineContent = { Text(stringResource(R.string.workspace_detail_ssh_address)) },
            supportingContent = {
                Text(settings?.let { "${it.username}@${it.host}:${it.port}" } ?: stringResource(R.string.workspace_detail_ssh_config_missing))
            },
        )
        settings?.jump?.let { jump ->
            item(
                headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_jump)) },
                supportingContent = { Text("${jump.username}@${jump.host}:${jump.port}") },
            )
        }
        item(
            leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
            headlineContent = { Text(stringResource(R.string.workspace_detail_ssh_remote_dir)) },
            supportingContent = { Text(settings?.remoteDir ?: "-") },
        )
        item(
            leadingContent = { CardGroupIcon(HugeIcons.FingerPrint) },
            headlineContent = { Text(stringResource(R.string.workspace_detail_ssh_host_key)) },
            supportingContent = {
                val hostKey = settings?.hostKey
                Text(
                    text = hostKey?.let { "${it.type}\n${it.fingerprint}" } ?: stringResource(R.string.workspace_detail_ssh_host_key_unconfirmed),
                    fontFamily = if (hostKey != null) FontFamily.Monospace else null,
                )
            },
        )
        item(
            onClick = onEdit,
            leadingContent = { CardGroupIcon(HugeIcons.PencilEdit01) },
            headlineContent = { Text(stringResource(R.string.workspace_detail_ssh_edit)) },
            supportingContent = { Text(stringResource(R.string.workspace_detail_ssh_edit_desc)) },
        )
    }
}

@Composable
internal fun PackageMirrorCard(
    workspace: WorkspaceEntity?,
    running: Boolean,
    picks: List<PackageMirrorPick>,
    aptIndex: AptIndexRefresh?,
    aptIndexDetail: String?,
    onConfigure: () -> Unit,
) {
    val context = LocalContext.current
    val setup = workspace?.packageMirrorSetup() ?: PackageMirrorSetup()
    val rows = listOf(
        PackageManager.APT to setup.aptRepoUrl,
        PackageManager.NPM to setup.npmRegistry,
        PackageManager.PIP to setup.pipIndexUrl,
        PackageManager.GO to setup.goProxy,
    ).filter { it.second.isNotBlank() }
    val rootfsReady = workspace?.shellStatus == WorkspaceShellStatus.READY.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_package_mirrors),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_package_mirrors_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.workspace_detail_package_mirrors_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEach { (manager, url) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = manager.mirrorLabel,
                            modifier = Modifier.weight(0.22f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            picks.firstOrNull { it.manager == manager }?.let { pick ->
                                Text(
                                    text = stringResource(
                                        R.string.workspace_detail_package_mirrors_pick,
                                        pick.displayName,
                                        pick.bytesPerSecond.fileSizeToString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (setup.configuredAt > 0) {
                    Text(
                        text = stringResource(
                            R.string.workspace_detail_package_mirrors_configured_at,
                            DateUtils.formatDateTime(
                                context,
                                setup.configuredAt,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (aptIndex == AptIndexRefresh.FAILED || aptIndex == AptIndexRefresh.TIMEOUT) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (aptIndex == AptIndexRefresh.TIMEOUT) {
                                R.string.workspace_detail_package_mirrors_apt_timeout
                            } else {
                                R.string.workspace_detail_package_mirrors_apt_failed
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    aptIndexDetail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (aptIndex == AptIndexRefresh.SUCCESS) {
                Text(
                    text = stringResource(R.string.workspace_detail_package_mirrors_apt_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onConfigure,
                enabled = rootfsReady && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Zap, contentDescription = null)
                Text(
                    text = stringResource(
                        if (running) {
                            R.string.workspace_detail_package_mirrors_working
                        } else {
                            R.string.workspace_detail_package_mirrors_configure
                        }
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.workspace_detail_package_mirrors_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!rootfsReady) {
                Text(
                    text = stringResource(R.string.workspace_detail_package_mirrors_need_rootfs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 包管理器在设置页里的显示名：都是产品专名，不进字符串资源。 */
internal val PackageManager.mirrorLabel: String
    get() = when (this) {
        PackageManager.APT -> "apt"
        PackageManager.NPM -> "npm"
        PackageManager.PIP -> "pip"
        PackageManager.GO -> "Go"
    }

/** 卡片里最多列几个任务；其余只报数量（避免长列表把设置项挤到屏幕外）。 */
private const val TASKS_MAX_ROWS = 5

/** 命令预览长度。 */
private const val TASK_COMMAND_PREVIEW_CHARS = 60

/**
 * 运行中/刚结束的后台任务卡片。
 *
 * 为什么需要：后台任务是「任务表在内存、输出在磁盘」的东西，而在此之前用户**只能在发起它的那条
 * 对话的工具卡片里**看到它——切走会话后既看不到手机上正跑着什么，也没有手动终止的入口。
 * 这是「AI 能起长任务」这一能力的必要配套（否则用户对设备失去可见性与控制权）。
 */
@Composable
internal fun WorkspaceTasksCard(
    tasks: List<WorkspaceTaskSnapshot>,
    now: Long,
    onKill: (String) -> Unit,
) {
    if (tasks.isEmpty()) return
    val ordered = remember(tasks) {
        tasks.sortedWith(
            compareByDescending<WorkspaceTaskSnapshot> { it.isRunning }
                .thenByDescending { it.startedAtMillis },
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_detail_tasks_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ordered.take(TASKS_MAX_ROWS).forEach { task ->
                WorkspaceTaskRow(task = task, now = now, onKill = onKill)
            }
            if (ordered.size > TASKS_MAX_ROWS) {
                Text(
                    text = stringResource(R.string.workspace_detail_tasks_more, ordered.size - TASKS_MAX_ROWS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun WorkspaceTaskRow(
    task: WorkspaceTaskSnapshot,
    now: Long,
    onKill: (String) -> Unit,
) {
    val failed = task.status == WorkspaceTaskStatus.FAILED || task.status == WorkspaceTaskStatus.TIMED_OUT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = task.command
                    .replace("\n", " ")
                    .trim()
                    .let { if (it.length > TASK_COMMAND_PREVIEW_CHARS) it.take(TASK_COMMAND_PREVIEW_CHARS) + "…" else it },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = taskStatusLine(task, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (task.isRunning) {
            TextButton(onClick = { onKill(task.id) }) {
                Text(stringResource(R.string.workspace_detail_tasks_kill))
            }
        }
    }
}

/** 状态行：本地化状态 + 剩余/已用时长 + 退出码（有则显示）。 */
@Composable
internal fun taskStatusLine(task: WorkspaceTaskSnapshot, now: Long): String {
    val status = stringResource(
        when (task.status) {
            WorkspaceTaskStatus.RUNNING -> R.string.workspace_task_status_running
            WorkspaceTaskStatus.EXITED -> R.string.workspace_task_status_exited
            WorkspaceTaskStatus.KILLED -> R.string.workspace_task_status_killed
            WorkspaceTaskStatus.TIMED_OUT -> R.string.workspace_task_status_timed_out
            WorkspaceTaskStatus.FAILED -> R.string.workspace_task_status_failed
        }
    )
    val timing = task.remainingMillis(now)
        ?.let { stringResource(R.string.workspace_detail_tasks_remaining, formatTaskDuration(it)) }
        ?: stringResource(R.string.workspace_detail_tasks_elapsed, formatTaskDuration(task.durationMillis(now)))
    val exit = task.exitCode?.let { " \u00b7 exit $it" }.orEmpty()
    return "$status \u00b7 $timing$exit"
}

/** 读取上限的可选档位（MB）；默认 8MB 与迁移前一致 */
private val MAX_READ_BYTES_CHOICES_MB = listOf(8, 16, 32, 64, 128)

@Composable
internal fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onMaxReadBytesChange: (Long) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()
    val capabilities = workspace?.capabilities()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 单次读取上限：只影响"整文件读"与图片读；窗口读（offset/limit）不受它约束
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_max_read_bytes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_max_read_bytes_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // FlowRow：窄屏上 5 个档位会挤出去（与模型页的标签排布同款）
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MAX_READ_BYTES_CHOICES_MB.forEach { mb ->
                        val bytes = mb * 1024L * 1024L
                        FilterChip(
                            selected = (workspace?.maxReadFileBytes ?: WorkspaceEntity.DEFAULT_MAX_READ_FILE_BYTES) == bytes,
                            onClick = { onMaxReadBytesChange(bytes) },
                            label = { Text("${mb}MB") },
                            enabled = workspace != null,
                        )
                    }
                }
            }

            val tools = workspaceToolApprovalItems()
            tools
                .filter { (toolName) ->
                    (capabilities == null || capabilities.fileWriteTools || toolName !in WORKSPACE_FILE_WRITE_TOOLS) &&
                        (capabilities == null || capabilities.backgroundTasks || toolName !in WORKSPACE_BACKGROUND_TOOLS)
                }
                .forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

private val WORKSPACE_FILE_WRITE_TOOLS = setOf("workspace_write_file", "workspace_edit_file")
private val WORKSPACE_BACKGROUND_TOOLS = setOf(
    "workspace_run_background",
    "workspace_task_status",
    "workspace_kill_task",
)

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
    "workspace_run_background" to stringResource(R.string.workspace_detail_tool_run_background),
    "workspace_task_status" to stringResource(R.string.workspace_detail_tool_task_status),
    "workspace_kill_task" to stringResource(R.string.workspace_detail_tool_kill_task),
)

@Composable
internal fun WorkspaceInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
