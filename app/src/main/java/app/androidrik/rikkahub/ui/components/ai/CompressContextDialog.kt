package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import app.androidrik.rikkahub.data.ai.compression.AUTO_COMPRESS_RETAIN_PERCENT_RANGE
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_KEEP_RECENT_MESSAGES_RANGE
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS_RANGE
import app.androidrik.rikkahub.data.ai.compression.CompressPreset
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.compression.CompressionPreview
import app.androidrik.rikkahub.ui.components.ui.RabbitLoadingIndicator

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    /**
     * 执行手动压缩：ChatService 后台任务（design-manual-compress-background）——
     * 确认即返回并关闭弹窗，任务在聊天页输入框上方提示条反馈进度，不再弹窗内等待。
     */
    onConfirm: (
        additionalPrompt: String,
        targetTokens: Int,
        retainRatio: Double,
        keepRecentMessages: Int,
        continueInNewWindow: Boolean,
    ) -> Unit,
    /**
     * 阶段 2 的范围预览（#2294 主需求）：执行前先看「这次会压掉什么」。
     * 与执行共用同一段切分代码（ChatService.planCompressionWindow）；返回 null = 无可压缩内容。
     */
    onPreview: suspend (retainRatio: Double, keepRecentMessages: Int) -> CompressionPreview?,
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
    // 压缩后把整个窗口复制到新会话（旧会话保留）：长会话的过渡措施，默认关
    var continueInNewWindow by remember { mutableStateOf(false) }
    var previewStage by remember { mutableStateOf(false) }
    var previewLoading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<CompressionPreview?>(null) }
    val scope = rememberCoroutineScope()

    fun fetchPreview() {
        previewStage = true
        previewLoading = true
        scope.launch {
            preview = runCatching {
                onPreview(selectedRetainPercent / 100.0, keepRecentMessages)
            }.getOrElse { null }
            previewLoading = false
        }
    }

    fun backToConfig() {
        previewStage = false
        previewLoading = false
        preview = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            // verticalScroll + imePadding（AGENTS.md TextField Host Rules #3）：对话框窗口
            // 不与 IME insets 协调，preset 档 + 三个数字框 + 多行提示词在键盘弹起时被遮且
            // 无法滚入视口（d50d7aeaf 同款机制的现症）；内容需整体可滚 + 抬到键盘上方。
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (previewStage) {
                    // 阶段 2：范围预览确认（#2294 R1 主需求）
                    CompressionPreviewContent(
                        loading = previewLoading,
                        preview = preview,
                        keepRecentMessages = keepRecentMessages,
                    )
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
                    // 数字填写（原为步进器）：直接键入目标值；范围 0..999 从 0 起，
                    // 负数/非整数会被判 invalid、不落值（NumericDialogField 的中间态语义）。
                    NumericDialogField(
                        label = stringResource(R.string.chat_page_compress_keep_recent_count),
                        value = keepRecentMessages,
                        range = COMPRESS_KEEP_RECENT_MESSAGES_RANGE,
                        onValueChange = { keepRecentMessages = it },
                    )
                    Text(
                        text = stringResource(R.string.chat_page_compress_keep_recent_count_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 过渡措施：长会话压缩后窗口复制到新会话，旧会话原样保留为归档
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = continueInNewWindow,
                            onCheckedChange = { continueInNewWindow = it },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_continue_new_window),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.chat_page_compress_continue_new_window_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

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
            if (previewStage) {
                // 无可压缩（preview == null）时禁用执行——预览层已说明原因。
                // 确认即关（后台化）：任务转 ChatService 后台，进度在聊天页提示条反馈
                TextButton(
                    enabled = preview != null,
                    onClick = {
                        onConfirm(
                            additionalPrompt,
                            selectedTokens,
                            selectedRetainPercent / 100.0,
                            keepRecentMessages,
                            continueInNewWindow,
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.chat_page_compress_execute_action))
                }
            } else {
                // 阶段 1 → 阶段 2：先拉切分预览，确认后才真正执行（#2294 主需求）
                TextButton(onClick = { fetchPreview() }) {
                    Text(stringResource(R.string.chat_page_compress_preview_action))
                }
            }
        },
        dismissButton = {
            if (previewStage) {
                TextButton(onClick = { backToConfig() }) {
                    Text(stringResource(R.string.chat_page_compress_back_action))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

/**
 * 阶段 2 内容（#2294 R1）：条数/token 摘要 + 起止边界消息片段 + 快照提示。
 *
 * 片段用 `maxLines` 截断，**不嵌套滚动**——宿主 Column 已 `verticalScroll + imePadding`
 * （AGENTS TextField 宿主规则 #3：AlertDialog 不与 IME insets 协调）；展开查看原文
 * 交给现有「展开压缩历史」折叠行，这里只做范围确认。
 */
@Composable
private fun CompressionPreviewContent(
    loading: Boolean,
    preview: CompressionPreview?,
    keepRecentMessages: Int,
) {
    if (loading) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RabbitLoadingIndicator(modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.chat_page_compress_preview_loading))
        }
        return
    }
    val shown = preview
    if (shown == null) {
        // 无可压缩（非错误）：口径与执行路径 addError 的提示一致
        Text(
            text = stringResource(
                if (keepRecentMessages > 0) {
                    R.string.chat_page_compress_not_enough_messages_keep_recent
                } else {
                    R.string.chat_page_compress_not_enough_messages
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Text(
        text = stringResource(
            R.string.chat_page_compress_preview_summary,
            shown.toCompressCount,
            shown.estimatedTokens,
            shown.keptCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    PreviewSnippetCard(shown.firstSnippet)
    PreviewSnippetCard(shown.lastSnippet)
    Text(
        text = stringResource(R.string.chat_page_compress_preview_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreviewSnippetCard(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
