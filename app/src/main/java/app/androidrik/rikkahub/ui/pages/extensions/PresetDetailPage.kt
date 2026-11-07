package app.androidrik.rikkahub.ui.pages.extensions

// 批次 6 拆出：自 PromptPage.kt 机械搬移（函数体零改动，private → internal）。
// 存放预设详情（PresetDetailScreen + ModeInjectionCard / ModeInjectionEditSheet /
// InjectionPositionSelector / InjectionRoleSelector）。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.MessageRole
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.export.ModeInjectionSerializer
import app.androidrik.rikkahub.data.export.rememberExporter
import app.androidrik.rikkahub.data.export.rememberImporter
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.normalizeEntryPriorities
import app.androidrik.rikkahub.data.model.sortedForDisplay
import app.androidrik.rikkahub.ui.components.ui.ExportDialog
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.DragOrderState
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.utils.reorderForDragByKey
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Tools
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 预设详情：该预设内条目的增删改 + 拖拽排序 + 逐条开关。
 *
 * 与 ST 截图的「条目」tab 对齐：一串可拖拽、可开关的条目；**显示顺序即生效顺序**
 * （顺序通过重写 priority 落实，见 `normalizeEntryPriorities`）。
 */
@Composable
internal fun PresetDetailScreen(
    preset: PromptPreset,
    onUpdatePreset: (PromptPreset) -> Unit,
    onBack: () -> Unit,
    /** Tier 4：从预览跳来时滚动到并高亮该条目（null = 普通进入，无高亮） */
    highlightEntryId: String? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val resources by rememberUpdatedState(LocalResources.current)

    val displayed = remember(preset.entries) { preset.entries.sortedForDisplay() }
    // Tier 4：滚动到目标条目（+1 补「顺序提示」那一行）并短暂高亮，让用户一眼看到「就是这条」。
    // 只在首次进入生效（rememberSaveable 的 id 已消费），后续手动滚动不被拉回。
    val highlighted = rememberHighlightedEntry(highlightEntryId, displayed) { index ->
        lazyListState.animateScrollToItem(index + 1)
    }
    // 同 PresetListScreen 的理由：rememberImporter 只按 serializer 缓存 onResult，
    // 首次组合捕获的 preset 会过期（详情页首帧可能仍是旧值）。
    val currentPreset by rememberUpdatedState(preset)
    // 拖动只改本地顺序、松手才落盘（priority 是唯一真相，落盘时统一重写）
    val entryDragOrder = remember { DragOrderState<PromptInjection.ModeInjection>() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // **按 key 映射**：本列表首行是「顺序提示」（orderHint）非数据行，绝对下标会错位
        entryDragOrder.onMove(displayed) { it.reorderForDragByKey(from.key, to.key) { entry -> entry.id } }
    }
    LaunchedEffect(displayed) { entryDragOrder.syncFromSource(displayed) }
    val editState = useEditState<PromptInjection.ModeInjection> { edited ->
        val index = preset.entries.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdatePreset(
                preset.copy(entries = preset.entries.toMutableList().apply { set(index, edited) })
            )
        } else {
            onUpdatePreset(preset.copy(entries = preset.entries + edited))
        }
    }
    val toaster = LocalToaster.current
    val importer = rememberImporter(ModeInjectionSerializer) { result ->
        result.onSuccess { imported ->
            onUpdatePreset(currentPreset.copy(entries = currentPreset.entries + imported))
            toaster.show(resources.getString(R.string.export_import_success))
        }.onFailure { error ->
            toaster.show(resources.getString(R.string.export_import_failed).format(error.message))
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                // 详情页的返回是**页内返回**（回到预设列表），不能用 BackButton——
                // 它就带 navController.popBackStack()，会直接退出整个提示词页。
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = null)
                    }
                },
                title = {
                    Text(preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed_preset) })
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false }
                    ),
                contentPadding = innerPadding + PaddingValues(bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                if (preset.entries.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_page_mode_injection_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.prompt_page_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    item("orderHint") {
                        Text(
                            text = stringResource(R.string.prompt_page_priority_order_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    items(entryDragOrder.pendingOrder ?: displayed, key = { it.id }) { injection ->
                        ReorderableItem(state = reorderableState, key = injection.id) { isDragging ->
                            ModeInjectionCard(
                                injection = injection,
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        onDragStopped = {
                                            // 拖拽即把新顺序写回 priority（priority 是唯一真相）
                                            entryDragOrder.onDragStopped { moved ->
                                                onUpdatePreset(preset.copy(entries = normalizeEntryPriorities(moved)))
                                            }
                                        },
                                    )
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                    },
                                highlighted = injection.id.toString() == highlighted,
                                onEdit = { editState.open(injection) },
                                onToggleEnabled = { enabled ->
                                    onUpdatePreset(
                                        currentPreset.copy(entries = currentPreset.entries.map { entry ->
                                            if (entry.id == injection.id) entry.copy(enabled = enabled) else entry
                                        })
                                    )
                                },
                                onDelete = {
                                    onUpdatePreset(preset.copy(entries = preset.entries - injection))
                                },
                            )
                        }
                    }
                }
            }

            HorizontalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
                leadingContent = {
                    IconButton(onClick = { importer.importFromFile() }) {
                        Icon(HugeIcons.FileImport, null)
                    }
                },
            ) {
                Button(onClick = { editState.open(PromptInjection.ModeInjection()) }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.prompt_page_add_mode_injection))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it },
            )
        }
    }
}


@Composable
internal fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    /** Tier 4：从拼装预览跳转来的目标条目——短暂高亮边框以指示「就是这条」 */
    highlighted: Boolean = false,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(injection, ModeInjectionSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            ),
            // Tier 4 跳转高亮：用 primary 描边而非改底色——描边不改变内容对比度，
            // 且与「启用/停用」等既有视觉语义不冲突。
            border = if (highlighted) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = injection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val enabledLabel = stringResource(R.string.prompt_page_enabled)
                    Switch(
                        checked = injection.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "${injection.name}: $enabledLabel"
                        }
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text(getPositionLabel(injection.position))
                    }
                    Tag(type = TagType.DEFAULT) {
                        Text(stringResource(R.string.prompt_page_priority_format, injection.priority))
                    }
                    if (!injection.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            HugeIcons.Delete01,
                            stringResource(R.string.prompt_page_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }

    if (showDeleteDialog) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.prompt_page_delete),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false },
            text = { Text(stringResource(R.string.prompt_page_delete_confirm)) },
        )
    }
}

@Composable
internal fun ModeInjectionEditSheet(
    injection: PromptInjection.ModeInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.ModeInjection) -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = injection.name,
                    onValueChange = { onEdit(injection.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = injection.enabled,
                            onCheckedChange = { onEdit(injection.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = injection.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(injection.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = injection.position,
                    onSelect = { onEdit(injection.copy(position = it)) }
                )

                AnimatedVisibility(visible = injection.position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = injection.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(injection.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                AnimatedVisibility(visible = injection.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InjectionRoleSelector(
                            role = injection.role,
                            position = injection.position,
                            onSelect = { onEdit(injection.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = injection.content,
                    onValueChange = { onEdit(injection.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    minLines = 5
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
internal fun InjectionPositionSelector(
    position: InjectionPosition,
    onSelect: (InjectionPosition) -> Unit
) {
    Select(
        options = InjectionPosition.entries,
        selectedOption = position,
        onOptionSelected = onSelect,
        optionToString = { getPositionLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun InjectionRoleSelector(
    role: MessageRole,
    position: InjectionPosition,
    onSelect: (MessageRole) -> Unit
) {
    if (position == InjectionPosition.BEFORE_SYSTEM_PROMPT ||
        position == InjectionPosition.AFTER_SYSTEM_PROMPT
    ) {
        // 这两个位置的内容**一律合并进系统提示正文**（见 applyInjections），角色不参与任何判断
        // ⇒ 给一个无效的选择器只会误导（用户会以为选了 assistant 就以助手身份发送）。
        Text(
            text = stringResource(R.string.prompt_page_system_prompt_position_role_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (position == InjectionPosition.END_OF_CHAT) {
        // 尾部支持 USER（原行为）与 SYSTEM（#1542 分段式系统提示）；ASSISTANT 禁止（预填充）
        Select(
            options = listOf(MessageRole.USER, MessageRole.SYSTEM),
            selectedOption = role,
            onOptionSelected = onSelect,
            optionToString = { getRoleLabel(it) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(
                if (role == MessageRole.SYSTEM) R.string.prompt_page_tail_system_role_hint
                else R.string.prompt_page_tail_user_role_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    // 中部位置（TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH）：三种角色都放开。
    // SYSTEM 此前缺席（只有尾部给过）⇒ 注入器即便支持也无从配置（③-3b 的可用性缺口）。
    Select(
        options = listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource(
            when {
                role == MessageRole.SYSTEM -> R.string.prompt_page_mid_system_role_hint
                role == MessageRole.ASSISTANT -> R.string.prompt_page_mid_assistant_role_hint
                else -> R.string.prompt_page_mid_user_role_hint
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
