package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
internal fun ReasoningOverrideSettings(
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
                            if (effort == null && override?.budgetTokens == null) null
                            else ReasoningOverride(effort = effort, budgetTokens = override?.budgetTokens)
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
                            if (override?.effort == null && budget == null) null
                            else ReasoningOverride(effort = override?.effort, budgetTokens = budget)
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
