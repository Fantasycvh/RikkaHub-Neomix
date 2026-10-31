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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
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
 *
 * revision 8 起「执行目标」三选一（§4.1）：已有会话（按助手分组挑）/ 专属新会话 / 每轮新建，
 * 后两者选助手即可，不必先手动建好会话。
 */
@Composable
fun ScheduledTaskCreatePage(
    /** 会话内入口预填：进来时目标已定（EXISTING + 该会话），prompt 给一个默认模板 */
    prefillConversationId: String? = null,
    vm: ScheduledTasksVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val form = rememberScheduledTaskFormState(null)
    val assistants by vm.assistants.collectAsStateWithLifecycle()

    var targetMode by remember { mutableStateOf(TargetMode.EXISTING) }
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    var selectedAssistant by remember { mutableStateOf<Assistant?>(null) }
    var showConversationPicker by remember { mutableStateOf(false) }
    var showAssistantPicker by remember { mutableStateOf(false) }

    // 会话内入口：目标预选（设计稿 §七「会话内创建入口」）。prompt 不预填模板文本——
    // 任务内容是用户的意图，替用户写一句模板反而要再删；预选目标 + 若会话有 goal
    // 自动出现「附带长期目标」开关，就是入口的全部便利。
    LaunchedEffect(prefillConversationId) {
        prefillConversationId?.let { id ->
            vm.conversationById(id)?.let { selectedConversation = it }
        }
    }
    // 保存时目标已被删掉的竞态：不静默吞掉，显示一行错误（见 TargetFailure）
    var targetFailure by remember { mutableStateOf<TargetFailure?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 目标是否已选全：已有会话要选会话，另两种要选助手
    val targetReady = when (targetMode) {
        TargetMode.EXISTING -> selectedConversation != null
        TargetMode.DEDICATED, TargetMode.NEW_EACH_RUN -> selectedAssistant != null
    }
    // 「附带长期目标」只有目标会话写了 goal 才有意义（§四：目标是会话的属性）
    val withGoalAvailable = targetMode == TargetMode.EXISTING && !selectedConversation?.goal.isNullOrBlank()
    // 自动清理只对「每轮新建」有意义（§4.2）
    val keepLastVisible = targetMode == TargetMode.NEW_EACH_RUN

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_create)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        enabled = targetReady && form.isValid,
                        onClick = {
                            vm.create(
                                targetMode = targetMode,
                                conversation = selectedConversation,
                                assistant = selectedAssistant,
                                title = form.title,
                                prompt = form.prompt,
                                scheduleType = form.type,
                                payload = form.payload(),
                                missPolicy = form.missPolicy,
                                idleOnly = form.idleOnly,
                                withGoal = form.withGoal && withGoalAvailable,
                                keepLastConversations = if (keepLastVisible) (form.keepLast ?: 0) else 0,
                                onCreated = { navController.popBackStack() },
                                onFailed = { targetFailure = it },
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
            FormItem(label = { Text(stringResource(R.string.scheduled_task_field_target)) }) {
                SegmentedPill(
                    options = TargetMode.entries,
                    selectedOption = targetMode,
                    onOptionSelected = {
                        targetMode = it
                        targetFailure = null
                    },
                    optionToString = {
                        when (it) {
                            TargetMode.EXISTING -> stringResource(R.string.scheduled_task_target_existing)
                            TargetMode.DEDICATED -> stringResource(R.string.scheduled_task_target_dedicated)
                            TargetMode.NEW_EACH_RUN -> stringResource(R.string.scheduled_task_target_new_each_run)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = when (targetMode) {
                    TargetMode.EXISTING -> stringResource(R.string.scheduled_task_target_hint_existing)
                    TargetMode.DEDICATED -> stringResource(R.string.scheduled_task_target_hint_dedicated)
                    TargetMode.NEW_EACH_RUN -> stringResource(R.string.scheduled_task_target_hint_new_each_run)
                },
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    if (targetMode == TargetMode.EXISTING) {
                        showConversationPicker = true
                    } else {
                        showAssistantPicker = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when (targetMode) {
                        TargetMode.EXISTING -> selectedConversation?.title?.ifEmpty {
                            stringResource(R.string.scheduled_task_conversation_untitled)
                        } ?: stringResource(R.string.scheduled_task_pick_conversation)

                        TargetMode.DEDICATED, TargetMode.NEW_EACH_RUN ->
                            selectedAssistant?.name?.ifBlank { null }
                                ?: stringResource(R.string.scheduled_task_pick_assistant)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            targetFailure?.let { failure ->
                Text(
                    text = when (failure) {
                        TargetFailure.CONVERSATION_MISSING ->
                            stringResource(R.string.scheduled_task_conversation_missing)

                        TargetFailure.ASSISTANT_MISSING ->
                            stringResource(R.string.scheduled_task_assistant_missing)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            ScheduledTaskFormFields(
                form,
                withGoalAvailable = withGoalAvailable,
                keepLastVisible = keepLastVisible,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showConversationPicker) {
        ConversationPickerDialog(
            vm = vm,
            assistants = assistants,
            selectedId = selectedConversation?.id,
            onPick = {
                selectedConversation = it
                targetFailure = null
                showConversationPicker = false
            },
            onDismiss = { showConversationPicker = false },
        )
    }

    if (showAssistantPicker) {
        AssistantPickerDialog(
            assistants = assistants,
            selectedId = selectedAssistant?.id,
            onPick = {
                selectedAssistant = it
                targetFailure = null
                showAssistantPicker = false
            },
            onDismiss = { showAssistantPicker = false },
        )
    }
}

/**
 * 会话选择器：**按助手分组**（revision 8）。分组头是助手名，组内是该助手的会话；
 * 助手已被删的会话落在「未知助手」组，不能因为助手没了就选不中/看不见。
 */
@Composable
private fun ConversationPickerDialog(
    vm: ScheduledTasksVM,
    assistants: List<Assistant>,
    selectedId: kotlin.uuid.Uuid?,
    onPick: (Conversation) -> Unit,
    onDismiss: () -> Unit,
) {
    val query by vm.conversationQuery.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val groups = groupConversationsByAssistant(conversations, assistants)

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
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.scheduled_task_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    groups.forEach { group ->
                        item(key = "header-${group.assistant?.id ?: "unknown"}") {
                            Text(
                                text = group.assistant?.name
                                    ?.ifBlank { null }
                                    ?: stringResource(R.string.scheduled_task_group_unknown_assistant),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(group.conversations, key = { it.id.toString() }) { conversation ->
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
                                RadioButton(
                                    selected = conversation.id == selectedId,
                                    onClick = { onPick(conversation) },
                                )
                            }
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

/** 助手选择器：「专属新会话 / 每轮新建」两种形态共用 */
@Composable
private fun AssistantPickerDialog(
    assistants: List<Assistant>,
    selectedId: kotlin.uuid.Uuid?,
    onPick: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_assistant)) },
        text = {
            LazyColumn(modifier = Modifier.height(360.dp)) {
                items(assistants, key = { it.id.toString() }) { assistant ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(assistant) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = assistant.name.ifBlank { assistant.id.toString() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = assistant.id == selectedId,
                            onClick = { onPick(assistant) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
