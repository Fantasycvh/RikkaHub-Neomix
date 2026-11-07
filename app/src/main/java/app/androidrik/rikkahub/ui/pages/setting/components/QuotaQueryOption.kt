package app.androidrik.rikkahub.ui.pages.setting.components

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
import androidx.compose.ui.unit.dp
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.QuotaQuery
import app.androidrik.ai.provider.QuotaQueryMethod
import app.androidrik.ai.provider.QuotaSource
import app.androidrik.common.http.isJsonExprValid
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.DEFAULT_PROVIDERS
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Refresh03

private val ApiPathRegex = Regex("""^(https?://|/)[^ \t\n\r]*$""")

/**
 * API Key 类提供商的额度查询编辑器（对齐上游 #2186 的 `QuotaQuery`，保留本仓多接口/表达式能力）。
 *
 * - 单接口模式：URL（支持 `{{baseUrl}}` / `{{apiKey}}` 模板）+ 结果 JSON 键
 * - 多接口模式：多个数据源（变量名 + 路径 + JSON 键）+ 计算表达式
 */
@Composable
fun SettingProviderQuotaQuery(
    provider: ProviderSetting,
    quotaQuery: QuotaQuery,
    modifier: Modifier = Modifier,
    onEdit: (QuotaQuery) -> Unit,
) {
    var expand by remember { mutableStateOf(false) }
    // 当前模式：sources 非空 = 多接口，否则单接口
    val isMultiMode = quotaQuery.sources.isNotEmpty()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_balance_info),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { expand = !expand }) {
                Icon(
                    imageVector = if (expand) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                )
            }
            Checkbox(
                checked = quotaQuery.enabled,
                onCheckedChange = { onEdit(quotaQuery.copy(enabled = it)) },
            )
        }
        AnimatedVisibility(visible = expand) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_balance_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isMultiMode,
                        onClick = {
                            if (isMultiMode) {
                                onEdit(quotaQuery.copy(sources = emptyList(), expression = ""))
                            }
                        },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_mode_single)) },
                    )
                    FilterChip(
                        selected = isMultiMode,
                        onClick = {
                            if (!isMultiMode) {
                                onEdit(
                                    quotaQuery.copy(
                                        sources = listOf(QuotaSource("var", "/api/path", "field")),
                                        expression = "var",
                                    )
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_mode_multi)) },
                    )
                }

                if (isMultiMode) {
                    quotaQuery.sources.forEachIndexed { index, source ->
                        Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
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
                                            val updated = quotaQuery.sources.toMutableList().also { it.removeAt(index) }
                                            onEdit(quotaQuery.copy(sources = updated))
                                        }
                                    ) {
                                        Text(stringResource(R.string.setting_provider_page_balance_source_delete))
                                    }
                                }
                                OutlinedTextField(
                                    value = source.name,
                                    onValueChange = { newName ->
                                        val updated = quotaQuery.sources.toMutableList()
                                        updated[index] = source.copy(name = newName)
                                        onEdit(quotaQuery.copy(sources = updated))
                                    },
                                    label = { Text(stringResource(R.string.setting_provider_page_balance_source_name)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                                )
                                OutlinedTextField(
                                    value = source.apiPath,
                                    onValueChange = { newPath ->
                                        val updated = quotaQuery.sources.toMutableList()
                                        updated[index] = source.copy(apiPath = newPath)
                                        onEdit(quotaQuery.copy(sources = updated))
                                    },
                                    label = { Text(stringResource(R.string.setting_provider_page_balance_api_path)) },
                                    isError = !source.apiPath.matches(ApiPathRegex),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                                )
                                OutlinedTextField(
                                    value = source.resultPath,
                                    onValueChange = { newPath ->
                                        val updated = quotaQuery.sources.toMutableList()
                                        updated[index] = source.copy(resultPath = newPath)
                                        onEdit(quotaQuery.copy(sources = updated))
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
                            onEdit(
                                quotaQuery.copy(
                                    sources = quotaQuery.sources + QuotaSource(
                                        "var${quotaQuery.sources.size + 1}",
                                        "/api/path",
                                        "field",
                                    )
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_provider_page_balance_source_add))
                    }
                    OutlinedTextField(
                        value = quotaQuery.expression,
                        onValueChange = { onEdit(quotaQuery.copy(expression = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_expression)) },
                        isError = !isJsonExprValid(quotaQuery.expression),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    )
                } else {
                    // 请求方法（上游 6328f4a8d）：部分中转站余额接口要 POST 带 JSON body
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = quotaQuery.method == QuotaQueryMethod.GET,
                            onClick = { onEdit(quotaQuery.copy(method = QuotaQueryMethod.GET)) },
                            label = { Text("GET") },
                        )
                        FilterChip(
                            selected = quotaQuery.method == QuotaQueryMethod.POST,
                            onClick = { onEdit(quotaQuery.copy(method = QuotaQueryMethod.POST)) },
                            label = { Text("POST") },
                        )
                    }
                    OutlinedTextField(
                        value = quotaQuery.url,
                        onValueChange = { onEdit(quotaQuery.copy(url = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_quota_url)) },
                        isError = !quotaQuery.url.matches(ApiPathRegex),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    )
                    if (quotaQuery.method == QuotaQueryMethod.POST) {
                        OutlinedTextField(
                            value = quotaQuery.body,
                            onValueChange = { onEdit(quotaQuery.copy(body = it)) },
                            label = { Text(stringResource(R.string.setting_provider_page_quota_body)) },
                            supportingText = {
                                Text(stringResource(R.string.setting_provider_page_quota_body_hint))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                        )
                    }
                    OutlinedTextField(
                        value = quotaQuery.resultPath,
                        onValueChange = { onEdit(quotaQuery.copy(resultPath = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                        isError = !isJsonExprValid(quotaQuery.resultPath),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    )
                }
                IconButton(
                    onClick = {
                        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                        val resetQuery = (defaultProvider as? ProviderSetting.ApiKey)?.quotaQuery
                        onEdit(resetQuery ?: QuotaQuery())
                    }
                ) {
                    Icon(HugeIcons.Refresh03, contentDescription = null)
                }
            }
        }
    }
}
