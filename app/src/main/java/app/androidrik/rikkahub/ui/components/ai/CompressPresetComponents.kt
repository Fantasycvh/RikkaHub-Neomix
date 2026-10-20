package app.androidrik.rikkahub.ui.components.ai

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.compression.AUTO_COMPRESS_RETAIN_PERCENT_RANGE
import app.androidrik.rikkahub.data.ai.compression.BuiltInCompressPreset
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS_RANGE
import app.androidrik.rikkahub.data.ai.compression.CompressPreset
import app.androidrik.rikkahub.data.ai.compression.CompressPresetMatch
import app.androidrik.rikkahub.data.ai.compression.MAX_COMPRESS_PRESETS
import app.androidrik.rikkahub.data.ai.compression.isCompressPresetNameAvailable
import app.androidrik.rikkahub.data.ai.compression.matchCompressPreset

/** 内置档的本地化名称（UI 层映射，`CompressPreset` 本身不依赖 Android 资源）。 */
@Composable
fun builtInCompressPresetName(preset: BuiltInCompressPreset): String = stringResource(
    when (preset) {
        BuiltInCompressPreset.DEFAULT -> R.string.compress_preset_builtin_default
        BuiltInCompressPreset.COMPACT -> R.string.compress_preset_builtin_compact
        BuiltInCompressPreset.DETAILED -> R.string.compress_preset_builtin_detailed
    }
)

/** 全部内置档的本地化名称（用于重名校验，避免用户建出与内置档同名的档）。 */
@Composable
fun builtInCompressPresetNames(): List<String> =
    BuiltInCompressPreset.entries.map { builtInCompressPresetName(it) }

/**
 * 压缩配置档选择器：内置档 + 自定义档 + 「自定义」。
 *
 * 选中态由**数值反推**（[matchCompressPreset]），不存「当前选中档」——故用户改任一数值后
 * 高亮自动变为「自定义」，不会有状态与数值不同步的问题。
 */
@Composable
fun CompressPresetSelector(
    selectedRetainPercent: Int,
    selectedTargetTokens: Int,
    customPresets: List<CompressPreset>,
    onApply: (retainPercent: Int, targetTokens: Int) -> Unit,
    onManage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val match = remember(selectedRetainPercent, selectedTargetTokens, customPresets) {
        matchCompressPreset(selectedRetainPercent, selectedTargetTokens, customPresets)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.compress_preset_label),
                style = MaterialTheme.typography.labelMedium,
            )
            // 「自定义」是**状态指示**（当前数值不匹配任何档），不是动作——
            // 真相源是数值，点它没有任何值可改，故不做成可点击的 chip（死点击）。
            // 只有当用户手改数值脱离档位时这里才出现。
            if (match is CompressPresetMatch.Values) {
                Text(
                    text = " · " + stringResource(R.string.compress_preset_custom),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onManage != null) {
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.compress_preset_manage))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BuiltInCompressPreset.entries) { preset ->
                val selected = match is CompressPresetMatch.BuiltIn && match.preset == preset
                FilterChip(
                    selected = selected,
                    onClick = { onApply(preset.retainPercent, preset.targetTokens) },
                    label = { Text(builtInCompressPresetName(preset)) },
                )
            }
            items(customPresets) { preset ->
                val selected = match is CompressPresetMatch.Custom && match.preset == preset
                FilterChip(
                    selected = selected,
                    onClick = { onApply(preset.retainPercent, preset.targetTokens) },
                    label = { Text(preset.name) },
                )
            }
        }
    }
}

/**
 * 自定义档的增/改对话框。
 *
 * @param editingIndex 编辑既有档时传其下标（允许保留原名）；新建传 null。
 */
@Composable
fun CompressPresetEditDialog(
    initial: CompressPreset?,
    editingIndex: Int?,
    customPresets: List<CompressPreset>,
    builtInNames: Collection<String>,
    onDismiss: () -> Unit,
    onSave: (CompressPreset) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var retainText by remember { mutableStateOf((initial?.retainPercent ?: 40).toString()) }
    var targetText by remember { mutableStateOf((initial?.targetTokens ?: 2000).toString()) }

    val retainValue = retainText.trim().toIntOrNull()
    val targetValue = targetText.trim().toIntOrNull()
    val nameOk = isCompressPresetNameAvailable(name, customPresets, builtInNames, editingIndex)
    val retainOk = retainValue != null && retainValue in AUTO_COMPRESS_RETAIN_PERCENT_RANGE
    val targetOk = targetValue != null && targetValue in COMPRESS_TARGET_TOKENS_RANGE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.compress_preset_add else R.string.compress_preset_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.compress_preset_name)) },
                    singleLine = true,
                    isError = name.isNotBlank() && !nameOk,
                    supportingText = if (name.isNotBlank() && !nameOk) {
                        { Text(stringResource(R.string.compress_preset_name_taken)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = retainText,
                    onValueChange = { retainText = it },
                    label = { Text(stringResource(R.string.compress_preset_retain_short)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = retainText.isNotBlank() && !retainOk,
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.compress_preset_range_hint,
                                AUTO_COMPRESS_RETAIN_PERCENT_RANGE.first,
                                AUTO_COMPRESS_RETAIN_PERCENT_RANGE.last,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text(stringResource(R.string.compress_preset_target_short)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = targetText.isNotBlank() && !targetOk,
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.compress_preset_range_hint,
                                COMPRESS_TARGET_TOKENS_RANGE.first,
                                COMPRESS_TARGET_TOKENS_RANGE.last,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameOk && retainOk && targetOk,
                onClick = {
                    if (retainValue != null && targetValue != null) {
                        onSave(
                            CompressPreset(
                                name = name.trim(),
                                retainPercent = retainValue,
                                targetTokens = targetValue,
                            )
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 档位管理对话框：内置档（只读、不可删）+ 自定义档（可改/删）+ 新建入口。
 *
 * 删除是纯 UI 操作、**不改变当前压缩行为**（数值才是真相源）——见设计稿 §二决策 1。
 */
@Composable
fun CompressPresetManageDialog(
    customPresets: List<CompressPreset>,
    builtInNames: Collection<String>,
    onDismiss: () -> Unit,
    onSavePreset: (index: Int?, preset: CompressPreset) -> Unit,
    onDeletePreset: (Int) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    if (showAdd || editingIndex != null) {
        CompressPresetEditDialog(
            initial = editingIndex?.let { customPresets.getOrNull(it) },
            editingIndex = editingIndex,
            customPresets = customPresets,
            builtInNames = builtInNames,
            onDismiss = {
                showAdd = false
                editingIndex = null
            },
            onSave = { preset ->
                onSavePreset(editingIndex, preset)
                showAdd = false
                editingIndex = null
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_preset_manage)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 内置档：只读（不可删、不可改，始终留合理回退）
                BuiltInCompressPreset.entries.forEach { preset ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            builtInCompressPresetName(preset),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(
                                R.string.compress_preset_values,
                                preset.retainPercent,
                                preset.targetTokens,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (customPresets.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.compress_preset_custom_section),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                customPresets.forEachIndexed { index, preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingIndex = index }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(
                                    R.string.compress_preset_values,
                                    preset.retainPercent,
                                    preset.targetTokens,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDeletePreset(index) }) {
                            Icon(
                                HugeIcons.Delete02,
                                contentDescription = stringResource(R.string.compress_preset_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (customPresets.size >= MAX_COMPRESS_PRESETS) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.compress_preset_limit, MAX_COMPRESS_PRESETS),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            showAdd = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.compress_preset_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}
