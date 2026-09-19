package app.androidrik.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.Delete01
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import app.androidrik.rikkahub.ui.components.ai.SHOW_LOREBOOK_TABS
import app.androidrik.rikkahub.data.model.entriesForDisplay
import app.androidrik.rikkahub.data.model.reorderEntries
import app.androidrik.rikkahub.data.model.updateEntry
import app.androidrik.rikkahub.utils.reorderForDragByKey
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.androidrik.ai.core.MessageRole
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.export.LorebookSerializer
import app.androidrik.rikkahub.data.export.PromptPresetSerializer
import app.androidrik.rikkahub.data.export.ModeInjectionSerializer
import app.androidrik.rikkahub.data.export.rememberExporter
import app.androidrik.rikkahub.data.export.rememberImporter
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.reconcileEntries
import app.androidrik.rikkahub.data.model.normalizeEntryPriorities
import app.androidrik.rikkahub.data.model.sortedForDisplay
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.SelectiveLogic
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.ExportDialog
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.data.model.countSystemRewritingInjections
import app.androidrik.rikkahub.data.model.migrateSystemRewritingInjections
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.utils.reorderForDrag
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PromptPage(vm: PromptVM = koinViewModel(), initialWorldbooks: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var worldbooks by rememberSaveable { mutableStateOf(initialWorldbooks && SHOW_LOREBOOK_TABS) }
    var openedPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    // #1542 复核 Tier A：一键迁移「改写 system 区」的注入条目到对话尾部（缓存止血）。
    // 无此类条目时卡片自身不渲染；迁移覆盖预设条目与世界书条目（ST 导入的 after 是主要来源）。
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val cacheMigrationCard: @Composable () -> Unit = {
        CacheMigrationCard(
            presets = settings.promptPresets,
            lorebooks = settings.lorebooks,
            onMigrate = {
                val count = countSystemRewritingInjections(settings.promptPresets, settings.lorebooks)
                val (newPresets, newLorebooks) =
                    migrateSystemRewritingInjections(settings.promptPresets, settings.lorebooks)
                vm.updateSettings(settings.copy(promptPresets = newPresets, lorebooks = newLorebooks))
                toaster.show(context.getString(R.string.prompt_page_cache_migrated, count))
            },
        )
    }
    val managementBar: @Composable () -> Unit = {
        if (SHOW_LOREBOOK_TABS) {
            NavigationBar {
                NavigationBarItem(
                    selected = !worldbooks,
                    onClick = { worldbooks = false },
                    icon = { Icon(HugeIcons.MagicWand01, null) },
                    label = { Text(stringResource(R.string.compress_preset_label)) },
                )
                NavigationBarItem(
                    selected = worldbooks,
                    onClick = { worldbooks = true },
                    icon = { Icon(HugeIcons.Book01, null) },
                    label = { Text(stringResource(R.string.extension_selector_tab_lorebooks)) },
                )
            }
        }
    }
    if (worldbooks) {
        val book = settings.lorebooks.find { it.id.toString() == openedBookId }
        if (book == null) {
            val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            Scaffold(
                topBar = {
                    LargeFlexibleTopAppBar(
                        navigationIcon = { BackButton() },
                        title = { Text(stringResource(R.string.prompt_page_title)) },
                        scrollBehavior = scrollBehavior,
                        colors = CustomColors.topBarColors,
                    )
                },
                bottomBar = managementBar,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = CustomColors.topBarColors.containerColor,
            ) { padding ->
                LorebookTab(
                    lorebooks = settings.lorebooks,
                    onUpdate = { vm.updateSettings(settings.copy(lorebooks = it)) },
                    budgetTokens = settings.lorebookInjectBudgetTokens,
                    onBudgetChange = { vm.updateSettings(settings.copy(lorebookInjectBudgetTokens = it)) },
                    onOpenBook = { openedBookId = it.id.toString() },
                    contentPadding = padding,
                    migrationCard = cacheMigrationCard,
                )
            }
        } else {
            BackHandler { openedBookId = null }
            key(book.id) {
                LorebookDetailScreen(
                    book = book,
                    onUpdateBook = { updated ->
                        vm.updateSettings(settings.copy(lorebooks = settings.lorebooks.map {
                            if (it.id == updated.id) updated else it
                        }))
                    },
                    onBack = { openedBookId = null },
                )
            }
        }
        return
    }

    val presets = settings.promptPresets
    val opened = openedPresetId?.let { id -> presets.find { it.id.toString() == id } }

    // 预设被删除 / 被导入替换后 id 会失效 → 自动回到列表，避免停在空白详情页
    if (opened == null) {
        PresetListScreen(
            presets = presets,
            bottomBar = managementBar,
            onUpdatePresets = { vm.updateSettings(settings.copy(promptPresets = it)) },
            onOpenPreset = { openedPresetId = it.id.toString() },
            migrationCard = cacheMigrationCard,
        )
    } else {
        // 详情页是**页内**二级页面（同一 route），系统返回键须先回到预设列表，
        // 否则会直接退出整个「提示词」页（与 WorkspaceDetailPage 同款处理）。
        BackHandler { openedPresetId = null }
        PresetDetailScreen(
            preset = opened,
            onUpdatePreset = { updated ->
                vm.updateSettings(
                    settings.copy(
                        promptPresets = presets.map { if (it.id == updated.id) updated else it }
                    )
                )
            },
            onBack = { openedPresetId = null },
        )
    }
}

/**
 * 预设列表（「扩展管理」里提示词的中心页）。
 *
 * 对齐 ST 的 Prompt Manager 组织方式：**多个具名预设**，每个内含一串有序条目；
 * 打开某个预设进入详情编辑。见 `docs/peer-apps-benchmark.md` §6.7 ⑤。
 */
@Composable
private fun PresetListScreen(
    presets: List<PromptPreset>,
    onUpdatePresets: (List<PromptPreset>) -> Unit,
    onOpenPreset: (PromptPreset) -> Unit,
    bottomBar: @Composable () -> Unit,
    migrationCard: (@Composable () -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current
    // ⚠️ 必须用 rememberUpdatedState 取「最新」列表：
    // rememberImporter 内部是 `remember(serializer) { ImporterState(... onResult) }`，
    // 只按 serializer 缓存 ⇒ 首次组合时传入的 onResult 会被**永久捕获**。
    // 而 PromptVM.settings 的初始值是 Settings.dummy()（只有一个默认预设）⇒
    // 若直接闭包捕获 presets，导入时会用「dummy 的 1 个预设 + 新导入」覆盖真实列表
    // （真机实测：14 个条目被写成 1 个）。
    // 同页的 ModeInjectionTab/LorebookTab 也用了同一手法（那正是为了防这个坑）。
    val currentPresets by rememberUpdatedState(presets)
    val editState = useEditState<PromptPreset> { edited ->
        val index = presets.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdatePresets(presets.toMutableList().apply { set(index, edited) })
        } else {
            onUpdatePresets(presets + edited)
        }
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 用 reorderForDrag：拖到列表末尾时 to.index == size，手写 remove+add 会越界崩溃
        onUpdatePresets(presets.reorderForDrag(from.index, to.index))
    }
    val toaster = LocalToaster.current
    val importer = rememberImporter(PromptPresetSerializer) { result ->
        result.onSuccess { imported ->
            onUpdatePresets(currentPresets + imported)
            toaster.show(context.getString(R.string.export_import_success))
        }.onFailure { error ->
            toaster.show(context.getString(R.string.export_import_failed).format(error.message))
        }
    }

    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_title)) },
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
                if (migrationCard != null) {
                    item(key = "cache_migration") { migrationCard() }
                }
                if (presets.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_page_preset_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.prompt_page_preset_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    items(presets, key = { it.id }) { preset ->
                        ReorderableItem(state = reorderableState, key = preset.id) { isDragging ->
                            PresetCard(
                                preset = preset,
                                modifier = Modifier
                                    .longPressDraggableHandle()
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.03f
                                            scaleY = 1.03f
                                        }
                                    },
                                onOpen = { onOpenPreset(preset) },
                                onEdit = { editState.open(preset) },
                                onToggleEnabled = { on ->
                                    onUpdatePresets(
                                        presets.map {
                                            if (it.id == preset.id) it.copy(enabled = on) else it
                                        }
                                    )
                                },
                                onDelete = { onUpdatePresets(presets - preset) },
                            )
                        }
                    }
                }
            }

            HorizontalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset - innerPadding.calculateBottomPadding()),
                leadingContent = {
                    IconButton(onClick = { importer.importFromFile() }) {
                        Icon(HugeIcons.FileImport, null)
                    }
                },
            ) {
                Button(onClick = { editState.open(PromptPreset()) }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.prompt_page_add_preset))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            PresetEditDialog(
                preset = state,
                onDismiss = { editState.dismiss() },
                onEdit = { editState.currentState = it },
                onConfirm = { editState.confirm() },
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: PromptPreset,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.DragDropVertical,
                contentDescription = stringResource(R.string.prompt_page_preset_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed_preset) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                if (preset.description.isNotBlank()) {
                    Text(
                        text = preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(type = TagType.INFO) {
                        Text(stringResource(R.string.prompt_page_preset_entry_count, preset.entries.size))
                    }
                    if (!preset.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
            }
            // 行内开关：与截图一致（不进详情即可停用整套预设）
            Switch(checked = preset.enabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.prompt_page_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PresetEditDialog(
    preset: PromptPreset,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptPreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_page_edit_preset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = preset.name,
                    onValueChange = { onEdit(preset.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = preset.description,
                    onValueChange = { onEdit(preset.copy(description = it)) },
                    label = { Text(stringResource(R.string.prompt_page_preset_description)) },
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

/**
 * 预设详情：该预设内条目的增删改 + 拖拽排序 + 逐条开关。
 *
 * 与 ST 截图的「条目」tab 对齐：一串可拖拽、可开关的条目；**显示顺序即生效顺序**
 * （顺序通过重写 priority 落实，见 `normalizeEntryPriorities`）。
 */
@Composable
private fun PresetDetailScreen(
    preset: PromptPreset,
    onUpdatePreset: (PromptPreset) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current

    val displayed = remember(preset.entries) { preset.entries.sortedForDisplay() }
    // 同 PresetListScreen 的理由：rememberImporter 只按 serializer 缓存 onResult，
    // 首次组合捕获的 preset 会过期（详情页首帧可能仍是旧值）。
    val currentPreset by rememberUpdatedState(preset)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = displayed.reorderForDrag(from.index, to.index)
        // 拖拽即把新顺序写回 priority（priority 是唯一真相）
        onUpdatePreset(preset.copy(entries = normalizeEntryPriorities(moved)))
    }
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
            toaster.show(context.getString(R.string.export_import_success))
        }.onFailure { error ->
            toaster.show(context.getString(R.string.export_import_failed).format(error.message))
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
                    items(displayed, key = { it.id }) { injection ->
                        ReorderableItem(state = reorderableState, key = injection.id) { isDragging ->
                            ModeInjectionCard(
                                injection = injection,
                                modifier = Modifier
                                    .longPressDraggableHandle()
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                    },
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
private fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
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
            )
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
private fun ModeInjectionEditSheet(
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
private fun InjectionPositionSelector(
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

private fun InjectionPosition.usesStandaloneMessage(): Boolean = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT -> false

    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AT_DEPTH,
    InjectionPosition.END_OF_CHAT -> true
}

@Composable
private fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
    InjectionPosition.END_OF_CHAT -> stringResource(R.string.prompt_page_position_end_of_chat)
}

@Composable
private fun InjectionRoleSelector(
    role: MessageRole,
    position: InjectionPosition,
    onSelect: (MessageRole) -> Unit
) {
    if (position == InjectionPosition.END_OF_CHAT) {
        Text(stringResource(R.string.prompt_page_tail_user_role_hint))
        return
    }
    Select(
        options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** 次要键逻辑的展示文案（对齐 ST `world_info_logic` 的四种语义）。 */
@Composable
private fun SelectiveLogic.labelRes(): Int = when (this) {
    SelectiveLogic.AND_ANY -> R.string.prompt_page_secondary_logic_and_any
    SelectiveLogic.NOT_ALL -> R.string.prompt_page_secondary_logic_not_all
    SelectiveLogic.NOT_ANY -> R.string.prompt_page_secondary_logic_not_any
    SelectiveLogic.AND_ALL -> R.string.prompt_page_secondary_logic_and_all
}

/**
 * 关键词列表编辑器（主键与次要键共用，避免两处 UI 漂移）。
 *
 * @param title 区块标题（用于区分「关键词」与「次要关键词」）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordEditor(
    title: String,
    keywords: List<String>,
    onKeywordsChange: (List<String>) -> Unit,
) {
    var newKeyword by remember { mutableStateOf("") }

    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keywords.forEach { keyword ->
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(keyword) },
                trailingIcon = {
                    IconButton(
                        onClick = { onKeywordsChange(keywords - keyword) },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                    }
                }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = newKeyword,
            onValueChange = { newKeyword = it },
            label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        IconButton(
            onClick = {
                if (newKeyword.isNotBlank()) {
                    onKeywordsChange(keywords + newKeyword.trim())
                    newKeyword = ""
                }
            }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
        }
    }
}

@Composable
private fun getRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    else -> role.name
}

/**
 * 世界书注入的 token 预算设置卡。
 *
 * 0 = 不限制（默认，保持旧行为）。作用：命中条目超过预算时按 priority 降序裁剪，
 * 避免导入大型世界书后**静默**塞满上下文。见 `docs/peer-apps-benchmark.md` §6.2 ③。
 */
@Composable
private fun LorebookBudgetCard(
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
private fun LorebookTab(
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
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = currentLorebooks.reorderForDragByKey(from.key, to.key) { it.id }
        if (moved !== currentLorebooks) currentOnUpdate(moved)
    }
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
                items(lorebooks, key = { it.id }) { book ->
                    ReorderableItem(
                        state = reorderableState,
                        key = book.id
                    ) { isDragging ->
                        LorebookCard(
                            book = book,
                            modifier = Modifier
                                .longPressDraggableHandle()
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
private fun LorebookCard(
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
private fun LorebookEditDialog(
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
private fun LorebookDetailScreen(
    book: Lorebook,
    onUpdateBook: (Lorebook) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val displayed = remember(book.entries) { book.entriesForDisplay() }
    val currentBook by rememberUpdatedState(book)
    val currentOnUpdate by rememberUpdatedState(onUpdateBook)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = currentBook.reorderEntries(from.key, to.key)
        if (moved !== currentBook) currentOnUpdate(moved)
    }
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
                    items(displayed, key = { it.id }) { injection ->
                        ReorderableItem(state = reorderableState, key = injection.id) { isDragging ->
                            RegexInjectionEntryCard(
                                entry = injection,
                                modifier = Modifier
                                    .longPressDraggableHandle()
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                    },
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
private fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
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
            )
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
private fun RegexInjectionEditDialog(
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
