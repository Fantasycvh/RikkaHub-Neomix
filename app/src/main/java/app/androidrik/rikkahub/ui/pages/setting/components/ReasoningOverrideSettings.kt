package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChip
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedButton as OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.ReasoningPreset
import app.androidrik.rikkahub.ui.components.ai.label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.ReasoningOverride
import app.androidrik.rikkahub.R

/**
 * 思考强度映射覆盖的编辑表单（effort 文本 + budget 数值,均可留空跟随默认）。
 * 模型级（覆盖优先）与提供商级（兜底）共用,只差标题/描述文案。
 */
@Composable
private fun LegacyReasoningOverrideFields(
    title: String,
    description: String,
    override: ReasoningOverride?,
    onUpdate: (ReasoningOverride?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = override?.effort.orEmpty(),
                    onValueChange = { text ->
                        val effort = text.trim().ifBlank { null }
                        onUpdate(
                            if (effort == null && override?.budgetTokens == null && override?.presets.isNullOrEmpty()) null
                            else (override ?: ReasoningOverride()).copy(effort = effort)
                        )
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_reasoning_override_effort)) },
                    placeholder = { Text(stringResource(R.string.setting_provider_page_reasoning_override_follow)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = override?.budgetTokens?.toString().orEmpty(),
                    onValueChange = { input ->
                        // 只放行数字；清空 = 回落跟随默认。0 是合法值（部分私部署用它关预算）
                        val budget = input.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull()
                        onUpdate(
                            if (override?.effort == null && budget == null && override?.presets.isNullOrEmpty()) null
                            else (override ?: ReasoningOverride()).copy(budgetTokens = budget)
                        )
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_reasoning_override_budget)) },
                    placeholder = { Text(stringResource(R.string.setting_provider_page_reasoning_override_follow)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.setting_provider_page_reasoning_override_budget_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


/** Keep editable fields in a persistent scrolling sheet, even when the host uses lazy items. */
@Composable
internal fun ReasoningOverrideSettings(
    title: String,
    description: String,
    override: ReasoningOverride?,
    onUpdate: (ReasoningOverride?) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(ReasoningOverride()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            draft = override ?: ReasoningOverride()
            editing = true
        }) { Text("配置思考档位（${override?.presets?.size ?: 0} 个自定义）") }
    }
    if (editing) {
        ModalBottomSheet(onDismissRequest = { editing = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                LegacyReasoningOverrideFields(
                    title = "通用覆盖",
                    description = "保留原有配置；选择自定义档位时使用该档位自己的参数。",
                    override = draft,
                    onUpdate = { draft = it ?: ReasoningOverride() },
                )
                Text("自定义档位", style = MaterialTheme.typography.titleMedium)
                Text("每个档位独立保存名称、effort 和 token 预算。基础强度决定 API 的思考模式；参数留空则使用该基础强度的内置映射。模型与提供商的自定义档位均可在聊天中选择。")
                draft.presets.forEach { preset ->
                    key(preset.id) {
                        ReasoningPresetFields(
                            preset = preset,
                            onUpdate = { updated ->
                                draft = draft.copy(presets = draft.presets.map {
                                    if (it.id == updated.id) updated else it
                                })
                            },
                            onDelete = {
                                draft = draft.copy(presets = draft.presets.filterNot { it.id == preset.id })
                            },
                        )
                    }
                }
                OutlinedButton(onClick = {
                    draft = draft.copy(presets = draft.presets + ReasoningPreset(name = "自定义 ${draft.presets.size + 1}"))
                }) { Text("+ 添加档位") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { editing = false }) { Text("取消") }
                    TextButton(onClick = {
                        val saved = draft.copy(presets = draft.presets.map {
                            it.copy(name = it.name.trim(), effort = it.effort?.trim()?.ifBlank { null })
                        })
                        onUpdate(saved.takeUnless { it == ReasoningOverride() })
                        editing = false
                    }, enabled = draft.presets.all { it.name.isNotBlank() }) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ReasoningPresetFields(
    preset: ReasoningPreset,
    onUpdate: (ReasoningPreset) -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = preset.name,
                onValueChange = { onUpdate(preset.copy(name = it)) },
                label = { Text("档位名称") },
                isError = preset.name.isBlank(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("基础强度", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningLevel.entries.filter { it != ReasoningLevel.OFF && it != ReasoningLevel.AUTO }.forEach { level ->
                    FilterChip(selected = preset.level == level,
                        onClick = { onUpdate(preset.copy(level = level)) },
                        label = { Text(level.label()) })
                }
            }
            OutlinedTextField(
                value = preset.effort.orEmpty(),
                onValueChange = { onUpdate(preset.copy(effort = it.takeIf { value -> value.isNotBlank() })) },
                label = { Text("effort（如 low、high、max）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = preset.budgetTokens?.toString().orEmpty(),
                onValueChange = { input ->
                    if (input.isEmpty() || (input.all { it.isDigit() } && input.toIntOrNull() != null)) {
                        onUpdate(preset.copy(budgetTokens = input.toIntOrNull()))
                    }
                },
                label = { Text("Token 预算（留空使用内置映射）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDelete) { Text("删除档位") }
        }
    }
}
