package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.rikkahub.R

/**
 * 提供商详情页的「自定义重试设置」分组。
 *
 * 三态语义：override = null 时跟随全局默认（Settings.retrySetting）；
 * 非 null 时该提供商完全使用自己的 enabled + delaysSeconds（可与全局相反）。
 */
@Composable
fun ProviderRetryOption(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val override = provider.retryOption
    var delaysText by remember(override) {
        mutableStateOf((override ?: RetrySetting()).delaysSeconds.joinToString(","))
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.setting_provider_page_retry_override))
                    Text(
                        stringResource(R.string.setting_provider_page_retry_override_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = override != null,
                    onCheckedChange = { enableOverride ->
                        onEdit(
                            if (enableOverride) {
                                provider.copyProvider(retryOption = override ?: RetrySetting())
                            } else {
                                provider.copyProvider(retryOption = null)
                            }
                        )
                    },
                )
            }

            // override 非空才展示独立配置编辑
            val current = override ?: return@Column
            OutlinedTextField(
                value = delaysText,
                onValueChange = {
                    delaysText = it
                    RetrySetting.parseDelays(it)?.let { delays ->
                        onEdit(provider.copyProvider(retryOption = current.copy(delaysSeconds = delays)))
                    }
                },
                label = { Text(stringResource(R.string.setting_retry_delays_title)) },
                supportingText = {
                    if (RetrySetting.parseDelays(delaysText) == null) {
                        Text(stringResource(R.string.setting_retry_delays_invalid))
                    } else if (current.delaysSeconds.isNotEmpty()) {
                        Text("${current.delaysSeconds.size} · ${current.delaysSeconds.sum()}s")
                    }
                },
                isError = RetrySetting.parseDelays(delaysText) == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.setting_retry_title))
                Switch(
                    checked = current.enabled,
                    onCheckedChange = { enabled ->
                        onEdit(provider.copyProvider(retryOption = current.copy(enabled = enabled)))
                    },
                )
            }
        }
    }
}
