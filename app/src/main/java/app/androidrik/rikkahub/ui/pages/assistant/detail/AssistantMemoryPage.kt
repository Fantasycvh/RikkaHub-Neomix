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
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
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
            onUpdateMemory = { vm.updateMemory(it) }
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
                Text(
                    text = memory.content,

                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
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
