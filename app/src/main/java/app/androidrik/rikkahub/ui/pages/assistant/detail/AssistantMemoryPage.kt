package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import androidx.compose.material3.CircularProgressIndicator
import app.androidrik.rikkahub.utils.toLocalDateTime
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.ai.describe
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import java.time.Instant
import java.time.ZoneId
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.EditStateContent
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.writeClipboardText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf


@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    // 做梦（V3）的状态：待整理轮数 / 进行中 / 上次结果（含本次建议）
    val consolidationStatus by vm.consolidationStatus.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) },
            consolidationStatus = consolidationStatus,
            onOrganize = { vm.organizeMemory() },
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    /** 做梦（V3）的状态与手动整理入口（在顶层收集后传进来：CardGroup 的 DSL 不是 composable 作用域） */
    consolidationStatus: MemoryConsolidationStatus,
    onOrganize: () -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id.isEmpty()) {   // 空 id = 新建（原为 id == 0）
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var showTimeReminderIntervalDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeReminderIntervalInput by remember(assistant.id) { mutableStateOf("") }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = memory.content,
                        onValueChange = {
                            update(memory.copy(content = it))
                        },
                        label = {
                            Text(stringResource(R.string.assistant_page_manage_memory_title))
                        },
                        minLines = 2,
                        maxLines = 8
                    )
                    // 主题词（清单行主体）：预算外的记忆只以这一行露出，故可在此补/改。
                    // 留空=不写清单主题（渲染时用内容截断兜底），不是错误状态。
                    TextField(
                        value = memory.description,
                        onValueChange = { update(memory.copy(description = it)) },
                        label = {
                            Text(stringResource(R.string.assistant_page_memory_description_label))
                        },
                        supportingText = {
                            Text(stringResource(R.string.assistant_page_memory_description_hint))
                        },
                        minLines = 1,
                        maxLines = 3
                    )
                    // 重要度：决定该记忆在预算内能否进上下文（模型写入时给初值，用户可在此覆盖）。
                    // 注入预算有限，故这个值直接影响「谁会出现在后续对话里」。
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_page_memory_importance),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "%.2f".format(memory.importance),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = memory.importance,
                            onValueChange = { update(memory.copy(importance = it)) },
                            valueRange = 0f..1f,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_global_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    useGlobalMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory_modify_approval)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_modify_approval_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.memoryModifyApproval,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    memoryModifyApproval = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory_create_approval)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_create_approval_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.memoryCreateApproval,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    memoryCreateApproval = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory_consolidation)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_consolidation_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        // 开 = 记下开启时刻（它同时是「只对之后的新内容负责」的基线，见
                        // docs/design-memory-consolidation.md §7）；关 = 0
                        checked = assistant.memoryConsolidationSince > 0L,
                        onCheckedChange = { checked ->
                            onUpdateAssistant(
                                assistant.copy(
                                    memoryConsolidationSince = if (checked) System.currentTimeMillis() else 0L
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            // 做梦（V3）的「整理一下」+ 状态 + 本次建议。
            // 建议**只活在进程内**（决策 B：不落库、不做一键应用），重启即失；要按它改就在下面手动改。
            if (assistant.memoryConsolidationSince > 0L) {
                val lastRun = consolidationStatus.lastRun
                // 建议「黏」在状态里（见 MemoryConsolidationStatus）：随后的空跑不会把它冲掉
                val suggestions = consolidationStatus.suggestions
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_consolidate_now)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = when {
                                    consolidationStatus.running ->
                                        stringResource(R.string.assistant_page_memory_consolidation_running)

                                    consolidationStatus.pendingTurns > 0 -> stringResource(
                                        R.string.assistant_page_memory_consolidation_pending,
                                        consolidationStatus.pendingTurns,
                                    )

                                    lastRun != null -> stringResource(
                                        R.string.assistant_page_memory_consolidation_last,
                                        lastRun.changedMemories,
                                        lastRun.proposals.size,
                                    )

                                    else -> stringResource(R.string.assistant_page_memory_consolidation_never)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            lastRun?.error?.let { error ->
                                Text(text = error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (suggestions.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.assistant_page_memory_consolidation_proposals,
                                        Instant.ofEpochMilli(consolidationStatus.suggestionsAt).toLocalDateTime(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                suggestions.forEach { proposal ->
                                    Text(
                                        text = proposal.describe(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    trailingContent = {
                        if (consolidationStatus.running) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            TextButton(onClick = onOrganize) {
                                Text(stringResource(R.string.assistant_page_memory_consolidate_action))
                            }
                        }
                    }
                )
            }
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_time_reminder_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableTimeReminder = it
                                )
                            )
                        }
                    )
                }
            )
            if (assistant.enableTimeReminder) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_value, assistant.timeReminderIntervalMinutes)) },
                    onClick = {
                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                        showTimeReminderIntervalDialog = true
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = {
                    memoryDialogState.open(AssistantMemory("", ""))
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = null
                )
            }
        }

        memories.fastForEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = {
                        pendingDeleteMemory = it
                    }
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // id 是随机 opaque token、不便记忆——整行可点复制，方便用户粘到对话里引用该条记忆
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            context.writeClipboardText(memory.id)
                            toaster.show(copiedText)
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "#${memory.id}",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.assistant_page_copy_memory_id),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 主题词（渐进披露的清单行主体）：模型没写就不占位置，不拿内容截断冒充
                if (memory.description.isNotBlank()) {
                    Text(
                        text = memory.description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = memory.content,

                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                // 读取遥测：让「这条记忆到底有没有起作用」可见（V2 只显示，不参与打分）。
                // 从未被读过就不显示——零读数是常态，不必占一行。
                if (memory.readCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.assistant_page_memory_read_count,
                            memory.readCount,
                            memory.lastReadAt.takeIf { it > 0L }?.let {
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                    .toString()
                            } ?: "-",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(HugeIcons.PencilEdit01, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
