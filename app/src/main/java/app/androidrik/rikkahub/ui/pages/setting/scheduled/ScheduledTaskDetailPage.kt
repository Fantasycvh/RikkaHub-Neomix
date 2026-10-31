package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.db.entity.targetModeOrDefault
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.theme.CustomColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 任务详情/编辑页（列表行点击进入）。目标会话只读展示；周期字段变更保存时重算 nextRunAt
 * 并重排唤醒作业（ScheduledTaskRepository.update / keeper.rearm）。
 */
@Composable
fun ScheduledTaskDetailPage(
    taskId: String,
    vm: ScheduledTaskDetailVM = koinViewModel(parameters = { parametersOf(taskId) }),
) {
    val task by vm.task.collectAsStateWithLifecycle()
    val conversationTitles by vm.conversationTitles.collectAsStateWithLifecycle()
    val conversationGoals by vm.conversationGoals.collectAsStateWithLifecycle()
    val assistantNames by vm.assistantNames.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_detail_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.delete() }) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val current = task ?: return@Scaffold
        val form = rememberScheduledTaskFormState(current)
        // 「附带长期目标」仅当目标会话写了 goal 才可选；每轮新建没有固定会话，恒不可选
        val withGoalAvailable = targetModeOrDefault(current.targetMode) != TargetMode.NEW_EACH_RUN &&
            !conversationGoals[current.conversationId].isNullOrBlank()
        Column(
            modifier = Modifier
                .padding(innerPadding)
                // 曲面屏两侧的曲率区会吃掉贴边内容，表单留足边距
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                // 键盘弹起时把表单顶起来，避免「时/分」被键盘压住后触发滚动抖动
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            FormItem(
                label = { Text(stringResource(R.string.scheduled_task_field_target)) },
                content = {
                    Text(targetDescription(current, conversationTitles, assistantNames))
                },
            )
            // 「每轮新建」没有固定会话可看，给一个跳到最近一轮结果的入口（没有跑过就不显示）
            val lastConversationId = current.lastConversationId
            if (targetModeOrDefault(current.targetMode) == TargetMode.NEW_EACH_RUN &&
                !lastConversationId.isNullOrBlank()
            ) {
                TextButton(onClick = { navController.navigate(Screen.Chat(lastConversationId)) }) {
                    Text(stringResource(R.string.scheduled_task_view_last_conversation))
                }
            }
            ScheduledTaskFormFields(form, withGoalAvailable = withGoalAvailable)
            RunHistoryCard(vm, navController)
            FormItem(
                label = { Text(stringResource(R.string.scheduled_task_enabled)) },
                tail = {
                    Switch(checked = current.enabled, onCheckedChange = { vm.setEnabled(it) })
                },
            )
            Button(
                onClick = { vm.save(form) },
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                enabled = form.isValid,
            ) {
                Text(stringResource(R.string.scheduled_task_save))
            }
        }
    }
}

/**
 * 运行历史卡片（P2 运行账本）：最近 [RUN_HISTORY_LIMIT] 次执行（新→旧）。
 * `status == null`（finished_at 空白）= 执行层中途死亡——这不是「加载中」，是进程被杀的
 * 直接证据，单独文案标出（peer-apps「无头运行显式终态」的 UI 面）。
 * 行可点：本轮有实际会话（NEW_EACH_RUN 各行不同）就跳过去看结果。
 */
@Composable
private fun RunHistoryCard(vm: ScheduledTaskDetailVM, navController: Navigator) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    FormItem(label = { Text(stringResource(R.string.scheduled_task_run_history)) })
    if (runs.isEmpty()) {
        Text(
            text = stringResource(R.string.scheduled_task_run_empty),
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    } else {
        runs.forEach { run ->
            val statusText = run.status?.let { statusLabel(it) }
                ?: stringResource(R.string.scheduled_task_run_unfinished)
            val sourceText = runSourceLabel(run.source)
            val conversationId = run.conversationId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (conversationId != null) {
                            Modifier.clickable {
                                navController.navigate(Screen.Chat(conversationId))
                            }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = runTimeText(run.startedAt) + " · " + sourceText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (run.status == TaskRunStatus.OK) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

/** 账本时刻展示：本地时区 MM-dd HH:mm:ss（秒级才分得清同分钟内的重试/补跑） */
private fun runTimeText(startedAt: Long): String =
    java.time.Instant.ofEpochMilli(startedAt)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

@Composable
private fun runSourceLabel(source: String): String = when (source) {
    // 与 RunSource 常量一一对应；未知值原样展示（新版本加的入口在旧版本上也不断言）
    ScheduledTaskRunEntity.RunSource.ALARM -> stringResource(R.string.scheduled_task_run_source_alarm)
    ScheduledTaskRunEntity.RunSource.SWEEP -> stringResource(R.string.scheduled_task_run_source_sweep)
    ScheduledTaskRunEntity.RunSource.SKIP -> stringResource(R.string.scheduled_task_run_source_skip)
    else -> source
}
