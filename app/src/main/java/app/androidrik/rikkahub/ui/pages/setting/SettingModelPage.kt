package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.components.ai.ModelListSheet
import app.androidrik.rikkahub.ui.components.ai.rememberModelListState
import app.androidrik.rikkahub.data.ai.compression.AUTO_COMPRESS_RETAIN_PERCENT_RANGE
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS_RANGE
import app.androidrik.rikkahub.data.ai.compression.clamped
import app.androidrik.rikkahub.ui.components.ai.CompressPresetManageDialog
import app.androidrik.rikkahub.ui.components.ai.CompressPresetSelector
import app.androidrik.rikkahub.ui.components.ai.builtInCompressPresetNames
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.utils.formatContextLength
import app.androidrik.rikkahub.utils.parseContextLengthInput
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        // imePadding：键盘弹出时抬高列表底部，避免最后一个输入项（默认上下文窗口）被 IME 遮挡
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            // 全局默认备用模型：助手未配置 fallback 时生效（优先级 助手 > 全局）
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fallback_model),
                description = stringResource(R.string.setting_model_page_fallback_model_desc),
                modelId = settings.fallbackChatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fallbackChatModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(fallbackChatModelId = null)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(fastModelId = null)) },
            )
        }
        item {
            ModelSettingItem(
                title = "做梦（记忆整理）模型",
                description = "用于自动和手动记忆整理。未配置时依次使用快速模型、助手聊天模型；建议选择支持工具调用的模型。",
                modelId = settings.memoryConsolidationModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(memoryConsolidationModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(memoryConsolidationModelId = null)) },
                emptyText = "跟随快速模型或助手聊天模型",
            )
        }
        item {
            // 自动标题开关 + 标题模型：与下面「建议模型」同形（关掉时隐藏模型选择器）。
            // 开关只关**自动**：会话列表/抽屉里的「重新生成标题」不受影响（force = true）。
            Column {
                CardGroup(title = { Text(stringResource(R.string.setting_model_page_title_model)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_model_page_enable_auto_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_model_page_enable_auto_title_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.enableAutoTitle,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(enableAutoTitle = it))
                                },
                            )
                        },
                    )
                    if (settings.enableAutoTitle) {
                        item {
                            ModelSettingItem(
                                title = stringResource(R.string.setting_model_page_title_model),
                                description = stringResource(R.string.setting_model_page_title_model_desc),
                                modelId = settings.titleModelId,
                                providers = settings.providers,
                                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                                onClear = { vm.updateSettings(settings.copy(titleModelId = null)) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SuggestionModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = if (settings.compressModelId == null) {
                    // 已选「跟随当前对话模型」
                    stringResource(R.string.setting_model_page_compress_model_follow)
                } else {
                    // 预设为固定模型；提示清除后可改为跟随对话
                    stringResource(R.string.setting_model_page_compress_model_desc) + "\n" +
                        stringResource(R.string.setting_model_page_compress_model_clear_hint)
                },
                modelId = settings.compressModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(compressModelId = null)) },
                emptyText = stringResource(R.string.setting_model_page_compress_model_follow),
            )
        }
        item {
            ContextLengthOverrideSetting(
                settings = settings,
                vm = vm,
            )
        }
        item {
            AutoCompressSetting(
                settings = settings,
                vm = vm,
            )
        }
    }
}

/**
 * 自动压缩：占用达到阈值时自动把早期历史摘要为检查点（design-auto-compaction Phase 2）。
 * 默认关闭——保持发送行为可预期；开启后阈值/保留比/目标 token 可调。
 */
@Composable
private fun AutoCompressSetting(
    settings: Settings,
    vm: SettingVM,
) {
    val thresholdOptions = listOf(60, 70, 80, 90)
    val builtInNames = builtInCompressPresetNames()
    var showPresetManager by remember { mutableStateOf(false) }

    if (showPresetManager) {
        CompressPresetManageDialog(
            customPresets = settings.compressPresets,
            builtInNames = builtInNames,
            onDismiss = { showPresetManager = false },
            onSavePreset = { index, preset ->
                val clamped = preset.clamped()
                val updated = if (index == null) {
                    settings.compressPresets + clamped
                } else {
                    settings.compressPresets.toMutableList().also { it[index] = clamped }
                }
                vm.updateSettings(settings.copy(compressPresets = updated))
            },
            onDeletePreset = { index ->
                // 删除是纯 UI 操作：真相源是数值，删档不改变当前压缩行为
                vm.updateSettings(
                    settings.copy(
                        compressPresets = settings.compressPresets.filterIndexed { i, _ -> i != index },
                    )
                )
            },
        )
    }

    CardGroup(title = { Text(stringResource(R.string.setting_model_page_auto_compress)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_auto_compress_enable)) },
            supportingContent = { Text(stringResource(R.string.setting_model_page_auto_compress_enable_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.autoCompressEnabled,
                    onCheckedChange = { vm.updateSettings(settings.copy(autoCompressEnabled = it)) },
                )
            },
        )
        if (settings.autoCompressEnabled) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 触发阈值：**只属自动压缩**（手动由用户自己判断何时压，无此参数）
                    Text(
                        text = stringResource(R.string.setting_model_page_auto_compress_threshold),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        thresholdOptions.forEachIndexed { index, percent ->
                            SegmentedButton(
                                selected = settings.autoCompressThresholdPercent == percent,
                                onClick = {
                                    vm.updateSettings(settings.copy(autoCompressThresholdPercent = percent))
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, thresholdOptions.size),
                            ) {
                                Text("$percent%")
                            }
                        }
                    }

                    // 保留比例 + 摘要目标：由「压缩配置档」一键填入，也可自填（= 自定义）
                    CompressPresetSelector(
                        selectedRetainPercent = settings.autoCompressRetainPercent,
                        selectedTargetTokens = settings.autoCompressTargetTokens,
                        customPresets = settings.compressPresets,
                        onApply = { retain, target ->
                            vm.updateSettings(
                                settings.copy(
                                    autoCompressRetainPercent = retain,
                                    autoCompressTargetTokens = target,
                                )
                            )
                        },
                        onManage = { showPresetManager = true },
                    )

                    NumericSettingField(
                        label = stringResource(R.string.setting_model_page_auto_compress_retain),
                        value = settings.autoCompressRetainPercent,
                        range = AUTO_COMPRESS_RETAIN_PERCENT_RANGE,
                        onValueChange = {
                            vm.updateSettings(settings.copy(autoCompressRetainPercent = it))
                        },
                    )
                    NumericSettingField(
                        label = stringResource(R.string.setting_model_page_auto_compress_target),
                        value = settings.autoCompressTargetTokens,
                        range = COMPRESS_TARGET_TOKENS_RANGE,
                        onValueChange = {
                            vm.updateSettings(settings.copy(autoCompressTargetTokens = it))
                        },
                    )

                    Text(
                        text = stringResource(R.string.setting_model_page_auto_compress_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 数值设置项：文本输入 + 提交时按 [range] 钳制落库。
 *
 * 为什么用文本而非 slider/分段：用户要能填**任意**值（设计稿 §五），但越界值必须钳制——
 * 阈值/保留比例的合法范围是压缩正确性的一部分（如保留比例过低保不住近期细节）。
 * 输入过程中允许中间态（如空串、超范围）不落库，只在解析成功且非空时提交。
 */
@Composable
private fun NumericSettingField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.trim().toIntOrNull()
    val isOutOfRange = text.isNotBlank() && (parsed == null || parsed !in range)

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.trim().toIntOrNull()?.let { if (it in range) onValueChange(it) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = isOutOfRange,
        supportingText = {
            Text(stringResource(R.string.compress_preset_range_hint, range.first, range.last))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 上下文窗口全局默认：模型未内置 contextLength 且未手动指定时的兜底窗口大小。
 * 支持 "256k"/"1M" 格式；留空 = 不覆盖，走 256K 内置兜底。
 */
@Composable
private fun ContextLengthOverrideSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var input by remember(settings.contextLengthOverride) {
        mutableStateOf(settings.contextLengthOverride?.let { formatContextLength(it) } ?: "")
    }
    CardGroup(title = { Text(stringResource(R.string.setting_model_page_context_length_override)) }) {
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    input = newValue
                    val parsed = parseContextLengthInput(newValue)
                    vm.updateSettings(settings.copy(contextLengthOverride = parsed))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.setting_model_page_context_length_override_placeholder))
                },
                supportingText = {
                    Text(
                        text = stringResource(R.string.setting_model_page_context_length_override_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
    }
}

@Composable
private fun SuggestionModelSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    val title = stringResource(R.string.setting_model_page_suggestion_model)
    val state = rememberModelListState(
        modelId = settings.suggestionModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                trailingContent = {
                    Switch(
                        checked = settings.enableSuggestion,
                        onCheckedChange = {
                            vm.updateSettings(settings.copy(enableSuggestion = it))
                        }
                    )
                },
            )
            if (settings.enableSuggestion) {
                item(
                    onClick = { state.open() },
                    headlineContent = { Text(title) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = state.currentModel?.displayName
                                    ?: stringResource(R.string.model_list_select_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentModel != null) {
                                IconButton(
                                    onClick = { vm.updateSettings(settings.copy(suggestionModelId = null)) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.setting_model_page_suggestion_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) })
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
    /** 未选模型时的右侧文案（默认「请选择模型」；压缩项传「跟随对话模型」） */
    emptyText: String? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: emptyText
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onClear != null && state.currentModel != null) {
                            IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                                Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
