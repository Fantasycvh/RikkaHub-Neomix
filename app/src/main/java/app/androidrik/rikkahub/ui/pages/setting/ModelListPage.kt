package app.androidrik.rikkahub.ui.pages.setting

// 批次 5 拆出，来源 SettingProviderDetailPage

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.CursorPointer01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.BadgedBox
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.ai.catalog.ProviderModelCatalogRepository
import app.androidrik.rikkahub.data.ai.catalog.catalogCandidateModels
import app.androidrik.rikkahub.data.ai.catalog.isRefreshing
import app.androidrik.rikkahub.ui.components.ai.CatalogAutoRefreshEffect
import app.androidrik.rikkahub.ui.components.ai.CatalogProviderStatus
import app.androidrik.rikkahub.ui.components.ai.CatalogModelMarker
import app.androidrik.rikkahub.ui.components.ai.rememberCatalogState
import app.androidrik.rikkahub.utils.reorderForDragByKey
import app.androidrik.ai.provider.filterByKeywords
import app.androidrik.ai.provider.providerTypeDisplayName
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ai.ModelAbilityTag
import app.androidrik.rikkahub.ui.components.ai.ModelModalityTag
import app.androidrik.rikkahub.ui.components.ai.ModelTypeTag
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.utils.DragOrderState
import app.androidrik.rikkahub.utils.plus
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
internal fun SettingProviderModelPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit
) {
    ModelList(
        providerSetting = provider,
        onUpdateProvider = onEdit
    )
}

@Composable
internal fun ModelList(
    providerSetting: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit
) {
    val catalogRepository = koinInject<ProviderModelCatalogRepository>()
    val catalogState = rememberCatalogState(providerSetting.id)
    val scope = rememberCoroutineScope()
    CatalogAutoRefreshEffect(listOf(providerSetting))
    val candidateModels = remember(catalogState?.lastComplete, catalogState?.lastAttempt?.partial) { catalogCandidateModels(catalogState) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    // 拖动只改本地顺序、松手才落盘：onUpdateProvider 最终走 DataStore 异步回流，
    // 拖到边缘自动滚动时每帧的移动会被旧顺序改回去（抽搐，同 ModelList dc2a43003）
    val modelDragOrder = remember { DragOrderState<Model>() }
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        modelDragOrder.onMove(providerSetting.models) { list ->
            // 与 ProviderSetting.moveMove 同构的列表移动
            list.reorderForDragByKey(from.key, to.key) { it.id }
        }
    }
    LaunchedEffect(providerSetting.models) { modelDragOrder.syncFromSource(providerSetting.models) }

    // ---- 模型多选批处理：批量删除 / 一键清空（与文件管理同形）----
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    val selectedModels = providerSetting.models.filter { it.id.toString() in selectedIds }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            item("catalog-status") { CatalogProviderStatus(providerSetting) }
            if (providerSetting.models.isEmpty()) {
                item("empty-models") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.setting_provider_page_no_models))
                        Text(stringResource(R.string.setting_provider_page_add_models_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(modelDragOrder.pendingOrder ?: providerSetting.models, key = { it.id }) { item ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = item.id
                    ) { isDragging ->
                        val haptic = LocalHapticFeedback.current
                        ModelCard(
                            model = item,
                            onDelete = {
                                onUpdateProvider(providerSetting.delModel(item))
                            },
                            onEdit = { editedModel ->
                                onUpdateProvider(providerSetting.editModel(editedModel))
                            },
                            onToggleEnabled = { enabled ->
                                onUpdateProvider(providerSetting.editModel(item.copy(enabled = enabled)))
                            },
                            selecting = selecting,
                            selected = item.id.toString() in selectedIds,
                            onToggleSelect = {
                                val key = item.id.toString()
                                selectedIds = if (key in selectedIds) selectedIds - key else selectedIds + key
                            },
                            parentProvider = providerSetting,
                            modifier = Modifier
                                // 排序管线（ReorderableItem + onMove）一直都在，但此前没接任何拖拽
                                // 触发器，长按无反应。卡片无其他长按用途，直接整卡长按拖动
                                .longPressDraggableHandle(
                                    enabled = !selecting,
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        modelDragOrder.onDragStopped { order ->
                                            // ProviderSetting 是抽象类没有 copy，走 copyProvider 换 models
                                            onUpdateProvider(providerSetting.copyProvider(models = order))
                                        }
                                    }
                                )
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    } else {
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    // 多选态：删除所选（左）→ 清空（右）→ 退出；全选已由「清空」承担，不再单独提供
                    FilledIconButton(
                        onClick = {
                            if (selectedModels.isNotEmpty()) showDeleteSelectedConfirm = true
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.common_delete),
                        )
                    }
                    FilledIconButton(
                        onClick = { showClearAllConfirm = true },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_provider_detail_page_clear_models)
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            selecting = false
                            selectedIds = emptyList()
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.workspace_selection_cancel)
                        )
                    }
                    if (selectedModels.isNotEmpty()) {
                        FilledIconButton(onClick = {}, enabled = false) {
                            Text(
                                text = selectedModels.size.toString(),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                } else {
                    FilledIconButton(onClick = { scope.launch { catalogRepository.refresh(providerSetting.id) } }, enabled = !catalogState.isRefreshing) {
                        if (catalogState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = stringResource(R.string.setting_provider_page_fetch_models)
                            )
                        }
                    }
                    FilledIconButton(
                        onClick = {
                            selecting = true
                            selectedIds = emptyList()
                        },
                        enabled = providerSetting.models.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.CursorPointer01,
                            contentDescription = stringResource(R.string.model_list_select_model)
                        )
                    }
                    AddModelButton(
                        models = candidateModels,
                        selectedModels = providerSetting.models,
                        onAddModel = {
                            onUpdateProvider(providerSetting.addModel(it))
                        },
                        onRemoveModel = {
                            onUpdateProvider(providerSetting.delModel(it))
                        },
                        expanded = expanded,
                        parentProvider = providerSetting,
                        onUpdateProvider = onUpdateProvider
                    )
                }
            }
        }
    }
    // ---- 批量删除 / 一键清空的确认与执行（单一更新入口，避免两次 onUpdate 竞态）----
    if (showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text(stringResource(R.string.setting_provider_detail_page_delete_models_title, selectedModels.size)) },
            text = { Text(stringResource(R.string.setting_provider_detail_page_delete_models_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSelectedConfirm = false
                    val removed = selectedModels.map { it.id }.toSet()
                    onUpdateProvider(providerSetting.copyProvider(models = providerSetting.models.filter { it.id !in removed }))
                    selecting = false
                    selectedIds = emptyList()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.setting_provider_detail_page_clear_models_title, providerSetting.models.size)) },
            text = { Text(stringResource(R.string.setting_provider_detail_page_clear_models_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    onUpdateProvider(providerSetting.copyProvider(models = emptyList()))
                    selecting = false
                    selectedIds = emptyList()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun AddModelButton(
    models: List<Model>,
    selectedModels: List<Model>,
    expanded: Boolean,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    parentProvider: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit
) {
    val dialogState = useEditState<Model> { onAddModel(it.copy(displayName = it.displayName.trim())) }
    val scope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            provider = parentProvider,
            models = models,
            selectedModels = selectedModels,
            onModelSelected = { model ->
                val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                onAddModel(
                    model.copy(
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                        abilities = abilities,
                        // 上下文长度来自 models.dev / 注册表内置数据（查不到则保留拉取时解析的值）；
                        // 用户仍可用 customContextLength 覆盖
                        contextLength = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId)
                            ?: model.contextLength,
                    )
                )
            },
            onModelDeselected = { model ->
                onRemoveModel(model)
            },
            onAllModelSelected = {
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models = parentProvider.models + it.filter { model ->
                            parentProvider.models.none { existing -> existing.modelId == model.modelId }
                        }.map { model ->
                            model.copy(
                                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
                                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
                                abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
                                // 上下文长度来自 models.dev / 注册表内置数据（查不到则保留拉取时解析的值）；
                                // 用户仍可用 customContextLength 覆盖
                                contextLength = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId)
                                    ?: model.contextLength,
                            )
                        }
                    )
                )
            },
            onAllModelDeselected = { filteredModels ->
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models = parentProvider.models.filter { model ->
                            filteredModels.none { filtered -> filtered.modelId == model.modelId }
                        }
                    )
                )
            }
        )

        Button(
            onClick = {
                dialogState.open(Model())
            }
        ) {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_provider_page_add_model)
                )
                AnimatedVisibility(expanded) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.setting_provider_page_add_new_model),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                dialogState.dismiss()
                            }
                        }
                    ) {
                        Icon(HugeIcons.ArrowDown01, null)
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .imePadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_model),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = false,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelPicker(
    provider: ProviderSetting,
    models: List<Model>,
    selectedModels: List<Model>,
    onModelSelected: (Model) -> Unit,
    onModelDeselected: (Model) -> Unit,
    onAllModelSelected: (List<Model>) -> Unit,
    onAllModelDeselected: (List<Model>) -> Unit
) {
    var showModal by remember { mutableStateOf(false) }
    if (showModal) {
        CatalogAutoRefreshEffect(listOf(provider))
        ModalBottomSheet(
            onDismissRequest = { showModal = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            var filterText by remember { mutableStateOf("") }
            val filteredModels = models.filterByKeywords(filterText)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(8.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CatalogProviderStatus(provider)
                // 标题栏和添加所有按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_avaliable_models),
                        style = MaterialTheme.typography.titleMedium
                    )

                    val unselectedCount = filteredModels.count { model ->
                        selectedModels.none { it.modelId == model.modelId }
                    }

                    TextButton(
                        onClick = {
                            if (unselectedCount > 0) {
                                onAllModelSelected(filteredModels)
                            } else {
                                onAllModelDeselected(filteredModels)
                            }
                        },
                    ) {
                        Text(
                            if (unselectedCount > 0) stringResource(
                                R.string.setting_provider_page_select_all,
                                unselectedCount
                            ) else stringResource(R.string.setting_provider_page_deselect_models)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(filteredModels) {
                        Card {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            ) {
                                AutoAIIcon(
                                    it.modelId,
                                    Modifier.size(32.dp)
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(
                                        4.dp
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = it.modelId,
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        val modelMeta = remember(it) {
                                            it.copy(
                                                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(it.modelId),
                                                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(it.modelId),
                                                abilities = ModelRegistry.MODEL_ABILITIES.getData(it.modelId),
                                            )
                                        }
                                        ModelModalityTag(
                                            model = modelMeta,
                                        )
                                        ModelAbilityTag(
                                            model = modelMeta,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                            // 从selectedModels中计算出要删除的model，因为删除需要id匹配，而不是ModelId
                                            onModelDeselected(selectedModels.firstOrNull { model -> model.modelId == it.modelId }
                                                ?: it)
                                        } else {
                                            onModelSelected(it)
                                        }
                                    }
                                ) {
                                    if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                        Icon(HugeIcons.Cancel01, null)
                                    } else {
                                        Icon(HugeIcons.Add01, null)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = {
                        filterText = it
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.setting_provider_page_filter_example))
                    },
                )
            }
        }
    }
    BadgedBox(
        badge = {
            if (models.isNotEmpty()) {
                Badge {
                    Text(models.size.toString())
                }
            }
        }
    ) {
        IconButton(
            onClick = {
                showModal = true
            }
        ) {
            Icon(HugeIcons.Package01, null)
        }
    }
}

@Composable
internal fun ModelCard(
    model: Model,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (Model) -> Unit,
    /** 停用/启用该模型（只影响它是否出现在模型选择器里，见 [Model.enabled]） */
    onToggleEnabled: (Boolean) -> Unit,
    /** 多选态由宿主持有；非空 onToggleSelect 时卡片进入选择语义 */
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    parentProvider: ProviderSetting
) {
    val dialogState = useEditState<Model> {
        onEdit(it.copy(displayName = it.displayName.trim()))
    }
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val enabledLabel = stringResource(
        if (model.enabled) R.string.setting_provider_page_enabled
        else R.string.setting_provider_page_disabled
    )

    if (dialogState.isEditing) {
        dialogState.currentState?.let { editingModel ->
            val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .imePadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    dialogState.dismiss()
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                        Text(
                            text = stringResource(R.string.setting_provider_page_edit_model),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = editingModel,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = true,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (editingModel.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }

    SwipeToDismissBox(
        state = swipeToDismissBoxState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            swipeToDismissBoxState.reset()
                        }
                    }
                ) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(
                    onClick = {
                        scope.launch {
                            onDelete()
                            swipeToDismissBoxState.reset()
                        }
                    }
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.chat_page_delete)
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        // 多选态下滑动删除退场（点击=切换选中），避免误扫
        gesturesEnabled = !selecting,
        modifier = modifier
    ) {
        OutlinedCard(
            colors = if (selected) {
                CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.outlinedCardColors()
            },
            // 多选态：点击卡片切换选中；平常态点击无动作（开关/编辑按钮照旧）
            onClick = { if (selecting) onToggleSelect?.invoke() },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CatalogModelMarker(parentProvider, model)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (model.providerOverwrite != null) {
                            Tag(type = TagType.INFO) {
                                Text(
                                    // 显示 API 格式名而非混淆类名（同上：release 下 simpleName = it9/ft9）
                                    model.providerOverwrite?.providerTypeDisplayName
                                        ?: model.providerOverwrite?.name
                                        ?: "ProviderOverwrite"
                                )
                            }
                        }
                        ModelTypeTag(model = model)
                        ModelModalityTag(model = model)
                        ModelAbilityTag(model = model)
                        if (!model.enabled) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.setting_provider_page_disabled))
                            }
                        }
                    }
                }

                // 启停开关：与「提供商启停」「预设条目启停」同款交互（行内 Switch）。
                // 语义=仅从选择器收起，不改既有选中项（见 Model.enabled 的说明）。
                // 多选态下隐藏操作件，行内勾选框替代
                if (selecting) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect?.invoke() },
                    )
                } else {
                    Switch(
                        checked = model.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "${model.displayName}: $enabledLabel"
                        },
                    )

                    // Edit button
                    IconButton(
                        onClick = {
                            dialogState.open(model.copy())
                        }
                    ) {
                        Icon(HugeIcons.Tools, "Edit")
                    }
                }
            }
        }
    }
}
