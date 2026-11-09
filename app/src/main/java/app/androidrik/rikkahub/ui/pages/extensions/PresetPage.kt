package app.androidrik.rikkahub.ui.pages.extensions

// 批次 6 拆出：自 PromptPage.kt 机械搬移（函数体零改动，private → internal）。
// 存放 PromptPage 骨架 + 预设列表（PresetListScreen / PresetCard / PresetEditDialog）。

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.export.PromptPresetSerializer
import app.androidrik.rikkahub.data.export.rememberImporter
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.countMigratableInjections
import app.androidrik.rikkahub.data.model.migrateInjections
import app.androidrik.rikkahub.ui.components.ai.SHOW_LOREBOOK_TABS
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.DragOrderState
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.utils.reorderForDrag
import app.androidrik.rikkahub.utils.reorderForDragByKey
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Tools
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PromptPage(
    vm: PromptVM = koinViewModel(),
    initialWorldbooks: Boolean = false,
    /** Tier 4：直接打开该容器（预设 / 世界书 id）的详情，而不是停在列表 */
    initialContainerId: String? = null,
    /** Tier 4：进入详情后滚动到并短暂高亮该条目 */
    initialEntryId: String? = null,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var worldbooks by rememberSaveable { mutableStateOf(initialWorldbooks && SHOW_LOREBOOK_TABS) }
    var openedPresetId by rememberSaveable {
        mutableStateOf(if (initialWorldbooks) null else initialContainerId)
    }
    var openedBookId by rememberSaveable {
        mutableStateOf(if (initialWorldbooks) initialContainerId else null)
    }
    // #1542 复核 Tier A + 反向：注入位置**双向**批量迁移卡（不做强制）——
    // 正向：系统区 → 对话末尾（缓存友好）；反向：对话末尾 → 系统提示词后（系统级强调，
    // 接受缓存代价）。无对应条目时卡片自身不渲染；迁移覆盖预设条目与世界书条目。
    val toaster = LocalToaster.current
    val resources by rememberUpdatedState(LocalResources.current)
    val cacheMigrationCard: @Composable () -> Unit = {
        CacheMigrationCard(
            presets = settings.promptPresets,
            lorebooks = settings.lorebooks,
            onMigrate = { direction ->
                val count = countMigratableInjections(settings.promptPresets, settings.lorebooks, direction)
                val (newPresets, newLorebooks) =
                    migrateInjections(settings.promptPresets, settings.lorebooks, direction)
                vm.updateSettings(settings.copy(promptPresets = newPresets, lorebooks = newLorebooks))
                toaster.show(resources.getString(R.string.prompt_page_cache_migrated, count))
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
                    highlightEntryId = initialEntryId,
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
            highlightEntryId = initialEntryId,
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
internal fun PresetListScreen(
    presets: List<PromptPreset>,
    onUpdatePresets: (List<PromptPreset>) -> Unit,
    onOpenPreset: (PromptPreset) -> Unit,
    bottomBar: @Composable () -> Unit,
    migrationCard: (@Composable () -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val resources by rememberUpdatedState(LocalResources.current)
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
    // 拖动只改本地顺序、松手才落盘（设置异步回流会抽搐，同 ModelList dc2a43003）
    val presetDragOrder = remember { DragOrderState<PromptPreset>() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // **按 key 映射，不要用下标**：from/to.index 是 lazy 列表的**绝对**下标（含头部非数据行），
        // 而本列表首行可能是「迁移卡」⇒ 用下标会整体错位（拖 A 实际移动了 B）。
        // 越界（拖到列表末尾）由 reorderForDrag 内部夹取，不会崩。
        presetDragOrder.onMove(presets) { it.reorderForDragByKey(from.key, to.key) { preset -> preset.id } }
    }
    LaunchedEffect(presets) { presetDragOrder.syncFromSource(presets) }
    val toaster = LocalToaster.current
    val importer = rememberImporter(PromptPresetSerializer) { result ->
        result.onSuccess { imported ->
            onUpdatePresets(currentPresets + imported)
            toaster.show(resources.getString(R.string.export_import_success))
        }.onFailure { error ->
            toaster.show(resources.getString(R.string.export_import_failed).format(error.message))
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
                    items(presetDragOrder.pendingOrder ?: presets, key = { it.id }) { preset ->
                        ReorderableItem(state = reorderableState, key = preset.id) { isDragging ->
                            PresetCard(
                                preset = preset,
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        onDragStopped = {
                                            presetDragOrder.onDragStopped { order -> onUpdatePresets(order) }
                                        },
                                    )
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
internal fun PresetCard(
    preset: PromptPreset,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

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
            // 删除先确认：预设是「一组条目」的容器，误删会连带丢掉全部条目
            // （条目级/世界书条目此前已有确认弹窗，只有预设级是直接删，属遗漏）
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.prompt_page_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
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
            text = { Text(stringResource(R.string.prompt_page_delete_preset_confirm)) },
        )
    }
}

@Composable
internal fun PresetEditDialog(
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
