package app.androidrik.rikkahub.ui.pages.extensions

// 批次 6 拆出：自 PromptPage.kt 机械搬移（函数体零改动，private → internal）。
// 存放世界书（Lorebook）Tab、详情与条目编辑：
// LorebookBudgetCard / LorebookTab / LorebookCard / LorebookEditDialog /
// LorebookDetailScreen / RegexInjectionEntryCard / RegexInjectionEditDialog。

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.export.LorebookSerializer
import app.androidrik.rikkahub.data.export.rememberExporter
import app.androidrik.rikkahub.data.export.rememberImporter
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.SelectiveLogic
import app.androidrik.rikkahub.data.model.entriesForDisplay
import app.androidrik.rikkahub.data.model.updateEntry
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
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Tools
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 世界书注入的 token 预算设置卡。
 *
 * 0 = 不限制（默认，保持旧行为）。作用：命中条目超过预算时按 priority 降序裁剪，
 * 避免导入大型世界书后**静默**塞满上下文。见 `docs/peer-apps-benchmark.md` §6.2 ③。
 */
@Composable
internal fun LorebookBudgetCard(
    budgetTokens: Int,
    onBudgetChange: (Int) -> Unit,
) {
    var text by remember(budgetTokens) { mutableStateOf(budgetTokens.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_page_lorebook_budget),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.prompt_page_lorebook_budget_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    // 空输入 = 0（不限制）；非法输入不落库，避免把设置改成意外值
                    if (input.isBlank()) {
                        onBudgetChange(0)
                    } else {
                        input.toIntOrNull()?.takeIf { it >= 0 }?.let(onBudgetChange)
                    }
                },
                label = { Text(stringResource(R.string.prompt_page_lorebook_budget_label)) },
                supportingText = {
                    Text(
                        if (budgetTokens <= 0) {
                            stringResource(R.string.prompt_page_lorebook_budget_unlimited)
                        } else {
                            stringResource(R.string.prompt_page_lorebook_budget_tokens, budgetTokens)
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ==================== Lorebook Tab ====================

@Composable
internal fun LorebookTab(
    lorebooks: List<Lorebook>,
    onUpdate: (List<Lorebook>) -> Unit,
    budgetTokens: Int,
    onBudgetChange: (Int) -> Unit,
    onOpenBook: (Lorebook) -> Unit,
    contentPadding: PaddingValues,
    migrationCard: (@Composable () -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentLorebooks by rememberUpdatedState(lorebooks)
    val currentOnUpdate by rememberUpdatedState(onUpdate)
    // 拖动只改本地顺序、松手才落盘（设置异步回流会抽搐）
    val bookDragOrder = remember { DragOrderState<Lorebook>() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        bookDragOrder.onMove(currentLorebooks) { it.reorderForDragByKey(from.key, to.key) { book -> book.id } }
    }
    LaunchedEffect(currentLorebooks) { bookDragOrder.syncFromSource(currentLorebooks) }
    val editState = useEditState<Lorebook> { edited ->
        val existing = currentLorebooks.find { it.id == edited.id }
        currentOnUpdate(if (existing == null) currentLorebooks + edited else currentLorebooks.map {
            if (it.id == edited.id) it.copy(name = edited.name, description = edited.description) else it
        })
    }

    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            currentOnUpdate(currentLorebooks + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = contentPadding + PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            item("lorebookBudget") {
                LorebookBudgetCard(
                    budgetTokens = budgetTokens,
                    onBudgetChange = onBudgetChange,
                )
            }
            if (lorebooks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_empty),
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
                if (migrationCard != null) {
                    item(key = "cache_migration") { migrationCard() }
                }
                items(bookDragOrder.pendingOrder ?: lorebooks, key = { it.id }) { book ->
                    ReorderableItem(
                        state = reorderableState,
                        key = book.id
                    ) { isDragging ->
                        LorebookCard(
                            book = book,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        bookDragOrder.onDragStopped { order -> currentOnUpdate(order) }
                                    },
                                )
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onOpen = { onOpenBook(book) },
                            onEdit = { editState.open(book) },
                            onToggleEnabled = { enabled ->
                                currentOnUpdate(currentLorebooks.map {
                                    if (it.id == book.id) it.copy(enabled = enabled) else it
                                })
                            },
                            onDelete = { currentOnUpdate(currentLorebooks.filterNot { it.id == book.id }) }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset - contentPadding.calculateBottomPadding()),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(Lorebook()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_lorebook))
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            LorebookEditDialog(
                book = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
internal fun LorebookCard(
    book: Lorebook,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

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
                        showDeleteDialog = true
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
            onClick = onOpen,
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_lorebook) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.description.isNotEmpty()) {
                        Text(
                            text = book.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Tag(type = TagType.INFO) {
                            Text(
                                stringResource(
                                    R.string.prompt_page_entries_count_format,
                                    book.entries.size
                                )
                            )
                        }
                        if (!book.enabled) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.prompt_page_disabled))
                            }
                        }
                    }
                }
                Switch(checked = book.enabled, onCheckedChange = onToggleEnabled)
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
internal fun LorebookEditDialog(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (Lorebook) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_page_edit_lorebook)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = book.name,
                    onValueChange = { onEdit(book.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = book.description,
                    onValueChange = { onEdit(book.copy(description = it)) },
                    label = { Text(stringResource(R.string.prompt_page_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.prompt_page_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_page_cancel)) }
        },
    )
}

@Composable
internal fun LorebookDetailScreen(
    book: Lorebook,
    onUpdateBook: (Lorebook) -> Unit,
    onBack: () -> Unit,
    /** Tier 4：从预览跳来时滚动到并高亮该条目（null = 普通进入，无高亮） */
    highlightEntryId: String? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val displayed = remember(book.entries) { book.entriesForDisplay() }
    val highlighted = rememberHighlightedEntry(highlightEntryId, displayed) { index ->
        lazyListState.animateScrollToItem(index + 1)
    }
    val currentBook by rememberUpdatedState(book)
    val currentOnUpdate by rememberUpdatedState(onUpdateBook)
    // 拖动只改本地顺序、松手才落盘（displayed 是纯显示序，落盘时才重写 priority——同 reorderEntries 语义）
    val entryDragOrder = remember { DragOrderState<PromptInjection.RegexInjection>() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        entryDragOrder.onMove(displayed) { it.reorderForDragByKey(from.key, to.key) { entry -> entry.id } }
    }
    LaunchedEffect(displayed) { entryDragOrder.syncFromSource(displayed) }
    val editState = useEditState<PromptInjection.RegexInjection> { edited ->
        currentOnUpdate(currentBook.updateEntry(edited))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                // Return to the worldbook list without popping the management route.
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = null)
                    }
                },
                title = {
                    Text(book.name.ifBlank { stringResource(R.string.prompt_page_unnamed_lorebook) })
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
                if (book.entries.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_page_entries_count_format, 0),
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
                            RegexInjectionEntryCard(
                                entry = injection,
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        onDragStopped = {
                                            // displayed 是纯显示序（priority 降序），落盘时按新顺序重写 priority
                                            entryDragOrder.onDragStopped { moved ->
                                                currentOnUpdate(
                                                    currentBook.copy(
                                                        entries = moved.mapIndexed { index, entry ->
                                                            entry.copy(priority = moved.lastIndex - index)
                                                        }
                                                    )
                                                )
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
                                    currentOnUpdate(
                                        currentBook.copy(entries = currentBook.entries.map { entry ->
                                            if (entry.id == injection.id) entry.copy(enabled = enabled) else entry
                                        })
                                    )
                                },
                                onDelete = {
                                    currentOnUpdate(currentBook.copy(
                                        entries = currentBook.entries.filterNot { it.id == injection.id }
                                    ))
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
            ) {
                Button(onClick = { editState.open(PromptInjection.RegexInjection()) }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.prompt_page_add_entry))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            RegexInjectionEditDialog(
                entry = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it },
            )
        }
    }
}


@Composable
internal fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    /** Tier 4：从拼装预览跳转来的目标条目——短暂高亮边框以指示「就是这条」 */
    highlighted: Boolean = false,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                        showDeleteDialog = true
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
            onClick = onEdit,
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            ),
            // Tier 4 跳转高亮（同 ModeInjectionCard 的理由：描边不改变内容对比度）
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
                        text = entry.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_entry) },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val enabledLabel = stringResource(R.string.prompt_page_enabled)
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "${entry.name}: $enabledLabel"
                        }
                    )
                }
                if (entry.keywords.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_page_keywords_format, entry.keywords.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.secondaryKeywords.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.prompt_page_secondary_keywords_format,
                            entry.secondaryKeywords.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text(getPositionLabel(entry.position))
                    }
                    Tag(type = TagType.DEFAULT) {
                        Text(stringResource(R.string.prompt_page_priority_format, entry.priority))
                    }
                    if (!entry.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegexInjectionEditDialog(
    entry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.RegexInjection) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_page_edit_entry)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = entry.name,
                    onValueChange = { onEdit(entry.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { onEdit(entry.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = entry.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(entry.copy(priority = p)) }
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
                    position = entry.position,
                    onSelect = { onEdit(entry.copy(position = it)) }
                )

                AnimatedVisibility(visible = entry.position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = entry.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(entry.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // 关键词
                KeywordEditor(
                    title = stringResource(R.string.prompt_page_keywords_label),
                    keywords = entry.keywords,
                    onKeywordsChange = { onEdit(entry.copy(keywords = it)) },
                )

                // 次要关键词：主键命中后用它二次收窄，防误触发（对齐 ST keysecondary）
                KeywordEditor(
                    title = stringResource(R.string.prompt_page_secondary_keywords_label),
                    keywords = entry.secondaryKeywords,
                    onKeywordsChange = { onEdit(entry.copy(secondaryKeywords = it)) },
                )

                AnimatedVisibility(visible = entry.secondaryKeywords.isNotEmpty()) {
                    Text(
                        stringResource(R.string.prompt_page_secondary_logic_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = entry.secondaryKeywords.isNotEmpty()) {
                    FormItem(
                        label = { Text(stringResource(R.string.prompt_page_secondary_logic)) },
                        tail = {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { expanded = true }) {
                                    Text(stringResource(entry.selectiveLogic.labelRes()))
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    SelectiveLogic.entries.forEach { logic ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(logic.labelRes())) },
                                            onClick = {
                                                onEdit(entry.copy(selectiveLogic = logic))
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_use_regex)) },
                    tail = {
                        Switch(
                            checked = entry.useRegex,
                            onCheckedChange = { onEdit(entry.copy(useRegex = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_case_sensitive)) },
                    tail = {
                        Switch(
                            checked = entry.caseSensitive,
                            onCheckedChange = { onEdit(entry.copy(caseSensitive = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_constant_active)) },
                    description = { Text(stringResource(R.string.prompt_page_constant_active_desc)) },
                    tail = {
                        Switch(
                            checked = entry.constantActive,
                            onCheckedChange = { onEdit(entry.copy(constantActive = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_match_assistant_prompt)) },
                    description = { Text(stringResource(R.string.prompt_page_match_assistant_prompt_desc)) },
                    tail = {
                        Switch(
                            checked = entry.matchAssistantPrompt,
                            onCheckedChange = { onEdit(entry.copy(matchAssistantPrompt = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = entry.scanDepth.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { d -> onEdit(entry.copy(scanDepth = d)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                AnimatedVisibility(visible = entry.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InjectionRoleSelector(
                            role = entry.role,
                            position = entry.position,
                            onSelect = { onEdit(entry.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = entry.content,
                    onValueChange = { onEdit(entry.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    minLines = 4
                )
            }
        },
        confirmButton = {
            val canSave = entry.keywords.isNotEmpty() || entry.constantActive
            TextButton(
                onClick = onConfirm,
                enabled = canSave
            ) {
                Text(stringResource(R.string.prompt_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.prompt_page_cancel))
            }
        }
    )
}
