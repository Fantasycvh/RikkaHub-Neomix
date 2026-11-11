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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.CronExpression
import app.androidrik.rikkahub.data.ai.MemoryTriggerConfig
import app.androidrik.rikkahub.data.ai.MemoryTriggerMode
import app.androidrik.rikkahub.data.ai.isWallClock
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
 * 七种模式**单选互斥**（决议 #2）：轮次类（IDLE / 每 N 轮 / 第 N 轮）由聊天事件驱动；
 * 墙钟类（一次性 / 间隔 / 每日 / 每周）由闹钟驱动，且会**停用**全部事件入口。
 *
 * 改任何墙钟参数都会把 `nextRunAt` 置空：由 `MemoryTriggerScheduleManager` 对账重新排首槽
 * （配置流驱动，改完几秒内生效，不需要手动「应用」）。
 *
 * TextField 宿主规范：本页输入全部放在 **CardGroup（Column 实现）** 里，不在 Lazy item
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

    /** 墙钟参数变化：旧排程作废（nextRunAt 置空由对账层重算首槽），ONCE 同时复位已执行标记 */
    fun updateSchedule(transform: (MemoryTriggerConfig) -> MemoryTriggerConfig) {
        update { it.copy(nextRunAt = null).let(transform) }
    }

    fun selectMode(mode: MemoryTriggerMode) {
        update { current ->
            val now = System.currentTimeMillis()
            // 切到 ONCE 且没有合法未来时刻时，预填「一小时后」，避免配置落到「立即触发」或永不触发
            val payload = if (mode == MemoryTriggerMode.ONCE && current.schedule.runAt <= now) {
                current.schedule.copy(runAt = now + 3_600_000L)
            } else {
                current.schedule
            }
            current.copy(
                mode = mode,
                schedule = payload,
                nextRunAt = null,
                onceFired = if (mode == MemoryTriggerMode.ONCE) false else current.onceFired,
            )
        }
    }

    /**
     * ONCE 的日期/时刻任一处改动 ⇒ 重算 `runAt`（日期缺省取今天）。
     * `hour/minute` 与 `runAt` 同步写，两者任何时刻都一致（UI 显示读 runAt、排程读 runAt）。
     */
    fun setOnceRunAt(hour: Int = config.schedule.hour, minute: Int = config.schedule.minute) {
        val zone = ZoneId.systemDefault()
        val baseDate = if (config.schedule.runAt > 0) {
            Instant.ofEpochMilli(config.schedule.runAt).atZone(zone).toLocalDate()
        } else {
            LocalDate.now()
        }
        val millis = baseDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        updateSchedule {
            it.copy(schedule = it.schedule.copy(runAt = millis, hour = hour, minute = minute))
        }
    }

    var showScopePicker by remember { mutableStateOf(false) }
    var showOnceDatePicker by remember { mutableStateOf(false) }

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
                    item {
                        Column {
                            MemoryTriggerMode.entries.forEach { mode ->
                                TriggerModeRow(
                                    mode = mode,
                                    selected = config.mode == mode,
                                    onSelect = { selectMode(mode) },
                                )
                            }
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

                        MemoryTriggerMode.ONCE -> {
                            item(
                                onClick = { showOnceDatePicker = true },
                                headlineContent = { Text(stringResource(R.string.memory_trigger_param_once_date)) },
                                supportingContent = {
                                    Text(
                                        if (config.schedule.runAt > 0) {
                                            formatDateTime(config.schedule.runAt)
                                        } else {
                                            stringResource(R.string.scheduled_task_pick_date)
                                        }
                                    )
                                },
                            )
                            timeOfDayItem(
                                headlineRes = R.string.memory_trigger_param_time,
                                hour = config.schedule.hour,
                                minute = config.schedule.minute,
                                onHour = { h -> setOnceRunAt(hour = h) },
                                onMinute = { m -> setOnceRunAt(minute = m) },
                            )
                        }

                        MemoryTriggerMode.INTERVAL -> numberParamItem(
                            headlineRes = R.string.memory_trigger_param_interval,
                            supportingRes = R.string.memory_trigger_param_interval_desc,
                            value = config.schedule.intervalMinutes.toInt(),
                            range = 15..10080,
                            onChange = { v ->
                                updateSchedule { it.copy(schedule = it.schedule.copy(intervalMinutes = v.toLong())) }
                            },
                        )

                        MemoryTriggerMode.DAILY -> timeOfDayItem(
                            headlineRes = R.string.memory_trigger_param_time,
                            hour = config.schedule.hour,
                            minute = config.schedule.minute,
                            onHour = { h ->
                                updateSchedule { it.copy(schedule = it.schedule.copy(hour = h)) }
                            },
                            onMinute = { m ->
                                updateSchedule { it.copy(schedule = it.schedule.copy(minute = m)) }
                            },
                        )

                        MemoryTriggerMode.WEEKLY -> {
                            weekdayItem(
                                dayOfWeek = config.schedule.dayOfWeek,
                                onSelect = { dow ->
                                    updateSchedule { it.copy(schedule = it.schedule.copy(dayOfWeek = dow)) }
                                },
                            )
                            timeOfDayItem(
                                headlineRes = R.string.memory_trigger_param_time,
                                hour = config.schedule.hour,
                                minute = config.schedule.minute,
                                onHour = { h ->
                                    updateSchedule { it.copy(schedule = it.schedule.copy(hour = h)) }
                                },
                                onMinute = { m ->
                                    updateSchedule { it.copy(schedule = it.schedule.copy(minute = m)) }
                                },
                            )
                        }

                        MemoryTriggerMode.CRON -> cronExpressionItem(
                            expression = config.cronExpression,
                            onCommit = { expr -> updateSchedule { it.copy(cronExpression = expr) } },
                        )
                    }
                }
            }
            if (config.mode.isWallClock) {
                item {
                    CardGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        item(
                            headlineContent = { Text(stringResource(R.string.memory_trigger_status_title)) },
                            supportingContent = {
                                Text(
                                    when {
                                        config.mode == MemoryTriggerMode.ONCE && config.onceFired ->
                                            stringResource(R.string.memory_trigger_status_once_done)

                                        config.nextRunAt != null ->
                                            stringResource(
                                                R.string.memory_trigger_status_next,
                                                formatDateTime(config.nextRunAt),
                                            )

                                        else -> stringResource(R.string.memory_trigger_status_pending)
                                    }
                                )
                            },
                        )
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

    if (showOnceDatePicker) {
        val currentDate = if (config.schedule.runAt > 0) {
            Instant.ofEpochMilli(config.schedule.runAt).atZone(ZoneId.systemDefault()).toLocalDate()
        } else {
            LocalDate.now()
        }
        // Material3 的 DatePicker 以 UTC 午夜表示日历日（与 AssistantMemoryPage 同款换算）
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = currentDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showOnceDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let { selected ->
                            val localDate = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                            val millis = localDate
                                .atTime(config.schedule.hour, config.schedule.minute)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                            updateSchedule { it.copy(schedule = it.schedule.copy(runAt = millis)) }
                        }
                        showOnceDatePicker = false
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showOnceDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }
}

/** ONCE 的时/分改动要重组 runAt（日期保持选定日；未选过则从今天起算）——由页面内联闭包完成 */

private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatDateTime(millis: Long): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

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
    MemoryTriggerMode.CRON -> R.string.memory_trigger_mode_cron
}

internal fun MemoryTriggerMode.descRes(): Int = when (this) {
    MemoryTriggerMode.IDLE -> R.string.memory_trigger_mode_idle_desc
    MemoryTriggerMode.EVERY_N_TURNS -> R.string.memory_trigger_mode_every_n_desc
    MemoryTriggerMode.TURN_N -> R.string.memory_trigger_mode_turn_n_desc
    MemoryTriggerMode.ONCE -> R.string.memory_trigger_mode_once_desc
    MemoryTriggerMode.INTERVAL -> R.string.memory_trigger_mode_interval_desc
    MemoryTriggerMode.DAILY -> R.string.memory_trigger_mode_daily_desc
    MemoryTriggerMode.WEEKLY -> R.string.memory_trigger_mode_weekly_desc
    MemoryTriggerMode.CRON -> R.string.memory_trigger_mode_cron_desc
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

/** 时 / 分输入行（墙钟模式共用；0..23 / 0..59，合法即提交） */
private fun CardGroupScope.timeOfDayItem(
    headlineRes: Int,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    formItem(headlineContent = { Text(stringResource(headlineRes)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = hour,
                range = 0..23,
                label = stringResource(R.string.scheduled_task_hour),
                onCommit = onHour,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = minute,
                range = 0..59,
                label = stringResource(R.string.scheduled_task_minute),
                onCommit = onMinute,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * CRON 表达式输入（5 字段）。**只把合法表达式写回配置**：非法中间态留在本地输入框里，
 * 避免逐键写 DataStore，也避免管理器拿半截表达式反复对账；错误直接在字段下方展示。
 * 合法时额外显示「下次触发」预览——cron 的语义光看表达式很难自证。
 */
private fun CardGroupScope.cronExpressionItem(
    expression: String,
    onCommit: (String) -> Unit,
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.memory_trigger_param_cron)) },
        supportingContent = { Text(stringResource(R.string.memory_trigger_param_cron_desc)) },
    ) {
        var text by remember(expression) { mutableStateOf(expression) }
        val parseError = remember(text) {
            runCatching { CronExpression.parse(text) }.exceptionOrNull()
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                if (runCatching { CronExpression.parse(input) }.isSuccess) onCommit(input)
            },
            isError = parseError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        if (parseError != null) {
            Text(
                text = parseError.message ?: stringResource(R.string.memory_trigger_cron_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val next = runCatching {
                CronExpression.parse(text).nextAfter(System.currentTimeMillis())
            }.getOrNull()
            Text(
                text = if (next != null) {
                    stringResource(R.string.memory_trigger_cron_next, formatDateTime(next))
                } else {
                    stringResource(R.string.memory_trigger_cron_never)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** WEEKLY 的星期选择（ISO 语义 1=周一…7=周日；与 SchedulePayload 一致） */
private fun CardGroupScope.weekdayItem(dayOfWeek: Int, onSelect: (Int) -> Unit) {
    formItem(headlineContent = { Text(stringResource(R.string.memory_trigger_param_weekday)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { dow ->
                val selected = dayOfWeek == dow
                Text(
                    text = java.time.DayOfWeek.of(dow)
                        .getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(dow) }
                        .padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (selected) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    value: Int,
    range: IntRange,
    label: String,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(4)
            val parsed = text.toIntOrNull()
            if (parsed != null && parsed in range && parsed != value) onCommit(parsed)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
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
