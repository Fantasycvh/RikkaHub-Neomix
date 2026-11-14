package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import kotlin.math.roundToInt
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
import app.androidrik.ai.core.ReasoningLevel
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
import app.androidrik.rikkahub.data.datastore.OcrBackend
import app.androidrik.rikkahub.data.datastore.OcrDeepSeekPurpose
import app.androidrik.rikkahub.data.datastore.DEFAULT_OCR_DEEPSEEK_BASE_URL
import app.androidrik.rikkahub.data.datastore.OCR_DEEPSEEK_MAX_PAGES_LIMIT
import app.androidrik.rikkahub.ui.components.ai.ModelListSheet
import app.androidrik.rikkahub.ui.components.ai.ReasoningButton
import app.androidrik.rikkahub.ui.components.ai.rememberModelListState
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.data.ai.compression.AUTO_COMPRESS_RETAIN_PERCENT_RANGE
import app.androidrik.rikkahub.data.ai.compression.AUTO_COMPRESS_THRESHOLD_PERCENT_RANGE
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS_RANGE
import app.androidrik.rikkahub.data.datastore.CONTEXT_IMAGE_LIMIT_RANGE
import app.androidrik.rikkahub.data.ai.compression.clamped
import app.androidrik.rikkahub.ui.components.ai.CompressPresetManageDialog
import app.androidrik.rikkahub.ui.components.ai.CompressPresetSelector
import app.androidrik.rikkahub.ui.components.ai.builtInCompressPresetNames
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
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
            Column {
                ModelSettingItem(
                    title = stringResource(R.string.setting_model_page_memory_consolidation_model),
                    description = stringResource(R.string.setting_model_page_memory_consolidation_model_desc),
                    modelId = settings.memoryConsolidationModelId,
                    providers = settings.providers,
                    onSelect = { vm.updateSettings(settings.copy(memoryConsolidationModelId = it.id)) },
                    onClear = { vm.updateSettings(settings.copy(memoryConsolidationModelId = null)) },
                    emptyText = stringResource(R.string.setting_model_page_memory_consolidation_empty),
                )
                // 整理任务的推理强度（上游 72d419171 增量移植）：仅配置了做梦模型时展示，
                // 对整理实际用上的模型生效；选「自动」= 原行为
                if (settings.memoryConsolidationModelId != null) {
                    FormItem(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        label = { Text(stringResource(R.string.setting_model_page_memory_consolidation_reasoning)) },
                        description = { Text(stringResource(R.string.setting_model_page_memory_consolidation_reasoning_desc)) },
                    ) {
                        ReasoningButton(
                            reasoningLevel = settings.memoryConsolidationReasoningLevel
                                ?: ReasoningLevel.AUTO,
                            onUpdateReasoningLevel = { level ->
                                vm.updateSettings(
                                    settings.copy(memoryConsolidationReasoningLevel = level)
                                )
                            }
                        )
                    }
                }
            }
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
            OcrBackendSettingItem(settings = settings, vm = vm)
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
        item {
            ContextImageLimitSetting(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelCardScaleSetting(
                settings = settings,
                vm = vm,
            )
        }
    }
}

/**
 * 模型卡片大小（design-model-card-scale.md）：三处模型卡片独立连续缩放，
 * 存储为 Float 比例，滑块用整数百分比换算（75%–150%）；拖动即时预览、松手落库。
 */
private val MODEL_CARD_SCALE_PERCENT_RANGE = 75..150

@Composable
private fun ModelCardScaleSetting(
    settings: Settings,
    vm: SettingVM,
) {
    CardGroup(title = { Text("模型卡片大小") }) {
        item {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SliderSettingField(
                    label = "模型选择弹窗卡片",
                    value = (settings.displaySetting.modelPickerCardScale * 100).roundToInt(),
                    range = MODEL_CARD_SCALE_PERCENT_RANGE,
                    onValueChange = {
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    modelPickerCardScale = it / 100f
                                )
                            )
                        )
                    },
                )
                SliderSettingField(
                    label = "模型管理页卡片",
                    value = (settings.displaySetting.modelManageCardScale * 100).roundToInt(),
                    range = MODEL_CARD_SCALE_PERCENT_RANGE,
                    onValueChange = {
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    modelManageCardScale = it / 100f
                                )
                            )
                        )
                    },
                )
                SliderSettingField(
                    label = "添加模型弹窗卡片",
                    value = (settings.displaySetting.modelAddCardScale * 100).roundToInt(),
                    range = MODEL_CARD_SCALE_PERCENT_RANGE,
                    onValueChange = {
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    modelAddCardScale = it / 100f
                                )
                            )
                        )
                    },
                )
                SliderSettingField(
                    label = "提供商列表卡片",
                    value = (settings.displaySetting.providerCardScale * 100).roundToInt(),
                    range = MODEL_CARD_SCALE_PERCENT_RANGE,
                    onValueChange = {
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    providerCardScale = it / 100f
                                )
                            )
                        )
                    },
                )
            }
        }
    }
}

/**
 * 上下文图片清单上限（借鉴 NMoi D248/D272）：请求窗口只保留最近 N 张图片，更旧的换成
 * 占位文本、模型可用 view_image 按路径取回。只影响发送的请求、不动落库与 UI。
 * 0 = 关闭淘汰（历史行为）。
 */
@Composable
private fun ContextImageLimitSetting(
    settings: Settings,
    vm: SettingVM,
) {
    CardGroup(title = { Text("上下文图片") }) {
        item {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumericSettingField(
                    label = "图片保留张数（0 = 不淘汰）",
                    value = settings.contextImageLimit,
                    range = CONTEXT_IMAGE_LIMIT_RANGE,
                    onValueChange = {
                        vm.updateSettings(settings.copy(contextImageLimit = it))
                    },
                )
                Text(
                    text = "长会话里更早的图片会在发送时移出上下文，替换为带路径的占位文本；" +
                        "模型需要时可通过 view_image 工具把图片取回。不影响聊天记录里的原图。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    // 触发阈值：**只属自动压缩**（手动由用户自己判断何时压，无此参数）。
                    // 滑杆拖拽即时预览、松手落库；数值框可填任意值，提交时按范围钳制。
                    SliderSettingField(
                        label = stringResource(R.string.setting_model_page_auto_compress_threshold),
                        value = settings.autoCompressThresholdPercent,
                        range = AUTO_COMPRESS_THRESHOLD_PERCENT_RANGE,
                        onValueChange = {
                            vm.updateSettings(settings.copy(autoCompressThresholdPercent = it))
                        },
                    )

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
 * 数值设置项（滑杆 + 可填写）：滑杆拖拽即时预览、松手落库；文本框可填任意值，解析成功且
 * 在 [range] 内才提交（与 [NumericSettingField] 同口径，越界标红不落库）。
 *
 * 为什么不直接滑一步写一步：滑杆拖动每帧都会触发 onValueChange，直接连到 DataStore 写入
 * 会高频落库——用 [dragValue] 缓冲拖动中的值，`onValueChangeFinished` 才提交。
 * `remember(value)` 保证设置被其他入口（如压缩配置档）改写后两个控件都回填同步。
 */
@Composable
private fun SliderSettingField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var dragValue by remember(value) { mutableStateOf<Int?>(null) }
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.trim().toIntOrNull()
    val isOutOfRange = text.isNotBlank() && (parsed == null || parsed !in range)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${dragValue ?: value}%",
                style = MaterialTheme.typography.labelLarge,
                color = if (dragValue != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Slider(
            value = (dragValue ?: value).toFloat(),
            onValueChange = { dragValue = it.roundToInt().coerceIn(range.first, range.last) },
            onValueChangeFinished = {
                dragValue?.let(onValueChange)
                dragValue = null
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                new.trim().toIntOrNull()?.let { if (it in range) onValueChange(it) }
            },
            isError = isOutOfRange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = { Text("%") },
            supportingText = {
                Text(stringResource(R.string.compress_preset_range_hint, range.first, range.last))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    // 本地权威编辑态 + 去抖单向写出（AGENTS「TextField Host Rules」第 4 条；范本 1808d418f）。
    // 旧写法 `remember(settings.contextLengthOverride)`：每次设置回声（含本框自己的逐键提交）
    // 都把缓冲重置成「格式化后的持久值」——既改写用户原文（"256k"/"1M" 被重新格式化），
    // 也逐字符抖动（D2 最易触发）。现在输入过程中保留用户原文，只在去抖提交时解析一次；
    // 解析失败不落库、保留用户输入让他继续改，用 isError 提示。
    // resetKey = settings.init：首帧占位 Settings 加载完成后只重建一次本地态。
    val inputState = rememberPersistedTextFieldState(
        externalValue = settings.contextLengthOverride?.let(::formatContextLength) ?: "",
        resetKey = settings.init,
        onCommit = { text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                // 空串 = 不覆盖（走内置 256K 兜底）
                vm.updateSettings { it.copy(contextLengthOverride = null) }
            } else {
                // 非空且无法解析 = 输入中间态：不落库，保留用户原文让他继续改（isError 提示）
                val parsed = parseContextLengthInput(trimmed)
                if (parsed != null) {
                    vm.updateSettings { it.copy(contextLengthOverride = parsed) }
                }
            }
        },
    )
    // isError/supportingText 都从本地权威文本算：不读持久态，避免落盘回声让错误态闪烁。
    val typedText = inputState.text.toString()
    val isInvalid = typedText.isNotBlank() && parseContextLengthInput(typedText) == null
    CardGroup(title = { Text(stringResource(R.string.setting_model_page_context_length_override)) }) {
        item {
            OutlinedTextField(
                state = inputState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.setting_model_page_context_length_override_placeholder))
                },
                isError = isInvalid,
                supportingText = {
                    Text(
                        text = stringResource(R.string.setting_model_page_context_length_override_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                lineLimits = TextFieldLineLimits.SingleLine,
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
                supportingContent = {
                    Text(
                        text = state.currentModel?.displayName ?: emptyText ?: stringResource(R.string.model_list_select_model),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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

/**
 * OCR 后端选择（DeepSeek 适配备料 03 §2.2）。
 *
 * CardGroup 是 Column 实现（AGENTS.md TextField 宿主规范）。providerId/baseUrl/maxPages
 * 三个高级字段的**编辑器都进 ModalBottomSheet**（整个区块在页面的 Lazy item 里，内联
 * TextField 会踩「键盘收缩裁剪视口 → item 销毁重建」坑）；sheet 内用
 * `rememberPersistedTextFieldState` 本地权威 + 去抖单向写出，关闭时补写一次防丢；
 * 全部走字段级 `vm.updateSettings { }` transform，不走「读 settings.value 改整对象写回」。
 */
@Composable
private fun OcrBackendSettingItem(settings: Settings, vm: SettingVM) {
    Column {
        CardGroup(title = { Text(stringResource(R.string.setting_model_page_ocr_backend)) }) {
            item(
                headlineContent = {
                    SegmentedPill(
                        options = listOf(OcrBackend.MODEL, OcrBackend.DEEPSEEK),
                        selectedOption = settings.ocrBackend,
                        onOptionSelected = { backend ->
                            vm.updateSettings { it.copy(ocrBackend = backend) }
                        },
                        optionToString = { backend ->
                            when (backend) {
                                OcrBackend.MODEL -> stringResource(R.string.setting_model_page_ocr_backend_model)
                                OcrBackend.DEEPSEEK -> stringResource(R.string.setting_model_page_ocr_backend_deepseek)
                            }
                        },
                    )
                },
            )
        }
        Text(
            text = stringResource(R.string.setting_model_page_ocr_backend_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        if (settings.ocrBackend == OcrBackend.DEEPSEEK) {
            val selectedProvider = settings.ocrDeepSeekProviderId?.let { id ->
                settings.providers.find { it.id == id }
            }
            var showProviderPicker by remember { mutableStateOf(false) }
            var showBaseUrlEditor by remember { mutableStateOf(false) }
            var showMaxPagesEditor by remember { mutableStateOf(false) }
            CardGroup {
                item(
                    headlineContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.setting_model_page_ocr_deepseek_purpose))
                            SegmentedPill(
                                options = listOf(OcrDeepSeekPurpose.TEXT, OcrDeepSeekPurpose.RICH_LAYOUT),
                                selectedOption = settings.ocrDeepSeekPurpose,
                                onOptionSelected = { purpose ->
                                    vm.updateSettings { it.copy(ocrDeepSeekPurpose = purpose) }
                                },
                                optionToString = { purpose ->
                                    when (purpose) {
                                        OcrDeepSeekPurpose.TEXT -> stringResource(R.string.setting_model_page_ocr_deepseek_purpose_text)
                                        OcrDeepSeekPurpose.RICH_LAYOUT -> stringResource(R.string.setting_model_page_ocr_deepseek_purpose_rich)
                                    }
                                },
                            )
                        }
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.setting_model_page_ocr_deepseek_purpose_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                item(
                    headlineContent = {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text(stringResource(R.string.setting_model_page_ocr_deepseek_include_layout))
                                Text(
                                    text = stringResource(R.string.setting_model_page_ocr_deepseek_include_layout_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.ocrDeepSeekIncludeLayout,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { it.copy(ocrDeepSeekIncludeLayout = checked) }
                                },
                            )
                        }
                    },
                )
                item(
                    onClick = { showProviderPicker = true },
                    headlineContent = {
                        Text(stringResource(R.string.setting_model_page_ocr_deepseek_provider))
                    },
                    supportingContent = {
                        Text(
                            text = selectedProvider?.name
                                ?: stringResource(R.string.setting_model_page_ocr_deepseek_provider_builtin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                item(
                    onClick = { showBaseUrlEditor = true },
                    headlineContent = {
                        Text(stringResource(R.string.setting_model_page_ocr_deepseek_base_url))
                    },
                    supportingContent = {
                        Text(
                            text = settings.ocrDeepSeekBaseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                item(
                    onClick = { showMaxPagesEditor = true },
                    headlineContent = {
                        Text(stringResource(R.string.setting_model_page_ocr_deepseek_max_pages))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.setting_model_page_ocr_deepseek_max_pages_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Text("${settings.ocrDeepSeekMaxPages}") },
                )
            }
            Text(
                text = stringResource(R.string.setting_model_page_ocr_deepseek_privacy_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            if (showProviderPicker) {
                OcrDeepSeekProviderPickerSheet(
                    // 只有 OpenAI 型提供商有 baseUrl/apiKey 语义（DeepSeek 内置 provider 同型）
                    providers = settings.providers.filterIsInstance<ProviderSetting.OpenAI>(),
                    selectedId = settings.ocrDeepSeekProviderId,
                    onSelect = { id ->
                        vm.updateSettings { it.copy(ocrDeepSeekProviderId = id) }
                    },
                    onDismiss = { showProviderPicker = false },
                )
            }
            if (showBaseUrlEditor) {
                OcrDeepSeekTextEditSheet(
                    title = stringResource(R.string.setting_model_page_ocr_deepseek_base_url),
                    description = stringResource(R.string.setting_model_page_ocr_deepseek_base_url_desc),
                    current = settings.ocrDeepSeekBaseUrl,
                    onCommit = { text ->
                        val normalized = text.trim().ifBlank { DEFAULT_OCR_DEEPSEEK_BASE_URL }
                        vm.updateSettings { it.copy(ocrDeepSeekBaseUrl = normalized) }
                    },
                    onDismiss = { showBaseUrlEditor = false },
                )
            }
            if (showMaxPagesEditor) {
                OcrDeepSeekTextEditSheet(
                    title = stringResource(R.string.setting_model_page_ocr_deepseek_max_pages),
                    description = stringResource(R.string.setting_model_page_ocr_deepseek_max_pages_desc),
                    current = "${settings.ocrDeepSeekMaxPages}",
                    numberOnly = true,
                    onCommit = { text ->
                        text.toIntOrNull()?.let { pages ->
                            vm.updateSettings {
                                it.copy(ocrDeepSeekMaxPages = pages.coerceIn(1, OCR_DEEPSEEK_MAX_PAGES_LIMIT))
                            }
                        }
                    },
                    onDismiss = { showMaxPagesEditor = false },
                )
            }
        }
    }
}

/**
 * OCR 密钥提供商选择（null = 内置 DeepSeek 提供商共享 Key，配料 03 §2.2）。
 * 纯选择无输入框，不存在 IME 视口裁剪问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrDeepSeekProviderPickerSheet(
    providers: List<ProviderSetting.OpenAI>,
    selectedId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_model_page_ocr_deepseek_provider),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.setting_model_page_ocr_deepseek_provider_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelect(null)
                        onDismiss()
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedId == null, onClick = null)
                Text(
                    text = stringResource(R.string.setting_model_page_ocr_deepseek_provider_builtin),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(provider.id)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedId == provider.id, onClick = null)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(text = provider.name.ifBlank { provider.id.toString() })
                        Text(
                            text = provider.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单字段编辑 sheet（AGENTS TextField 宿主规范：编辑器不内联 Lazy item，进 sheet 常驻）。
 *
 * `numberOnly` 时提交解析 [Int]，解析失败不落库（保持原值）；关闭时补写一次——
 * 去抖窗口内的编辑在 sheet 销毁后 snapshotFlow 不会再触发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrDeepSeekTextEditSheet(
    title: String,
    description: String,
    current: String,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    numberOnly: Boolean = false,
) {
    val state = rememberPersistedTextFieldState(
        externalValue = current,
        onCommit = onCommit,
    )
    ModalBottomSheet(
        onDismissRequest = {
            onCommit(state.text.toString())
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                state = state,
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numberOnly) KeyboardType.Number else KeyboardType.Uri,
                ),
            )
        }
    }
}
