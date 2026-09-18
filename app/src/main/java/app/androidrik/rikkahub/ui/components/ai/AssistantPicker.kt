package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.LookTop
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.filterForPicker
import app.androidrik.rikkahub.ui.components.ui.UIAvatar
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.hooks.rememberAssistantState
import kotlin.uuid.Uuid

@Composable
fun AssistantPicker(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    onClickSetting: () -> Unit,
) {
    val state = rememberAssistantState(settings, onUpdateSettings)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var showPicker by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        icon = {
            Icon(HugeIcons.LookTop, contentDescription = null)
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 当前助手可以是已停用的（停用只影响切换器隐藏，不解除解析）——标注以免「怎么不见了」困惑
                if (!state.currentAssistant.enabled) {
                    Text(
                        text = stringResource(R.string.assistant_disabled_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                UIAvatar(
                    name = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    value = state.currentAssistant.avatar,
                    onClick = onClickSetting
                )
            }
        },
        onClick = {
            showPicker = true
        },
        modifier = modifier,
        selected = false,
    )

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = state.currentAssistant,
            onAssistantSelected = { assistant ->
                showPicker = false
                state.setSelectAssistant(assistant)
            },
            onDismiss = {
                showPicker = false
            }
        )
    }
}

/**
 * 助手选择底部弹窗（**纯选择器**：只回调选中的助手，不写 Settings）。
 *
 * 提为 internal 以便**导入流程**复用（"导入到哪个助手"）——那里**不能**用外层
 * [AssistantPicker]，因为它的 `AssistantState.setSelectAssistant` 会写
 * `settings.assistantId`（改掉用户的当前助手），而导入只是选目标、不该有副作用。
 * 见 `docs/design-conversation-import-export.md` §4.2。
 *
 * @param showDisabled 已停用（归档）助手是否显示。主切换器默认**隐藏**；
 *   恢复模式（SafeMode）与导入目标等**管理动作**场景传 true——归档助手仍可被
 *   选为导入目标/在恢复模式里查看，只显示「已停用」标注。
 */
@Composable
internal fun AssistantPickerSheet(
    settings: Settings,
    currentAssistant: Assistant,
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit,
    showDisabled: Boolean = false,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }

    // 根据选中的标签过滤助手（主切换器场景额外隐藏已停用助手）——规则见 [Assistant].filterForPicker
    val filteredAssistants = remember(settings.assistants, selectedTagIds, showDisabled) {
        settings.assistants.filterForPicker(showDisabled = showDisabled, selectedTagIds = selectedTagIds)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 标签过滤器
            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(settings.assistantTags, key = { tag -> tag.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 助手列表
            val navController = LocalNavController.current
            if (filteredAssistants.isEmpty()) {
                // 全部助手都已停用（或标签过滤后为空）：给明确空态而不是白板
                Text(
                    text = stringResource(R.string.assistant_picker_empty_all_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == currentAssistant.id
                    Card(
                        onClick = { onAssistantSelected(assistant) },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        AssistantItem(
                            assistant = assistant,
                            defaultAssistantName = defaultAssistantName,
                            onEdit = {
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                    navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                                }
                            }
                        )                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    defaultAssistantName: String,
    onEdit: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = assistant.name.ifEmpty { defaultAssistantName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (!assistant.enabled) {
            // showDisabled 场景（导入目标/恢复模式）下标注归档状态，避免误选
            { Text(stringResource(R.string.assistant_disabled_badge)) }
        } else null,
        leadingContent = {
            UIAvatar(
                name = assistant.name.ifEmpty { defaultAssistantName },
                value = assistant.avatar,
                modifier = Modifier.size(32.dp)
            )
        },
        trailingContent = {
            IconButton(
                onClick = {
                    onEdit()
                }
            ) {
                Icon(
                    imageVector = HugeIcons.Edit03,
                    contentDescription = null
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
