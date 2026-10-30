package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                // 曲面屏两侧的曲率区会吃掉贴边内容，表单留足边距
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            FormItem(
                label = { Text(stringResource(R.string.scheduled_task_field_conversation)) },
                content = {
                    Text(
                        text = conversationTitles[current.conversationId]
                            ?: stringResource(R.string.scheduled_task_conversation_missing),
                    )
                },
            )
            ScheduledTaskFormFields(form)
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
