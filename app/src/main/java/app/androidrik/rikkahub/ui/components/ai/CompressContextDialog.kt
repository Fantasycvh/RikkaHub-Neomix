package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import app.androidrik.rikkahub.data.ai.compression.AUTO_COMPRESS_RETAIN_PERCENT_RANGE
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_KEEP_RECENT_MESSAGES_RANGE
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS_RANGE
import app.androidrik.rikkahub.data.ai.compression.CompressPreset
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.RabbitLoadingIndicator

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, targetTokens: Int, retainRatio: Double, keepRecentMessages: Int) -> Job,
    /**
     * 初值：来自当前压缩设置（而不是每次重置为固定值）。
     *
     * 手动压缩**只暴露保留比例与摘要目标**——触发时机由用户自己判断（觉得长/快满了才点），
     * 故没有阈值参数。见 `docs/design/design-compress-presets.md` §四。
     */
    initialRetainPercent: Int = 40,
    initialTargetTokens: Int = 2000,
    /** 当前设置里的自定义档，供选择器一键填入（对话框内的改动**不持久化**）。 */
    customPresets: List<CompressPreset> = emptyList(),
) {
    var additionalPrompt by remember { mutableStateOf("") }
    // 初值读设置，而非硬编码——此前每次打开都重置为 2000/40，用户上次的选择不被记住
    var selectedTokens by remember { mutableIntStateOf(initialTargetTokens) }
    var selectedRetainPercent by remember { mutableIntStateOf(initialRetainPercent) }
    // 「保留最近 N 条」下限：默认 0 = 只按 token 比例切分（提示词缓存命中最好，与上游默认一致）
    var keepRecentMessages by remember { mutableIntStateOf(0) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // 压缩配置档：一键填入下面的两个数值（内置档 + 自定义档 + 自定义）。
                    // 这里的改动是**一次性**的，不写回设置（决策 2）。
                    CompressPresetSelector(
                        selectedRetainPercent = selectedRetainPercent,
                        selectedTargetTokens = selectedTokens,
                        customPresets = customPresets,
                        onApply = { retain, target ->
                            selectedRetainPercent = retain
                            selectedTokens = target
                        },
                    )

                    NumericDialogField(
                        label = stringResource(R.string.chat_page_compress_target_tokens),
                        value = selectedTokens,
                        range = COMPRESS_TARGET_TOKENS_RANGE,
                        onValueChange = { selectedTokens = it },
                    )
                    NumericDialogField(
                        label = stringResource(R.string.chat_page_compress_keep_recent),
                        value = selectedRetainPercent,
                        range = AUTO_COMPRESS_RETAIN_PERCENT_RANGE,
                        onValueChange = { selectedRetainPercent = it },
                    )
                    // 可选「保留最近 N 条」下限：token 预算太小、整段发不出去时，多留几条原文。
                    // 用步进器而非数字输入框（上游 71dd98f88）：不弹键盘、不会出现「负数显示与实际值不一致」
                    // 「清空后沿用旧值」「输入小数被忽略」这类输入框行为差异。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_keep_recent_count),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            onClick = { keepRecentMessages-- },
                            enabled = keepRecentMessages > COMPRESS_KEEP_RECENT_MESSAGES_RANGE.first,
                        ) {
                            Icon(Lucide.Minus, contentDescription = "Decrease")
                        }
                        Text(
                            text = keepRecentMessages.toString(),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 40.dp),
                        )
                        FilledTonalIconButton(
                            onClick = { keepRecentMessages++ },
                            enabled = keepRecentMessages < COMPRESS_KEEP_RECENT_MESSAGES_RANGE.last,
                        ) {
                            Icon(Lucide.Plus, contentDescription = "Increase")
                        }
                    }
                    Text(
                        text = stringResource(R.string.chat_page_compress_keep_recent_count_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    currentJob = onConfirm(
                        additionalPrompt,
                        selectedTokens,
                        selectedRetainPercent / 100.0,
                        keepRecentMessages,
                    )
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

/**
 * 对话框内的整数输入（初值来自设置，越界不提交）。
 *
 * 与设置页的数值项同口径：允许中间态（空串/超范围）不落值，解析成功且落在 [range] 内才提交。
 */
@Composable
private fun NumericDialogField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.trim().toIntOrNull()
    val invalid = text.isNotBlank() && (parsed == null || parsed !in range)

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.trim().toIntOrNull()?.let { if (it in range) onValueChange(it) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = invalid,
        supportingText = {
            Text(stringResource(R.string.compress_preset_range_hint, range.first, range.last))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
