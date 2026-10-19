package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.FormItem

val MediaGenerationProviderSetting.typeName: String
    @Composable get() = when (this) {
        is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
        is MediaGenerationProviderSetting.Aliyun -> stringResource(R.string.media_provider_type_aliyun)
        is MediaGenerationProviderSetting.Volcengine -> stringResource(R.string.media_provider_type_volcengine)
        is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
        is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
        is MediaGenerationProviderSetting.SiliconFlow -> "SiliconFlow"
    }

val MediaKind.label: String
    @Composable get() = when (this) {
        MediaKind.IMAGE -> stringResource(R.string.media_kind_image)
        MediaKind.VIDEO -> stringResource(R.string.video)
    }

/**
 * 媒体生成服务商的配置表单。
 *
 * 与上游的差异：`CardGroupIcon` / `CardGroupValue` / `ConnectedChoiceButtons` 在本仓库不存在，
 * 这里用普通 `Text` / `FilterChip` 和 `FormItem` 做等价替代；本仓库的 `CardGroup.item` 没有内容槽位，
 * 表单字段因此放在一个普通 `Card` 里，而不是塞进 `ListItem`。
 */
@Composable
fun MediaGenerationProviderConfigure(
    setting: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit,
) {
    val models = setting.models
    // 按枚举顺序排列，保证按钮组的顺序稳定
    val kinds = MediaKind.entries.filter { it in setting.supportedKinds }

    fun updateModels(models: List<MediaGenerationModel>) {
        onValueChange(setting.copyProvider(models = models))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 类型在新建时选定，之后不能改
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_media_page_provider_type),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = setting.typeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.setting_media_page_provider_name)) },
                ) {
                    OutlinedTextField(
                        value = setting.name,
                        onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                FormItem(label = { Text("API Key") }) {
                    OutlinedTextField(
                        value = setting.apiKey,
                        onValueChange = { onValueChange(setting.copyProvider(apiKey = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                FormItem(label = { Text("Base URL") }) {
                    OutlinedTextField(
                        value = setting.baseUrl,
                        onValueChange = { onValueChange(setting.copyProvider(baseUrl = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                if (setting is MediaGenerationProviderSetting.Aliyun) {
                    FormItem(
                        label = { Text(stringResource(R.string.setting_media_page_workspace_id)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.setting_media_page_workspace_id_desc,
                                    MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER,
                                )
                            )
                        },
                    ) {
                        OutlinedTextField(
                            value = setting.workspaceId,
                            onValueChange = { onValueChange(setting.copy(workspaceId = it.trim())) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
        }

        CardGroup(
            title = { Text(stringResource(R.string.setting_media_page_models)) },
        ) {
            models.forEach { model ->
                item(
                    headlineContent = {
                        ModelRow(
                            model = model,
                            kinds = kinds,
                            onModelIdChange = { value ->
                                updateModels(
                                    models.map {
                                        if (it.id == model.id) model.copy(modelId = value.trim()) else it
                                    }
                                )
                            },
                            onKindChange = { kind ->
                                updateModels(
                                    models.map {
                                        if (it.id == model.id) model.copy(kind = kind) else it
                                    }
                                )
                            },
                            onDelete = { updateModels(models.filter { it.id != model.id }) },
                        )
                    },
                )
            }

            item(
                onClick = if (kinds.isNotEmpty()) {
                    { updateModels(models + MediaGenerationModel(modelId = "", kind = kinds.first())) }
                } else null,
                headlineContent = { Text(stringResource(R.string.setting_media_page_add_model)) },
                supportingContent = { Text(stringResource(R.string.setting_media_page_models_desc)) },
                leadingContent = { Icon(HugeIcons.Add01, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: MediaGenerationModel,
    kinds: List<MediaKind>,
    onModelIdChange: (String) -> Unit,
    onKindChange: (MediaKind) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = model.modelId,
                onValueChange = onModelIdChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.setting_media_page_model_id)) },
                singleLine = true,
            )
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, stringResource(R.string.delete))
            }
        }
        // 只支持一种类型的厂商不需要选择
        if (kinds.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { kind ->
                    FilterChip(
                        selected = model.kind == kind,
                        onClick = { onKindChange(kind) },
                        label = { Text(kind.label) },
                    )
                }
            }
        }
    }
}
