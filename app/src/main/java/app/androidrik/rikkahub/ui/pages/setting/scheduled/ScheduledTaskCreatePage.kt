package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 新建任务整页表单（docs/design/design-scheduled-ai-tasks.md §七）。
 *
 * 之前这里是 `AlertDialog`：七组字段塞进一个 wrap-content 居中对话框，键盘一弹就比屏幕还高，
 * 底部字段与确认按钮被压在键盘下面；而且「时/分」正好落在可见区边缘，每敲一位都触发
 * Compose 的「滚动到光标」，把整块内容往上顶 18dp——真机 PJX110 上表现为**界面抖动**（§7.1）。
 * 改成和详情页一致的整页 + `imePadding()` + 数字键盘后，滚动只由用户发起，键盘不再遮挡输入区。
 */
@Composable
fun ScheduledTaskCreatePage(vm: ScheduledTasksVM = koinViewModel()) {
    val navController = LocalNavController.current
    val form = rememberScheduledTaskFormState(null)
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    var showConversationPicker by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_create)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        enabled = selectedConversation != null && form.isValid,
                        onClick = {
                            val conversation = selectedConversation ?: return@TextButton
                            vm.create(
                                conversation = conversation,
                                title = form.title,
                                prompt = form.prompt,
                                scheduleType = form.type,
                                payload = form.payload(),
                                missPolicy = form.missPolicy,
                                idleOnly = form.idleOnly,
                                onCreated = { navController.popBackStack() },
                            )
                        },
                    ) { Text(stringResource(R.string.scheduled_task_save)) }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                // 键盘弹起时把表单整体顶起来：底部字段不会被挡住（对话框时代这里正是重灾区）
                .imePadding()
                .verticalScroll(rememberScrollState())
                // 曲面屏两侧的曲率区会吃掉贴边内容，表单留足边距
                .padding(horizontal = 16.dp),
        ) {
            FormItem(label = { Text(stringResource(R.string.scheduled_task_field_conversation)) }) {
                OutlinedButton(
                    onClick = { showConversationPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = selectedConversation?.title?.ifEmpty {
                            stringResource(R.string.scheduled_task_conversation_untitled)
                        } ?: stringResource(R.string.scheduled_task_pick_conversation),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ScheduledTaskFormFields(form)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showConversationPicker) {
        ConversationPickerDialog(
            vm = vm,
            onPick = {
                selectedConversation = it
                showConversationPicker = false
            },
            onDismiss = { showConversationPicker = false },
        )
    }
}

@Composable
private fun ConversationPickerDialog(
    vm: ScheduledTasksVM,
    onPick: (Conversation) -> Unit,
    onDismiss: () -> Unit,
) {
    val query by vm.conversationQuery.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_conversation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setConversationQuery,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        Row(
                            // 整行可点：原来只有行尾的小圆钮能点，手指大一点就点不中
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(conversation) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = conversation.title.ifEmpty {
                                    stringResource(R.string.scheduled_task_conversation_untitled)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            RadioButton(selected = false, onClick = { onPick(conversation) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
