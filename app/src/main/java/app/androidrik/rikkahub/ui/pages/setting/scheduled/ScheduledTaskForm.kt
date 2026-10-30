package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 创建/详情两处共用的表单状态（docs/design/design-scheduled-ai-tasks.md §七）。
 *
 * 目标会话不在表单里：创建时由外层的选择器决定，详情页只读展示（执行锚点不可改）。
 */
@Stable
class ScheduledTaskFormState {
    var title by mutableStateOf("")
    var prompt by mutableStateOf("")
    var type by mutableStateOf(ScheduleType.DAILY)
    var hourText by mutableStateOf("8")
    var minuteText by mutableStateOf("0")
    var dayOfWeek by mutableIntStateOf(1)
    var intervalText by mutableStateOf("240")
    var runAtDate by mutableStateOf<LocalDate?>(null)
    var missPolicy by mutableStateOf(MissPolicy.RUN_LATE)
    var idleOnly by mutableStateOf(false)

    val hour: Int? get() = hourText.toIntOrNull()?.coerceIn(0, 23)
    val minute: Int? get() = minuteText.toIntOrNull()?.coerceIn(0, 59)
    val interval: Long? get() = intervalText.toLongOrNull()?.coerceAtLeast(15)

    /** ONCE 需要 date + 时刻；其余类型只要求时刻/间隔字段可解析 */
    val isValid: Boolean
        get() = title.isNotBlank() && prompt.isNotBlank() && hour != null && minute != null &&
            (type != ScheduleType.INTERVAL || interval != null) &&
            (type != ScheduleType.ONCE || runAtDate != null)

    fun payload(): SchedulePayload = SchedulePayload(
        hour = hour ?: 8,
        minute = minute ?: 0,
        dayOfWeek = dayOfWeek,
        intervalMinutes = interval ?: 240,
        runAt = runAtDate
            ?.atTime(hour ?: 8, minute ?: 0)
            ?.atZone(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
            ?: 0L,
    )

    fun loadFrom(entity: ScheduledTaskEntity) {
        val payload = SchedulePayload.decode(entity.schedulePayload)
        title = entity.title
        prompt = entity.prompt
        type = runCatching { ScheduleType.valueOf(entity.scheduleType) }.getOrDefault(ScheduleType.DAILY)
        hourText = payload.hour.toString()
        minuteText = payload.minute.toString()
        dayOfWeek = payload.dayOfWeek
        intervalText = payload.intervalMinutes.toString()
        runAtDate = if (payload.runAt > 0) {
            Instant.ofEpochMilli(payload.runAt).atZone(ZoneId.systemDefault()).toLocalDate()
        } else {
            null
        }
        missPolicy = runCatching { MissPolicy.valueOf(entity.missPolicy) }.getOrDefault(MissPolicy.RUN_LATE)
        idleOnly = entity.idleOnly
    }
}

@Composable
fun rememberScheduledTaskFormState(entity: ScheduledTaskEntity?): ScheduledTaskFormState =
    remember(entity?.id) { ScheduledTaskFormState().apply { entity?.let(::loadFrom) } }

/**
 * 表单字段主体（名称/提示词/周期/时刻/错过策略/idleOnly）。
 * ONCE 的日期选择对话框内置；会话选择由外层负责（两处形态不同）。
 */
@Composable
fun ScheduledTaskFormFields(state: ScheduledTaskFormState, modifier: Modifier = Modifier) {
    var showDatePicker by remember { mutableStateOf(false) }
    // 「时」按键盘的下一步直接落到「分」，省掉一次点按（此前是行内两个独立输入框，
    // 键盘不切数字、也不串焦点，见 docs/design/design-scheduled-ai-tasks.md §七）
    val minuteFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.title,
            onValueChange = { state.title = it },
            label = { Text(stringResource(R.string.scheduled_task_field_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.prompt,
            onValueChange = { state.prompt = it },
            label = { Text(stringResource(R.string.scheduled_task_field_prompt)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        FormItem(label = { Text(stringResource(R.string.scheduled_task_field_schedule)) }) {
            SegmentedPill(
                options = ScheduleType.entries,
                selectedOption = state.type,
                onOptionSelected = { state.type = it },
                optionToString = {
                    when (it) {
                        ScheduleType.ONCE -> stringResource(R.string.scheduled_task_type_once)
                        ScheduleType.DAILY -> stringResource(R.string.scheduled_task_type_daily)
                        ScheduleType.WEEKLY -> stringResource(R.string.scheduled_task_type_weekly)
                        ScheduleType.INTERVAL -> stringResource(R.string.scheduled_task_type_interval_short)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when (state.type) {
            ScheduleType.ONCE -> {
                TextButton(onClick = { showDatePicker = true }) {
                    Text(
                        state.runAtDate?.toString()
                            ?: stringResource(R.string.scheduled_task_pick_date),
                    )
                }
            }

            ScheduleType.WEEKLY -> {
                SegmentedPill(
                    options = (1..7).toList(),
                    selectedOption = state.dayOfWeek,
                    onOptionSelected = { state.dayOfWeek = it },
                    optionToString = { dow ->
                        DayOfWeek.of(dow).getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ScheduleType.INTERVAL -> {
                OutlinedTextField(
                    value = state.intervalText,
                    onValueChange = { state.intervalText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.scheduled_task_interval_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> Unit
        }
        // ONCE/DAILY/WEEKLY 都要时刻；INTERVAL 只看间隔
        if (state.type != ScheduleType.INTERVAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.hourText,
                    onValueChange = { state.hourText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.scheduled_task_hour)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { minuteFocusRequester.requestFocus() },
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.minuteText,
                    onValueChange = { state.minuteText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.scheduled_task_minute)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(minuteFocusRequester),
                )
            }
        }
        FormItem(label = { Text(stringResource(R.string.scheduled_task_field_miss_policy)) }) {
            SegmentedPill(
                options = MissPolicy.entries,
                selectedOption = state.missPolicy,
                onOptionSelected = { state.missPolicy = it },
                optionToString = {
                    when (it) {
                        MissPolicy.RUN_LATE -> stringResource(R.string.scheduled_task_miss_run_late)
                        MissPolicy.SKIP -> stringResource(R.string.scheduled_task_miss_skip)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormItem(
            label = { Text(stringResource(R.string.scheduled_task_idle_only)) },
            description = { Text(stringResource(R.string.scheduled_task_idle_only_desc)) },
            tail = {
                Switch(
                    checked = state.idleOnly,
                    onCheckedChange = { state.idleOnly = it },
                )
            },
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.runAtDate
                ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let { selected ->
                            state.runAtDate = Instant.ofEpochMilli(selected)
                                .atZone(ZoneId.of("UTC")).toLocalDate()
                        }
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }
}
