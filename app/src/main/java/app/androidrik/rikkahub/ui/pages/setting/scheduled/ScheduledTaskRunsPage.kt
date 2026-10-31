package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 任务运行历史二级页（P3）：从详情页的「运行历史」入口进入，整页摊开账本
 * （详情页只留一行摘要，几十条历史塞在编辑表单中间既挤又喧宾夺主）。
 *
 * 每行：开始时刻 + 触发入口 + 耗时 → 下一行结果状态（未收尾 = 执行层中途死亡，红色标出）。
 * 行可点：本轮有实际会话（NEW_EACH_RUN 各行不同）就跳过去看结果。
 */
@Composable
fun ScheduledTaskRunsPage(
    taskId: String,
    vm: ScheduledTaskDetailVM = org.koin.androidx.compose.koinViewModel(
        parameters = { org.koin.core.parameter.parametersOf(taskId) },
    ),
) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_run_history)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (runs.isEmpty()) {
            Text(
                text = stringResource(R.string.scheduled_task_run_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(16.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            items(runs, key = { it.id }) { run ->
                val statusText = run.status?.let { runStatusLabel(it) }
                    ?: stringResource(R.string.scheduled_task_run_unfinished)
                val conversationId = run.conversationId
                Column(
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
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = runTimeText(run.startedAt),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = listOfNotNull(
                                runSourceLabel(run.source),
                                run.durationText()?.let { " · $it" },
                            ).joinToString(""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
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

/**
 * 逐轮结果的**纯状态词**（与 [statusLabel] 的「上次运行：X」句式区分开——逐轮行不带前缀）。
 * 与 [TaskRunStatus] 一一对应；未知值原样展示（新版本加的状态在旧版本上不断言）。
 */
@Composable
internal fun runStatusLabel(status: String): String = when (status) {
    TaskRunStatus.OK -> stringResource(R.string.scheduled_task_run_ok)
    TaskRunStatus.FAILED -> stringResource(R.string.scheduled_task_run_failed)
    TaskRunStatus.TIMEOUT -> stringResource(R.string.scheduled_task_run_timeout)
    TaskRunStatus.SKIPPED_MISSED -> stringResource(R.string.scheduled_task_run_skipped_missed)
    TaskRunStatus.TARGET_GONE -> stringResource(R.string.scheduled_task_run_target_gone)
    TaskRunStatus.ASSISTANT_GONE -> stringResource(R.string.scheduled_task_run_assistant_gone)
    else -> status
}

/** 触发入口文案；与 RunSource 常量一一对应，未知值原样展示（新版入口在旧版上不断言） */
@Composable
internal fun runSourceLabel(source: String): String = when (source) {
    ScheduledTaskRunEntity.RunSource.ALARM -> stringResource(R.string.scheduled_task_run_source_alarm)
    ScheduledTaskRunEntity.RunSource.SWEEP -> stringResource(R.string.scheduled_task_run_source_sweep)
    ScheduledTaskRunEntity.RunSource.SKIP -> stringResource(R.string.scheduled_task_run_source_skip)
    else -> source
}

/** 账本时刻：本地时区 MM-dd HH:mm:ss（秒级才分得清同分钟内的重试/补跑） */
internal fun runTimeText(startedAt: Long): String =
    Instant.ofEpochMilli(startedAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

/** 本轮耗时（收尾才有）；未收尾返回 null——不给「还在跑」的错觉 */
private fun app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity.durationText(): String? {
    val finished = finishedAt ?: return null
    val seconds = (finished - startedAt) / 1000.0
    return if (seconds >= 60) {
        val minutes = seconds / 60
        String.format(java.util.Locale.ROOT, "%.1fmin", minutes)
    } else {
        String.format(java.util.Locale.ROOT, "%.1fs", seconds)
    }
}
