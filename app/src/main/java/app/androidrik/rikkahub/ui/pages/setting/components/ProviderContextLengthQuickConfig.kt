package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.utils.formatContextLength
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.utils.parseContextLengthInput

/** Edits the provider page's local draft; its Save action persists the provider default. */
@Composable
internal fun ProviderContextLengthQuickConfig(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormItem(
                label = { Text("快捷配置 · 上下文窗口") },
                description = {
                    Text("统一设置该提供商下模型的默认窗口，模型单独设置优先。点击页面的保存按钮生效。")
                },
            )
            Text(
                text = when {
                    provider.contextLengthOverride != null -> "当前统一覆盖：${formatContextLength(provider.contextLengthOverride)}"
                    else -> "当前：跟随各模型内置或全局默认"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            key(provider.id) {
                ContextLengthQuickConfigEditor(
                    value = provider.contextLengthOverride,
                    applyLabel = "应用到提供商",
                    resetLabel = "跟随模型内置值",
                    applyOnEdit = true,
                    onApply = { tokens ->
                        onEdit(provider.copyProvider(contextLengthOverride = tokens))
                    },
                )
            }
        }
    }
}

@Composable
internal fun ModelContextLengthQuickConfigSheet(
    model: Model,
    globalDefault: Int?,
    providerDefault: Int?,
    onDismiss: () -> Unit,
    onApply: (Int?) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormItem(
                label = { Text("快捷配置 · 上下文窗口") },
                description = { Text(model.displayName) },
            )
            Text(
                "默认窗口：${formatContextLength(model.copy(customContextLength = null).effectiveContextLength(globalDefault, providerDefault))}",
                style = MaterialTheme.typography.bodySmall,
            )
            ContextLengthQuickConfigEditor(
                value = model.customContextLength,
                applyLabel = stringResource(R.string.confirm),
                resetLabel = "跟随提供商 / 模型默认",
                onApply = {
                    onApply(it)
                    onDismiss()
                },
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun ContextLengthQuickConfigEditor(
    value: Int?,
    applyLabel: String,
    resetLabel: String,
    onApply: (Int?) -> Unit,
    applyOnEdit: Boolean = false,
) {
    // The local draft owns editing; applying a value must not reformat active input.
    var contextLengthText by rememberSaveable { mutableStateOf(value?.toString() ?: "") }
    val parsed = parseContextLengthInput(contextLengthText)
    val invalid = contextLengthText.isNotBlank() && parsed == null

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(32_000, 64_000, 128_000, 256_000, 512_000, 1_000_000).forEach { tokens ->
            FilterChip(
                selected = value == tokens,
                onClick = {
                    contextLengthText = formatContextLength(tokens)
                    onApply(tokens)
                },
                label = { Text(formatContextLength(tokens)) },
            )
        }
    }
    OutlinedTextField(
        value = contextLengthText,
        onValueChange = { input ->
            contextLengthText = input
            if (applyOnEdit) {
                val tokens = parseContextLengthInput(input)
                if (input.isBlank() || tokens != null) onApply(tokens)
            }
        },
        label = { Text(stringResource(R.string.setting_provider_page_context_length)) },
        placeholder = { Text("例如 256k / 1M / 262144") },
        supportingText = {
            Text(
                when {
                    invalid -> "请输入 1 至 100000000 之间的 Token 数，支持 k / M"
                    applyOnEdit -> "留空可恢复默认值，点击页面保存后生效"
                    else -> "留空并确认可恢复默认值"
                },
            )
        },
        isError = invalid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!applyOnEdit) {
            TextButton(
                enabled = !invalid,
                onClick = { onApply(parsed) },
            ) {
                Text(applyLabel)
            }
        }
        TextButton(
            enabled = value != null,
            onClick = {
                contextLengthText = ""
                onApply(null)
            },
        ) {
            Text(resetLabel)
        }
    }
}
