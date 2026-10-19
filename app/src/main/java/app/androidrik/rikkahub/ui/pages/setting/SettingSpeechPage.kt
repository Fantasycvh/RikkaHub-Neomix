package app.androidrik.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.VolumeHigh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.asr.withEnabled
import app.androidrik.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import app.androidrik.rikkahub.data.datastore.ensureFallbackTtsEnabled
import app.androidrik.rikkahub.data.datastore.isBuiltInTtsProvider
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.context.LocalTTSState
import app.androidrik.rikkahub.ui.pages.setting.components.ASRProviderConfigure
import app.androidrik.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.utils.reorderForDrag
import app.androidrik.tts.provider.TTSProviderSetting
import app.androidrik.tts.provider.withEnabled
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@Composable
fun SettingSpeechPage(vm: SettingVM = koinViewModel(), embedded: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingTTSProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var editingASRProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val addProvider: @Composable () -> Unit = {
        if (selectedPage == 0) {
            AddTTSProviderButton {
                vm.updateSettings(settings.copy(ttsProviders = listOf(it) + settings.ttsProviders))
            }
        } else {
            AddASRProviderButton {
                vm.updateSettings(
                    settings.copy(
                        asrProviders = listOf(it) + settings.asrProviders,
                        selectedASRProviderId = settings.selectedASRProviderId ?: it.id,
                    )
                )
            }
        }
    }
    val speechContent: @Composable (Modifier) -> Unit = { modifier ->
        when (selectedPage) {
            0 -> Column(modifier = modifier.fillMaxSize()) {
                TTSPlaybackSpeedSetting(
                    speed = settings.defaultTTSPlaybackSpeed,
                    onSpeedChange = {
                        vm.updateSettings(settings.copy(defaultTTSPlaybackSpeed = it))
                    },
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                TTSProviderList(
                    settings = settings,
                    onUpdateSettings = vm::updateSettings,
                    onEdit = { editingTTSProvider = it },
                    modifier = Modifier.weight(1f),
                )
            }

            1 -> ASRProviderList(
                settings = settings,
                onUpdateSettings = vm::updateSettings,
                onEdit = { editingASRProvider = it },
                modifier = modifier,
            )
        }
    }

    // Embedded speech owns only its TTS/ASR tabs and actions; the media page owns page chrome.
    if (embedded) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryTabRow(
                    selectedTabIndex = selectedPage,
                    modifier = Modifier.weight(1f),
                    containerColor = CustomColors.topBarColors.containerColor,
                ) {
                    Tab(
                        selected = selectedPage == 0,
                        onClick = { selectedPage = 0 },
                        text = { Text(stringResource(R.string.speech_tab_tts)) },
                    )
                    Tab(
                        selected = selectedPage == 1,
                        onClick = { selectedPage = 1 },
                        text = { Text(stringResource(R.string.speech_tab_asr)) },
                    )
                }
                addProvider()
            }
            speechContent(Modifier.weight(1f))
        }
    } else {
        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(text = stringResource(R.string.speech_page_title))
                    },
                    navigationIcon = {
                        BackButton()
                    },
                    actions = {
                        addProvider()
                    },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
                ) {
                    NavigationBarItem(
                        selected = selectedPage == 0,
                        onClick = { selectedPage = 0 },
                        icon = { Icon(HugeIcons.VolumeHigh, contentDescription = null) },
                        label = { Text(stringResource(R.string.speech_tab_tts)) }
                    )
                    NavigationBarItem(
                        selected = selectedPage == 1,
                        onClick = { selectedPage = 1 },
                        icon = { Icon(HugeIcons.Mic01, contentDescription = null) },
                        label = { Text(stringResource(R.string.speech_tab_asr)) }
                    )
                }
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = CustomColors.topBarColors.containerColor,
        ) { innerPadding ->
            speechContent(Modifier.padding(innerPadding))
        }
    }

    // Edit TTS Provider Bottom Sheet
    editingTTSProvider?.let { provider ->
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        var currentProvider by remember(provider) { mutableStateOf(provider) }

        ModalBottomSheet(
            onDismissRequest = {
                editingTTSProvider = null
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_edit_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                TTSProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            editingTTSProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            val newProviders = settings.ttsProviders.map {
                                if (it.id == provider.id) currentProvider else it
                            }
                            vm.updateSettings(settings.copy(ttsProviders = newProviders))
                            editingTTSProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }

    editingASRProvider?.let { provider ->
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        var currentProvider by remember(provider) { mutableStateOf(provider) }

        ModalBottomSheet(
            onDismissRequest = {
                editingASRProvider = null
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_asr_page_edit_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                ASRProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            editingASRProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            val newProviders = settings.asrProviders.map {
                                if (it.id == provider.id) currentProvider else it
                            }
                            vm.updateSettings(settings.copy(asrProviders = newProviders))
                            editingASRProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun TTSProviderList(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    onEdit: (TTSProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.ttsProviders.reorderForDrag(from.index, to.index)
        onUpdateSettings(settings.copy(ttsProviders = newProviders))
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        items(settings.ttsProviders, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                TTSProviderItem(
                    modifier = Modifier
                        .scale(if (isDragging) 0.95f else 1f)
                        .fillMaxWidth(),
                    provider = provider,
                    dragHandle = {
                        val haptic = LocalHapticFeedback.current
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                        ) {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null
                            )
                        }
                    },
                    isSelected = settings.selectedTTSProviderId == provider.id,
                    onSelect = {
                        onUpdateSettings(settings.copy(selectedTTSProviderId = provider.id))
                    },
                    // 停用是「临时别用它」而非删除配置。**系统 TTS 是兜底**：不允许停用到
                    // 一个启用的都不剩，故先应用不变式，被拉回时提示原因（见 ensureFallbackTtsEnabled）。
                    onToggleEnabled = { enabled ->
                        val toggled = settings.ttsProviders.map {
                            if (it.id == provider.id) it.withEnabled(enabled) else it
                        }
                        val normalized = toggled.ensureFallbackTtsEnabled()
                        if (!enabled && normalized != toggled) {
                            toaster.show(context.getString(R.string.setting_tts_page_fallback_kept))
                        }
                        // 停用当前选中项时自动切到第一个仍启用的（避免「选中项不可用」死状态；
                        // 与 SettingSearchPage.onToggleEnabled 同款）
                        val newSelectedId =
                            if (!enabled && settings.selectedTTSProviderId == provider.id) {
                                normalized.firstOrNull { it.enabled && it.id != provider.id }?.id
                                    ?: settings.selectedTTSProviderId
                            } else {
                                settings.selectedTTSProviderId
                            }
                        onUpdateSettings(
                            settings.copy(
                                ttsProviders = normalized,
                                selectedTTSProviderId = newSelectedId,
                            )
                        )
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        val newProviders = settings.ttsProviders - provider
                        val newSelectedId = if (settings.selectedTTSProviderId == provider.id) {
                            newProviders.firstOrNull { it.enabled }?.id ?: DEFAULT_SYSTEM_TTS_ID
                        } else {
                            settings.selectedTTSProviderId
                        }
                        onUpdateSettings(
                            settings.copy(
                                ttsProviders = newProviders.ensureFallbackTtsEnabled(),
                                selectedTTSProviderId = newSelectedId,
                                // 内置项删除记墓碑（否则读路径会把它原样补回来 = 删不掉）
                                removedTtsProviderIds = if (isBuiltInTtsProvider(provider.id)) {
                                    settings.removedTtsProviderIds + provider.id
                                } else {
                                    settings.removedTtsProviderIds
                                },
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TTSPlaybackSpeedSetting(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(speed) { mutableFloatStateOf(speed) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_default_playback_speed),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "x${"%.1f".format(sliderValue)}",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = { onSpeedChange(sliderValue) },
                valueRange = 0.5f..2.0f,
                steps = 14,
            )
            Text(
                text = stringResource(R.string.setting_tts_page_default_playback_speed_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ASRProviderList(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    onEdit: (ASRProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.asrProviders.reorderForDrag(from.index, to.index)
        onUpdateSettings(settings.copy(asrProviders = newProviders))
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        items(settings.asrProviders, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                ASRProviderItem(
                    modifier = Modifier
                        .scale(if (isDragging) 0.95f else 1f)
                        .fillMaxWidth(),
                    provider = provider,
                    dragHandle = {
                        val haptic = LocalHapticFeedback.current
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                        ) {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null
                            )
                        }
                    },
                    isSelected = settings.selectedASRProviderId == provider.id,
                    onSelect = {
                        onUpdateSettings(settings.copy(selectedASRProviderId = provider.id))
                    },
                    // 停用是「临时别用它」而非删除配置：API key 与参数都留着，随时可再启用。
                    // 语义对齐搜索服务（SettingSearchPage 的 onToggleEnabled）：
                    // 停用**当前选中项**时自动切到第一个仍启用的提供商，避免「选中项不可用」而死；
                    // 若全部停用则保留原 id（此时语音侧会如实提示「请配置 ASR」）。
                    onToggleEnabled = { enabled ->
                        val newProviders = settings.asrProviders.map {
                            if (it.id == provider.id) it.withEnabled(enabled) else it
                        }
                        val newSelectedId = if (!enabled && settings.selectedASRProviderId == provider.id) {
                            newProviders.firstOrNull { it.enabled }?.id ?: settings.selectedASRProviderId
                        } else {
                            settings.selectedASRProviderId
                        }
                        onUpdateSettings(
                            settings.copy(
                                asrProviders = newProviders,
                                selectedASRProviderId = newSelectedId,
                            )
                        )
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        val newProviders = settings.asrProviders - provider
                        val newSelectedId =
                            if (settings.selectedASRProviderId == provider.id) {
                                newProviders.firstOrNull()?.id
                            } else {
                                settings.selectedASRProviderId
                            }
                        onUpdateSettings(
                            settings.copy(
                                asrProviders = newProviders,
                                selectedASRProviderId = newSelectedId
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AddTTSProviderButton(onAdd: (TTSProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var currentProvider: TTSProviderSetting by remember { mutableStateOf(TTSProviderSetting.SystemTTS()) }

    IconButton(
        onClick = {
            currentProvider = TTSProviderSetting.SystemTTS()
            showBottomSheet = true
        }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_tts_page_add_provider_content_description))
    }

    if (showBottomSheet) {
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                TTSProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            onAdd(currentProvider)
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddASRProviderButton(onAdd: (ASRProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var currentProvider: ASRProviderSetting by remember { mutableStateOf(ASRProviderSetting.OpenAIRealtime()) }

    Box {
        IconButton(
            onClick = { showTypeMenu = true }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.setting_asr_page_add_provider))
        }
        DropdownMenu(
            expanded = showTypeMenu,
            onDismissRequest = { showTypeMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("OpenAI Realtime") },
                onClick = {
                    currentProvider = ASRProviderSetting.OpenAIRealtime()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("DashScope") },
                onClick = {
                    currentProvider = ASRProviderSetting.DashScope()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("Volcengine") },
                onClick = {
                    currentProvider = ASRProviderSetting.Volcengine()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("MiMo") },
                onClick = {
                    currentProvider = ASRProviderSetting.MiMo()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("Step") },
                onClick = {
                    currentProvider = ASRProviderSetting.Step()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
        }
    }

    if (showBottomSheet) {
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_asr_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                ASRProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            onAdd(currentProvider)
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun TTSProviderItem(
    provider: TTSProviderSetting,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    dragHandle: @Composable () -> Unit,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDropdownMenu by remember { mutableStateOf(false) }
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsState()
    val isAvailable by tts.isAvailable.collectAsState()

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                CustomColors.listItemColors.containerColor
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                    modifier = Modifier.size(32.dp)
                )

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    Text(
                        text = when (provider) {
                            is TTSProviderSetting.OpenAI -> stringResource(R.string.setting_tts_page_provider_openai)
                            is TTSProviderSetting.Gemini -> stringResource(R.string.setting_tts_page_provider_gemini)
                            is TTSProviderSetting.MiniMax -> "MiniMax"
                            is TTSProviderSetting.SystemTTS -> stringResource(R.string.setting_tts_page_provider_system)
                            is TTSProviderSetting.Qwen -> "Qwen"
                            is TTSProviderSetting.Groq -> "Groq"
                            is TTSProviderSetting.XAI -> "xAI"
                            is TTSProviderSetting.MiMo -> "MiMo"
                            is TTSProviderSetting.Step -> "Step"
                            is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                            is TTSProviderSetting.FishAudio -> "Fish Audio"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = onSelect
                )

                dragHandle()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态标签
                if (isSelected) {
                    Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.setting_tts_page_selected))
                    }
                }

                if (!provider.enabled) {
                    Tag(type = TagType.DEFAULT) {
                        Text(stringResource(R.string.setting_tts_page_disabled_badge))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onToggleEnabled,
                )

                // TTS测试播放按钮
                if (isSelected && isAvailable) {
                    val testText = stringResource(R.string.setting_tts_page_test_text)
                    IconButton(
                        onClick = {
                            if (!isSpeaking) {
                                tts.speak(testText)
                            } else {
                                tts.stop()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                            contentDescription = if (isSpeaking) stringResource(R.string.stop) else stringResource(R.string.test_tts),
                            tint = if (isSpeaking) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }
                }

                IconButton(
                    onClick = { showDropdownMenu = true }
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = stringResource(R.string.setting_tts_page_more_options_content_description)
                    )
                    DropdownMenu(
                        expanded = showDropdownMenu,
                        onDismissRequest = { showDropdownMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                showDropdownMenu = false
                                onEdit()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.PencilEdit01, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                showDropdownMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            },
                            enabled = provider.id != DEFAULT_SYSTEM_TTS_ID
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ASRProviderItem(
    provider: ASRProviderSetting,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    dragHandle: @Composable () -> Unit,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDropdownMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                CustomColors.listItemColors.containerColor
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
                    modifier = Modifier.size(32.dp)
                )

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    Text(
                        text = when (provider) {
                            is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
                            is ASRProviderSetting.DashScope -> "DashScope"
                            is ASRProviderSetting.Volcengine -> "Volcengine"
                            is ASRProviderSetting.MiMo -> "MiMo"
                            is ASRProviderSetting.Step -> "Step"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = onSelect
                )

                dragHandle()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.setting_tts_page_selected))
                    }
                }

                if (!provider.enabled) {
                    Tag(type = TagType.DEFAULT) {
                        Text(stringResource(R.string.setting_asr_page_disabled_badge))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onToggleEnabled,
                )

                IconButton(
                    onClick = { showDropdownMenu = true }
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = stringResource(R.string.setting_tts_page_more_options_content_description)
                    )
                    DropdownMenu(
                        expanded = showDropdownMenu,
                        onDismissRequest = { showDropdownMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                showDropdownMenu = false
                                onEdit()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.PencilEdit01, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                showDropdownMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}
