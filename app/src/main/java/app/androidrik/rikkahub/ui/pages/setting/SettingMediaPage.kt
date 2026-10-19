package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.components.ai.ExtensionEmptyState
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.pages.setting.components.MediaGenerationProviderConfigure
import app.androidrik.rikkahub.ui.pages.setting.components.label
import app.androidrik.rikkahub.ui.pages.setting.components.typeName
import app.androidrik.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 媒体生成服务商设置。
 *
 * 上游页面还有第二个「素材上传」页签，依赖 `RemoteFileStore` / `uploadS3Config`；本仓库没有这条上传链路，
 * 因此这里只保留服务商配置页，S3 页签与相关控件一并省略（服务层遇到需要公网素材的模型会明确报错）。
 */
@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var newProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 新建时先选类型，再进配置面板。
    // 只列出已经接入适配器的类型（OpenAI 兼容适配器与硅基流动）；其余类型的设置类保留是为了
    // 设置 JSON/旧备份能反序列化，但不放进"新建"菜单，避免建完只能拿到运行期 Provider not ported yet。
    val providerFactories = listOf(
        "OpenAI" to { MediaGenerationProviderSetting.OpenAI() },
        "SiliconFlow" to { MediaGenerationProviderSetting.SiliconFlow() },
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_media)) },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.setting_media_page_add_provider),
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            providerFactories.forEach { (label, factory) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showAddMenu = false
                                        newProvider = factory()
                                    },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        MediaProviderList(
            settings = settings,
            onUpdateSettings = vm::updateSettings,
            onEdit = { editingProvider = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }

    editingProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings(
                    settings.copy(
                        mediaGenerationProviders = settings.mediaGenerationProviders.map {
                            if (it.id == provider.id) edited else it
                        }
                    )
                )
            },
            onDismiss = { editingProvider = null },
        )
    }

    newProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = { added ->
                vm.updateSettings(
                    settings.copy(
                        mediaGenerationProviders = listOf(added) + settings.mediaGenerationProviders
                    )
                )
            },
            onDismiss = { newProvider = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaProviderList(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    onEdit: (MediaGenerationProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.mediaGenerationProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings(settings.copy(mediaGenerationProviders = newProviders))
    }
    var deleteTarget by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }

    if (settings.mediaGenerationProviders.isEmpty()) {
        ExtensionEmptyState(
            message = stringResource(R.string.setting_media_page_empty),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState,
        ) {
            items(settings.mediaGenerationProviders, key = { it.id }) { provider ->
                ReorderableItem(state = reorderableState, key = provider.id) { isDragging ->
                    val haptic = LocalHapticFeedback.current
                    MediaProviderItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(if (isDragging) 0.95f else 1f),
                        provider = provider,
                        onEdit = { onEdit(provider) },
                        onDelete = { deleteTarget = provider },
                        dragHandle = {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                ),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.DragDropHorizontal,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                onUpdateSettings(
                    settings.copy(
                        mediaGenerationProviders = settings.mediaGenerationProviders.filter { it.id != target.id }
                    )
                )
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.setting_media_page_delete_provider, deleteTarget?.name.orEmpty()))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaProviderItem(
    provider: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val name = provider.name.ifEmpty { provider.typeName }

    Card(
        onClick = onEdit,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(name = name, modifier = Modifier.size(40.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider.typeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )

                val modelCounts = MediaKind.entries
                    .map { kind -> kind to provider.models.count { it.kind == kind } }
                    .filter { it.second > 0 }
                if (modelCounts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        modelCounts.forEach { (kind, count) ->
                            Tag(type = TagType.INFO) {
                                Text("${kind.label} $count")
                            }
                        }
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
            }

            dragHandle()
        }
    }
}

@Composable
private fun MediaProviderSheet(
    title: String,
    initial: MediaGenerationProviderSetting,
    confirmText: String,
    onConfirm: (MediaGenerationProviderSetting) -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var currentProvider by remember(initial) { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .fillMaxHeight(0.8f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)

            MediaGenerationProviderConfigure(
                setting = currentProvider,
                onValueChange = { currentProvider = it },
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        // 没填模型 ID 的行不保存
                        onConfirm(
                            currentProvider.copyProvider(
                                models = currentProvider.models.filter { it.modelId.isNotBlank() }
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}
