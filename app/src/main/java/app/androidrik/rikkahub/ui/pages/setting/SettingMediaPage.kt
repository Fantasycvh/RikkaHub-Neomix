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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.ui.pages.setting.components.toMediaGenerationProvider
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.common.android.sanitizeSensitiveText
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.RemoteFileStore
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.ui.components.ai.ExtensionEmptyState
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.MediaGenerationProviderConfigure
import app.androidrik.rikkahub.ui.pages.setting.components.label
import app.androidrik.rikkahub.ui.pages.setting.components.typeName
import app.androidrik.rikkahub.ui.theme.CustomColors
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 媒体生成设置。
 *
 * 三个页签：服务商配置、素材存储，以及语音设置。部分视频接口只接受公网素材地址，配置独立于备份的
 * S3 兼容桶后，本地素材会先上传再以临时地址提交；未配置时相关生成会给出本地化明确错误。
 */
@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var newProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showChatImport by remember { mutableStateOf(false) }
    val importableProviders = settings.providers.filterIsInstance<ProviderSetting.OpenAI>()
    // 0 = 服务商，1 = 素材存储，2 = 语音
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabStateHolder = rememberSaveableStateHolder()
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
                title = { Text(stringResource(R.string.setting_page_media_and_speech)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (selectedTab == 0) {
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
                                DropdownMenuItem(
                                    text = { Text("从聊天服务商导入") },
                                    onClick = {
                                        showAddMenu = false
                                        showChatImport = true
                                    },
                                )
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
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        // 内部三个页签放底部：与「设置 → 模型」一致（那边也是 BottomAppBar + NavigationBarItem），
        // 长列表里切换不用回到顶部；页签本身不滚动，始终可点。
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                    label = {
                        Text(
                            stringResource(R.string.setting_media_page_providers),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(HugeIcons.Folder01, contentDescription = null) },
                    label = {
                        Text(
                            stringResource(R.string.setting_media_page_asset_storage),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(HugeIcons.Mic01, contentDescription = null) },
                    label = {
                        Text(
                            stringResource(R.string.speech_page_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            tabStateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> MediaProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingProvider = it },
                        modifier = Modifier.fillMaxSize(),
                    )

                    1 -> MediaUploadStorageTab(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        modifier = Modifier.fillMaxSize(),
                    )

                    2 -> SettingSpeechPage(vm = vm, embedded = true)
                }
            }
        }
    }

    if (showChatImport) {
        AlertDialog(
            onDismissRequest = { showChatImport = false },
            title = { Text("从聊天服务商导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "仅支持 OpenAI 兼容服务商，复制名称、API 地址和第一个可用密钥。" +
                            "不导入聊天模型、自定义请求头或独立代理；导入后请添加生图模型并确认接口兼容性。" +
                            "保存为独立配置，不影响原聊天服务商。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (importableProviders.isEmpty()) {
                        Text("暂无可导入的 OpenAI 兼容聊天服务商")
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(importableProviders, key = { it.id }) { source ->
                            TextButton(
                                onClick = {
                                    newProvider = source.toMediaGenerationProvider()
                                    showChatImport = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChatImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
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

/**
 * 素材上传存储（独立于备份用的 S3 桶）。
 *
 * 部分视频接口只接受公网素材地址：配置这里的 S3 兼容桶后，本地素材会先上传并换成临时地址再提交；
 * 未配置时相关生成会给出本地化明确错误，不做静默降级。上传对象 7 天后自动清理。
 */
@Composable
private fun MediaUploadStorageTab(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    remoteFileStore: RemoteFileStore = koinInject(),
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    // 输入框以本地状态为准：设置要经过存储再回流，直接绑定的话打字快了会被旧值覆盖
    var config by remember { mutableStateOf(settings.uploadS3Config) }

    val connectionSuccess = stringResource(R.string.backup_page_connection_success)
    // 失败原因由测试结果动态给出；模板在组合期取好，回调里再格式化，避免在回调里读 LocalContext 取资源
    val connectionFailedTemplate = stringResource(R.string.backup_page_connection_failed, "%s")
    // 本轮安全策略：素材上传只允许 HTTPS（显式 http 会被 RemoteFileStore 拒绝），在输入处就给出明确提示
    val usesPlainHttpEndpoint = config.endpoint.trim().startsWith("http://", ignoreCase = true)

    fun updateConfig(newConfig: S3Config) {
        config = newConfig
        onUpdateSettings(settings.copy(uploadS3Config = newConfig))
    }

    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_media_page_upload_storage_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = stringResource(R.string.setting_media_page_upload_https_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_s3_endpoint)) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = config.endpoint,
                            onValueChange = { updateConfig(config.copy(endpoint = it.trim())) },
                            placeholder = { Text("https://s3.amazonaws.com") },
                            singleLine = true,
                            isError = usesPlainHttpEndpoint,
                        )
                        if (usesPlainHttpEndpoint) {
                            Text(
                                text = stringResource(R.string.setting_media_page_upload_http_rejected),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_s3_access_key_id)) },
                supportingContent = {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.accessKeyId,
                        onValueChange = { updateConfig(config.copy(accessKeyId = it.trim())) },
                        singleLine = true,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_s3_secret_access_key)) },
                supportingContent = {
                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.secretAccessKey,
                        onValueChange = { updateConfig(config.copy(secretAccessKey = it.trim())) },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                    contentDescription = null,
                                )
                            }
                        },
                        singleLine = true,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_s3_bucket)) },
                supportingContent = {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.bucket,
                        onValueChange = { updateConfig(config.copy(bucket = it.trim())) },
                        placeholder = { Text("my-bucket") },
                        singleLine = true,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_s3_path_style)) },
                supportingContent = { Text(stringResource(R.string.backup_page_s3_path_style_desc)) },
                trailingContent = {
                    Switch(
                        checked = config.pathStyle,
                        onCheckedChange = { updateConfig(config.copy(pathStyle = it)) },
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_s3_region)) },
                supportingContent = {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.region,
                        onValueChange = { updateConfig(config.copy(region = it.trim())) },
                        placeholder = { Text("auto") },
                        singleLine = true,
                    )
                },
            )
        }

        CardGroup {
            item(
                onClick = if (!testing) {
                    {
                        scope.launch {
                            testing = true
                            try {
                                remoteFileStore.testConnection()
                                    .onSuccess {
                                        toaster.show(connectionSuccess, type = ToastType.Success)
                                    }
                                    .onFailure {
                                        toaster.show(
                                            // 连接错误里可能带签名 URL 片段，展示前先抹掉
                                            connectionFailedTemplate.format(
                                                sanitizeSensitiveText(it.message ?: "")
                                            ),
                                            type = ToastType.Error,
                                        )
                                    }
                            } finally {
                                testing = false
                            }
                        }
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_test_connection)) },
                supportingContent = { Text(stringResource(R.string.setting_media_page_upload_test_hint)) },
                leadingContent = {
                    if (testing) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        Icon(HugeIcons.Connect, contentDescription = null)
                    }
                },
            )
        }
    }
}
