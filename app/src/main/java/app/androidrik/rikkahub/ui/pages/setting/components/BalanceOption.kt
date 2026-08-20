package app.androidrik.rikkahub.ui.pages.setting.components

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Refresh03
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.androidrik.ai.provider.BalanceOption
import app.androidrik.ai.provider.BalanceSource
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.common.http.isJsonExprValid
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.DEFAULT_PROVIDERS
import app.androidrik.rikkahub.ui.theme.JetbrainsMono

private val ApiPathRegex = Regex("""^/[^ \t\n\r]*$""")

@Composable
fun SettingProviderBalanceOption(
    provider: ProviderSetting,
    balanceOption: BalanceOption,
    modifier: Modifier = Modifier,
    onEdit: (BalanceOption) -> Unit,
) {
    var expand by remember { mutableStateOf(false) }
    // 当前模式：sources 非空 = 多接口，否则单接口
    val isMultiMode = balanceOption.sources.isNotEmpty()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_balance_info),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    expand = !expand
                }
            ) {
                if (expand) {
                    Icon(
                        imageVector = HugeIcons.ArrowUp01,
                        contentDescription = null,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = null,
                    )
                }
            }
            Checkbox(
                checked = balanceOption.enabled,
                onCheckedChange = { onEdit(balanceOption.copy(enabled = it)) }
            )
        }
        AnimatedVisibility(visible = expand) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 模式切换
                Text(
                    text = stringResource(R.string.setting_provider_page_balance_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !isMultiMode,
                        onClick = {
                            // 切到单接口：清空 sources + expression
                            if (isMultiMode) {
                                onEdit(balanceOption.copy(sources = emptyList(), expression = ""))
                            }
                        },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_mode_single)) },
                    )
                    FilterChip(
                        selected = isMultiMode,
                        onClick = {
                            // 切到多接口：初始化一个空 source（若仍空）
                            if (!isMultiMode) {
                                onEdit(balanceOption.copy(
                                    sources = listOf(BalanceSource("var", "/api/path", "field")),
                                    expression = "var",
                                ))
                            }
                        },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_mode_multi)) },
                    )
                }

                if (isMultiMode) {
                    // 多接口模式：可编辑 sources 列表 + expression
                    balanceOption.sources.forEachIndexed { index, source ->
                        Surface(
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "#${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = {
                                            val updated = balanceOption.sources.toMutableList().also { it.removeAt(index) }
                                            onEdit(balanceOption.copy(sources = updated))
                                        }
                                    ) {
                                        Text(stringResource(R.string.setting_provider_page_balance_source_delete))
                                    }
                                }
                                OutlinedTextField(
                                    value = source.name,
                                    onValueChange = { newName ->
                                        val updated = balanceOption.sources.toMutableList()
                                        updated[index] = source.copy(name = newName)
                                        onEdit(balanceOption.copy(sources = updated))
                                    },
                                    label = { Text(stringResource(R.string.setting_provider_page_balance_source_name)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                                )
                                OutlinedTextField(
                                    value = source.apiPath,
                                    onValueChange = { newPath ->
                                        val updated = balanceOption.sources.toMutableList()
                                        updated[index] = source.copy(apiPath = newPath)
                                        onEdit(balanceOption.copy(sources = updated))
                                    },
                                    label = { Text(stringResource(R.string.setting_provider_page_balance_api_path)) },
                                    isError = !source.apiPath.matches(ApiPathRegex),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                                )
                                OutlinedTextField(
                                    value = source.resultPath,
                                    onValueChange = { newPath ->
                                        val updated = balanceOption.sources.toMutableList()
                                        updated[index] = source.copy(resultPath = newPath)
                                        onEdit(balanceOption.copy(sources = updated))
                                    },
                                    label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                                    isError = !isJsonExprValid(source.resultPath),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onEdit(balanceOption.copy(
                                sources = balanceOption.sources + BalanceSource("var${balanceOption.sources.size + 1}", "/api/path", "field")
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_provider_page_balance_source_add))
                    }
                    OutlinedTextField(
                        value = balanceOption.expression,
                        onValueChange = { onEdit(balanceOption.copy(expression = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_expression)) },
                        isError = !isJsonExprValid(balanceOption.expression),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
                    )
                } else {
                    // 单接口模式：原有字段
                    OutlinedTextField(
                        value = balanceOption.apiPath,
                        onValueChange = { onEdit(balanceOption.copy(apiPath = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_api_path)) },
                        isError = !balanceOption.apiPath.matches(ApiPathRegex),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = balanceOption.resultPath,
                        onValueChange = { onEdit(balanceOption.copy(resultPath = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                        isError = !isJsonExprValid(balanceOption.resultPath),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
                    )
                }
                IconButton(
                    onClick = {
                        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                        if (defaultProvider != null) {
                            onEdit(defaultProvider.balanceOption.copy())
                        } else {
                            onEdit(BalanceOption())
                        }
                    }
                ) {
                    Icon(HugeIcons.Refresh03, null)
                }
            }
        }
    }
}
