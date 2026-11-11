package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.MemoryTriggerConfig
import app.androidrik.rikkahub.data.ai.MemoryTriggerMode
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.AppTextField as TextField
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.CardGroupScope
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 记忆整理触发方式（`docs/design/design-memory-consolidation-trigger.md`）。
 *
 * 全局一份配置（Settings.memoryTriggerConfig），与每助手的「整理」开关/基线正交。
 * P0 只放三个轮次类模式（IDLE/每 N 轮/第 N 轮）；墙钟四模式（一次性/间隔/每日/每周）
 * 随 P1 的闹钟层一起上，届时在这里追加选项。
 *
 * TextField 宿主规范：本页参数输入全部放在 **CardGroup（Column 实现）** 里，不在 Lazy item
 * 内联长表单——遵循 AGENTS.md 的 IME 宿主规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingMemoryTriggerPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val config = settings.memoryTriggerConfig

    fun update(transform: (MemoryTriggerConfig) -> MemoryTriggerConfig) {
        scope.launch { settingsStore.update { it.copy(memoryTriggerConfig = transform(it.memoryTriggerConfig)) } }
    }

    var showScopePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_trigger_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    // P0 模式集；墙钟模式由 P1 追加（设计稿 §九）
                    item {
                        Column {
                            TriggerModeRow(
                                mode = MemoryTriggerMode.IDLE,
                                selected = config.mode == MemoryTriggerMode.IDLE,
                                onSelect = { update { it.copy(mode = MemoryTriggerMode.IDLE) } },
                            )
                            TriggerModeRow(
                                mode = MemoryTriggerMode.EVERY_N_TURNS,
                                selected = config.mode == MemoryTriggerMode.EVERY_N_TURNS,
                                onSelect = { update { it.copy(mode = MemoryTriggerMode.EVERY_N_TURNS) } },
                            )
                            TriggerModeRow(
                                mode = MemoryTriggerMode.TURN_N,
                                selected = config.mode == MemoryTriggerMode.TURN_N,
                                onSelect = { update { it.copy(mode = MemoryTriggerMode.TURN_N) } },
                            )
                        }
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    when (config.mode) {
                        MemoryTriggerMode.IDLE -> {
                            numberParamItem(
                                headlineRes = R.string.memory_trigger_param_idle_delay,
                                supportingRes = R.string.memory_trigger_param_idle_delay_desc,
                                value = config.idleDelayMinutes,
                                range = MemoryTriggerConfig.MIN_IDLE_DELAY_MINUTES..
                                    MemoryTriggerConfig.MAX_IDLE_DELAY_MINUTES,
                                onChange = { v -> update { it.copy(idleDelayMinutes = v) } },
                            )
                            numberParamItem(
                                headlineRes = R.string.memory_trigger_param_every_n,
                                supportingRes = R.string.memory_trigger_param_every_n_desc,
                                value = config.everyNTurns,
                                range = 1..100,
                                onChange = { v -> update { it.copy(everyNTurns = v) } },
                            )
                        }

                        MemoryTriggerMode.EVERY_N_TURNS -> numberParamItem(
                            headlineRes = R.string.memory_trigger_param_every_n,
                            supportingRes = R.string.memory_trigger_param_every_n_desc,
                            value = config.everyNTurns,
                            range = 1..100,
                            onChange = { v -> update { it.copy(everyNTurns = v) } },
                        )

                        MemoryTriggerMode.TURN_N -> numberParamItem(
                            headlineRes = R.string.memory_trigger_param_turn_n,
                            supportingRes = R.string.memory_trigger_param_turn_n_desc,
                            value = config.triggerTurn,
                            range = 1..1000,
                            onChange = { v -> update { it.copy(triggerTurn = v) } },
                        )

                        else -> Unit
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = { showScopePicker = true },
                        headlineContent = { Text(stringResource(R.string.memory_trigger_scope)) },
                        supportingContent = {
                            Text(
                                if (config.scopeAssistantIds.isEmpty()) {
                                    stringResource(R.string.memory_trigger_scope_all)
                                } else {
                                    stringResource(
                                        R.string.memory_trigger_scope_selected,
                                        config.scopeAssistantIds.size,
                                    )
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.memory_trigger_note_per_assistant),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }

    if (showScopePicker) {
        ScopePickerSheet(
            assistants = settings.assistants,
            selected = config.scopeAssistantIds,
            onConfirm = { ids ->
                update { it.copy(scopeAssistantIds = ids) }
                showScopePicker = false
            },
            onDismiss = { showScopePicker = false },
        )
    }
}

@Composable
private fun TriggerModeRow(
    mode: MemoryTriggerMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = stringResource(mode.titleRes()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(mode.descRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun MemoryTriggerMode.titleRes(): Int = when (this) {
    MemoryTriggerMode.IDLE -> R.string.memory_trigger_mode_idle
    MemoryTriggerMode.EVERY_N_TURNS -> R.string.memory_trigger_mode_every_n
    MemoryTriggerMode.TURN_N -> R.string.memory_trigger_mode_turn_n
    MemoryTriggerMode.ONCE -> R.string.memory_trigger_mode_once
    MemoryTriggerMode.INTERVAL -> R.string.memory_trigger_mode_interval
    MemoryTriggerMode.DAILY -> R.string.memory_trigger_mode_daily
    MemoryTriggerMode.WEEKLY -> R.string.memory_trigger_mode_weekly
}

internal fun MemoryTriggerMode.descRes(): Int = when (this) {
    MemoryTriggerMode.IDLE -> R.string.memory_trigger_mode_idle_desc
    MemoryTriggerMode.EVERY_N_TURNS -> R.string.memory_trigger_mode_every_n_desc
    MemoryTriggerMode.TURN_N -> R.string.memory_trigger_mode_turn_n_desc
    MemoryTriggerMode.ONCE -> R.string.memory_trigger_mode_once_desc
    MemoryTriggerMode.INTERVAL -> R.string.memory_trigger_mode_interval_desc
    MemoryTriggerMode.DAILY -> R.string.memory_trigger_mode_daily_desc
    MemoryTriggerMode.WEEKLY -> R.string.memory_trigger_mode_weekly_desc
}

/** 数字参数行（CardGroup 扩展：CardGroup 是 Column 实现，IME 宿主安全，见 AGENTS.md）
 *
 * 状态流向（AGENTS.md 规则 4）：本地 text 是编辑态唯一权威，程序化回填只在合法写入时
 * 经 remember(value) 发生——绝不向活跃 IME 会话回写无效中间态（否则退格/清空会「打不动」）。
 */
private fun CardGroupScope.numberParamItem(
    headlineRes: Int,
    supportingRes: Int,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(headlineRes)) },
        supportingContent = { Text(stringResource(supportingRes)) },
        trailingContent = {
            var text by remember(value) { mutableStateOf(value.toString()) }
            TextField(
                value = text,
                onValueChange = { input ->
                    text = input.filter { it.isDigit() }
                    val parsed = text.toIntOrNull()
                    if (parsed != null && parsed in range && parsed != value) onChange(parsed)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                // 固定宽度：不约束的话 TextField 会把 supporting 文本挤成竖排一列（真机截图实测）
                modifier = Modifier.width(110.dp),
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        },
    )
}

/**
 * 范围选择（决议 #5）：空集 = 全部助手。ModalBottomSheet + Column/verticalScroll——
 * 没有输入框，纯选择，不存在 Lazy item 视口裁剪问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopePickerSheet(
    assistants: List<Assistant>,
    selected: Set<Uuid>,
    onConfirm: (Set<Uuid>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(selected) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_trigger_scope),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.memory_trigger_scope_pick_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            assistants.forEach { assistant ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            draft = if (assistant.id in draft) draft - assistant.id else draft + assistant.id
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = assistant.id in draft,
                        onCheckedChange = { checked ->
                            draft = if (checked) draft + assistant.id else draft - assistant.id
                        },
                    )
                    Text(
                        text = assistant.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            TextButton(
                onClick = { onConfirm(draft) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp),
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        }
    }
}
