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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import app.androidrik.rikkahub.utils.toLocalDateTime
import app.androidrik.rikkahub.data.ai.MEMORY_CONSOLIDATION_ALL_HISTORY
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.ai.detailContent
import app.androidrik.rikkahub.data.ai.proposeChangeSummary
import app.androidrik.rikkahub.data.ai.memoryConsolidationCoversAllHistory
import app.androidrik.rikkahub.data.ai.detailTitle
import app.androidrik.rikkahub.data.ai.describe
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import kotlinx.coroutines.launch
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

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
            // 手动固化：动作本身没有可见结果（只影响后续请求的 system 块），
            // 所以用一条带会话数的提示给「作用面」反馈。
            onFreezeAnchors = {
                scope.launch {
                    val count = vm.freezeMemoryAnchors()
                    toaster.show(
                        message = context.getString(R.string.assistant_page_memory_freeze_done, count)
                    )
                }
            },
            // 应用建议 = **真正改记忆库**。界面已走过二次确认（勾选 → 确认页 → 确认），
            // 这里只负责执行与回执。
            onApplyProposals = { ids ->
                scope.launch {
                    val result = vm.applyProposals(ids)
                    toaster.show(
                        message = if (result.stale > 0) {
                            context.getString(
                                R.string.assistant_page_memory_consolidation_applied_stale,
                                result.applied,
                                result.stale,
                            )
                        } else {
                            context.getString(
                                R.string.assistant_page_memory_consolidation_applied,
                                result.applied,
                            )
                        }
                    )
                }
            },
            onRejectProposals = { ids -> scope.launch { vm.rejectProposals(ids) } },
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
    onFreezeAnchors: () -> Unit,
    /** 应用所选建议（**二次确认之后**才被调用；内部会真的改记忆库） */
    onApplyProposals: (Set<Long>) -> Unit,
    /** 忽略所选建议（不改记忆库） */
    onRejectProposals: (Set<Long>) -> Unit,
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
    // 被点开看详情的建议。按建议本身记住，弹层只读。
    var detailProposal by remember(assistant.id) {
        mutableStateOf<MemoryConsolidationProposal?>(null)
    }
    /** 已勾选待采纳的建议 id（用 id 而不是下标：新一轮整理会把整个列表换掉） */
    var selectedProposals by remember(assistant.id) { mutableStateOf(emptySet<Long>()) }
    /** 二次确认页是否打开。**打开它不写任何东西**，写库发生在确认按钮之后 */
    var confirmProposals by remember(assistant.id) { mutableStateOf(false) }

    // 二次确认的**第二段**：把人将要承受的改动逐条摆出来（删除要显示原内容），
    // 再让用户按一次「确认应用」。不可逆操作不放在一次点击里。
    if (confirmProposals) {
        val picked = consolidationStatus.suggestions.filter { it.id in selectedProposals }
        val deletes = picked.count { it.action == "delete" }
        AlertDialog(
            onDismissRequest = { confirmProposals = false },
            title = {
                Text(stringResource(R.string.assistant_page_memory_consolidation_confirm_title, picked.size))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_consolidation_confirm_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    picked.forEach { proposal ->
                        val target = memories.firstOrNull { it.id == proposal.targetId }
                        Text(
                            text = proposeChangeSummary(proposal, target),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (proposal.action == "delete") {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (deletes > 0) {
                        Text(
                            text = stringResource(
                                R.string.assistant_page_memory_consolidation_confirm_delete_warning,
                                deletes,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmProposals = false
                    val ids = selectedProposals
                    selectedProposals = emptySet()
                    onApplyProposals(ids)
                }) {
                    Text(stringResource(R.string.assistant_page_memory_consolidation_confirm_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmProposals = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    detailProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { detailProposal = null },
            title = { Text(proposal.detailTitle()) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 全文，不截断——这正是这个弹层存在的理由
                    Text(text = proposal.detailContent())
                    proposal.importance?.let { importance ->
                        Text(
                            text = stringResource(
                                R.string.assistant_page_memory_consolidation_detail_importance,
                                importance,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    proposal.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = stringResource(
                                R.string.assistant_page_memory_consolidation_detail_description,
                                description,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 说明「这条为什么没进库」——避免再被读成一次失败的写入
                    Text(
                        text = stringResource(R.string.assistant_page_memory_consolidation_detail_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailProposal = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

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
                    // L1 固化：进 system 层的「Anchors」块 —— 换来 system 级权重。
                    // 配额仅 5 条、且只由用户在此手动固化（AI 不得自填，见 MemoryRepository）。
                    // **只对新会话生效**：已有会话在它开始时冻过集合了，要采纳得显式按下
                    // 会话菜单「本对话固化记忆」或本页的「固化记忆」（设计稿 §5）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.assistant_page_memory_anchor),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = stringResource(R.string.assistant_page_memory_anchor_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = memory.isAnchor,
                            onCheckedChange = { update(memory.copy(isAnchor = it)) },
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
                        // docs/design/design-memory-consolidation.md §7）；关 = 0
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
            // 「整理范围」：开启时刻**同时是基线**（只对之后的新内容负责，见
            // docs/design/design-memory-consolidation.md §7）。存量历史整理要求能把基线回拨到最早 ——
            // 否则开启之前的内容**任何路径都够不到**（手动整理原先也受同一基线约束）。
            // 实现上仍只存一个 Long：0 = 关、1 = 全部历史哨兵、其余 = 开启时刻 ⇒ 无 DB 迁移。
            if (assistant.enableMemory && assistant.memoryConsolidationSince > 0L) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_consolidation_range)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.assistant_page_memory_consolidation_range_desc),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !assistant.memoryConsolidationCoversAllHistory(),
                                    onClick = {
                                        if (assistant.memoryConsolidationCoversAllHistory()) {
                                            onUpdateAssistant(
                                                assistant.copy(
                                                    memoryConsolidationSince = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    },
                                    label = {
                                        Text(stringResource(R.string.assistant_page_memory_consolidation_range_new))
                                    },
                                )
                                FilterChip(
                                    selected = assistant.memoryConsolidationCoversAllHistory(),
                                    onClick = {
                                        if (!assistant.memoryConsolidationCoversAllHistory()) {
                                            onUpdateAssistant(
                                                assistant.copy(
                                                    memoryConsolidationSince = MEMORY_CONSOLIDATION_ALL_HISTORY
                                                )
                                            )
                                        }
                                    },
                                    label = {
                                        Text(stringResource(R.string.assistant_page_memory_consolidation_range_all))
                                    },
                                )
                            }
                        }
                    },
                )
            }
            // L1 锚点集合的手动固化（设计稿 §5 的手动刷新点）。
            // 会话只在**开始时**冻结一次锚点；此后新固化的锚点不会挤进已有会话。
            // 一个明确的入口是必要的：光靠"保存记忆时顺带刷新"用户看不见这个行为，
            // 也无从表达「我现在就要让已有会话采纳当前锚点」。
            if (assistant.enableMemory) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_freeze)) },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_page_memory_freeze_desc))
                    },
                    trailingContent = {
                        TextButton(onClick = onFreezeAnchors) {
                            Text(stringResource(R.string.assistant_page_memory_freeze_action))
                        }
                    }
                )
            }
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
                                        consolidationStatus.pendingConversations,
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
                                    // 勾选 = 待采纳；点文字 = 看完整原文。两个动作分开（勾选框在左，
                                    // 文字可点）——把选择也塞进整行点击就没法再看详情了。
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = proposal.id in selectedProposals,
                                            onCheckedChange = { checked ->
                                                selectedProposals = if (checked) {
                                                    selectedProposals + proposal.id
                                                } else {
                                                    selectedProposals - proposal.id
                                                }
                                            },
                                        )
                                        // 可点开看**完整原文**：列表这行是 `describe()` 的 60 字摘要，
                                        // 而「照建议手动改」必须先看得到内容（真机反馈：#建议被截断）。
                                        // 用 Text 的 clickable 而不是外层包一层：保持与相邻行同样的左边距，
                                        // 且不给弹层目标加一个会挡住长按选择的父级手势。
                                        Text(
                                            text = proposal.describe(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { detailProposal = proposal }
                                                .padding(vertical = 4.dp),
                                        )
                                    }
                                }
                                // 二次确认的第一段：这里只是**打开确认页**，不写任何东西
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = { confirmProposals = true },
                                        enabled = selectedProposals.isNotEmpty(),
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.assistant_page_memory_consolidation_apply,
                                                selectedProposals.size,
                                            )
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            val picked = selectedProposals
                                            selectedProposals = emptySet()
                                            onRejectProposals(picked)
                                        },
                                        enabled = selectedProposals.isNotEmpty(),
                                    ) {
                                        Text(stringResource(R.string.assistant_page_memory_consolidation_reject))
                                    }
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
