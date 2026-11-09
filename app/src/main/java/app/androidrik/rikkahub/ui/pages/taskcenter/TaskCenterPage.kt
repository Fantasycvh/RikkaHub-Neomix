package app.androidrik.rikkahub.ui.pages.taskcenter

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.androidrik.rikkahub.data.task.TaskVisibility
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.task.TaskCancelResult
import app.androidrik.rikkahub.data.task.TaskCard
import app.androidrik.rikkahub.data.task.TaskSource
import app.androidrik.rikkahub.data.task.TaskState
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunsVM
import app.androidrik.rikkahub.ui.pages.agentrun.DispatchDialog
import app.androidrik.rikkahub.ui.theme.CustomColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Time02
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TaskTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTaskTime(ts: Long?): String =
    ts?.let { TaskTimeFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"

/**
 * 任务中心（P0，`design-agent-task-runtime.md` §5.5 第 1 条）。
 *
 * 三段合一：Agent 运行 / 后台命令 / 定时任务。全部数据来自 `TaskCenter` 的 Flow，
 * 页面本身不轮询、不缓存状态；取消后由 Flow 自动刷新（不等下一次进入）。
 */
@Composable
fun TaskCenterPage(vm: TaskCenterVM = koinViewModel()) {
    val navController = LocalNavController.current
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf<TaskSource?>(null) }
    // 任务中心是入口收敛后的唯一入口：新建运行（旧 Agent 运行页的 FAB 迁到这里）
    val agentRunsVm: AgentRunsVM = koinViewModel()
    val assistants by agentRunsVm.assistants.collectAsStateWithLifecycle()
    var showDispatch by rememberSaveable { mutableStateOf(false) }

    if (filter == null || filter == TaskSource.AGENT) LifecycleStartEffect(Unit) {
        val lease = TaskVisibility.acquire("all")
        onStopOrDispose { lease.close() }
    }
    val visible = tasks.filter { filter == null || it.source == filter }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.task_center_title)) },
                navigationIcon = { BackButton() },
                // 定时任务管理页（旧设置页入口迁到这里，作为任务中心的二级详情页）
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.SettingScheduledTasks) }) {
                        Icon(
                            imageVector = HugeIcons.Time02,
                            contentDescription = stringResource(R.string.setting_page_scheduled_tasks),
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        // 新建运行（旧 Agent 运行页的 FAB 迁到这里）
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!agentRunsVm.canStartRun()) {
                        toaster.show(
                            resources.getString(R.string.agent_run_dispatch_busy, agentRunsVm.activeRunCount())
                        )
                    } else {
                        showDispatch = true
                    }
                },
            ) {
                Icon(
                    imageVector = HugeIcons.Rocket01,
                    contentDescription = stringResource(R.string.agent_run_dispatch_title),
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text(stringResource(R.string.task_center_filter_all)) },
                )
                FilterChip(
                    selected = filter == TaskSource.AGENT,
                    onClick = { filter = TaskSource.AGENT },
                    label = { Text(stringResource(R.string.task_center_filter_agent)) },
                )
                FilterChip(
                    selected = filter == TaskSource.SHELL,
                    onClick = { filter = TaskSource.SHELL },
                    label = { Text(stringResource(R.string.task_center_filter_shell)) },
                )
                FilterChip(
                    selected = filter == TaskSource.SCHEDULED,
                    onClick = { filter = TaskSource.SCHEDULED },
                    label = { Text(stringResource(R.string.task_center_filter_scheduled)) },
                )
            }

            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.task_center_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 三段：需要人 → 进行中 → 最近（P0.6，对照 dsh-superagent 的 Ticket stage 表）。
                // 只在有内容时出现段头；健康状态不占空间。
                val needsYou = visible.filter { it.needsPerson }
                val active = visible.filter { !it.needsPerson && !it.state.isTerminal }
                val recent = visible.filter { !it.needsPerson && it.state.isTerminal }
                val cardContent: @Composable (TaskCard) -> Unit = { card ->
                    TaskCenterCard(
                        card = card,
                        onClick = {
                            when (val key = card.key) {
                                is app.androidrik.rikkahub.data.task.TaskKey.Agent ->
                                    navController.navigate(Screen.AgentRunDetail(key.runId))

                                is app.androidrik.rikkahub.data.task.TaskKey.Scheduled ->
                                    navController.navigate(Screen.SettingScheduledTaskDetail(key.taskId))

                                is app.androidrik.rikkahub.data.task.TaskKey.Shell -> scope.launch {
                                    val workspaceId = vm.workspaceIdByRoot(key.root)
                                    if (workspaceId != null) {
                                        // 后台命令的详情面 = 工作区详情里的后台任务卡（含输出）
                                        navController.navigate(Screen.WorkspaceDetail(workspaceId))
                                    } else {
                                        // 工作区已删：命令只活在进程内注册表，没有详情可看
                                        navController.navigate(Screen.Workspaces)
                                    }
                                }
                            }
                        },
                        onCancel = {
                            scope.launch {
                                val result = vm.cancel(card.key)
                                toaster.show(
                                    resources.getString(
                                        when (result) {
                                            TaskCancelResult.REQUESTED -> R.string.task_center_cancel_requested
                                            TaskCancelResult.NOT_ACTIVE -> R.string.task_center_cancel_not_active
                                            TaskCancelResult.NOT_FOUND -> R.string.task_center_cancel_not_found
                                        }
                                    )
                                )
                            }
                        },
                        onReviewApproval = {
                            val key = card.key
                            if (key is app.androidrik.rikkahub.data.task.TaskKey.Agent) {
                                navController.navigate(Screen.AgentRunDetail(key.runId))
                            }
                        },
                    )
                }
                val sectionAttention = stringResource(R.string.task_center_section_attention)
                val sectionActive = stringResource(R.string.task_center_section_active)
                val sectionRecent = stringResource(R.string.task_center_section_recent)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部留出 FAB（新建运行）的空间，最后一张卡不被遮
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    taskSection(sectionAttention, needsYou, cardContent)
                    taskSection(sectionActive, active, cardContent)
                    taskSection(sectionRecent, recent, cardContent)
                }
            }
        }
    }

    if (showDispatch) {
        DispatchDialog(
            assistants = assistants,
            catalogProvider = agentRunsVm::toolCatalog,
            onDismiss = { showDispatch = false },
            onDispatch = { profile, goal, tools ->
                val id = agentRunsVm.dispatch(profile, goal, tools)
                if (id == null) {
                    // 与旧运行列表一致：带活跃数，避免 `%1$d` 字面显示
                    toaster.show(
                        resources.getString(R.string.agent_run_dispatch_busy, agentRunsVm.activeRunCount())
                    )
                } else {
                    showDispatch = false
                }
            },
        )
    }
}

@Composable
private fun TaskCenterCard(
    card: TaskCard,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    onReviewApproval: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = taskStateLabel(card.state),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (card.state.isTerminal) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            card.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatTaskTime(card.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (card.pendingApprovalCount > 0 || card.canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (card.pendingApprovalCount > 0) {
                        TextButton(onClick = onReviewApproval) {
                            Text(
                                stringResource(
                                    R.string.task_center_pending_approvals,
                                    card.pendingApprovalCount,
                                )
                            )
                        }
                    }
                    if (card.canCancel) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.task_center_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun taskStateLabel(state: TaskState): String = stringResource(
    when (state) {
        TaskState.PENDING -> R.string.agent_run_status_pending
        TaskState.RUNNING -> R.string.agent_run_status_running
        TaskState.WAITING_APPROVAL -> R.string.agent_run_status_awaiting_approval
        TaskState.COMPLETED -> R.string.agent_run_status_completed
        TaskState.FAILED -> R.string.agent_run_status_failed
        TaskState.CANCELLED -> R.string.agent_run_status_cancelled
        TaskState.TIMED_OUT -> R.string.agent_run_status_timeout
        TaskState.INTERRUPTED -> R.string.agent_run_status_interrupted
    }
)

/** 一个段头 + 该段的卡片；空段整体不出现（「健康状态不占空间」）。 */
private fun LazyListScope.taskSection(
    title: String,
    cards: List<TaskCard>,
    cardContent: @Composable (TaskCard) -> Unit,
) {
    if (cards.isEmpty()) return
    item(key = "section:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
    items(cards, key = { it.key.wire }) { card -> cardContent(card) }
}
