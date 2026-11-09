package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.semanticRole
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.export.messageExportDisplayConfig
import app.androidrik.rikkahub.data.export.resolveExportModelLabel
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.pages.setting.components.ExportDisplaySchemeSheet

/**
 * **按消息覆盖导出显示**的多选面板（PNG / Markdown / HTML 的标签）。
 *
 * 语义：`messageId → ExportDisplayConfig` 的整张 map 由父级持有并持久化（单列整列替换），
 * 优先级 消息覆盖 > 会话覆盖 > 全局。多选一次设置/清除，写库是**原子**的。
 *
 * 只列 assistant 消息：其余角色在三种导出里都不渲染标签，列出来只会是无效开关。
 * 行内文案直接显示「当前生效结果」，而不是抽象的模式名——用户关心的是导出来长什么样。
 *
 * TextField 宿主规则：本面板自身没有输入框（只有勾选行）；唯一带 TextField 的是复用的
 * [ExportDisplaySchemeSheet]（独立 bottom sheet，`Column+verticalScroll+imePadding`），合规。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportMessageOverrideSheet(
    visible: Boolean,
    conversation: Conversation,
    /** 当前分支的消息（与导出默认消息集合一致）。 */
    messages: List<UIMessage>,
    settings: Settings,
    assistantName: String?,
    /** 会话级已决议的配置（消息未覆盖时「跟随会话」的文案来源）。 */
    baseDisplayConfig: ExportDisplayConfig,
    /** 整张 map 替换（空 map = 全部清除）。 */
    onOverridesChange: (Map<String, ExportDisplayConfig>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editorVisible by remember { mutableStateOf(false) }
    val overrides = conversation.exportDisplayOverrides
    val candidates = remember(messages) {
        messages.filter { it.semanticRole == MessageRole.ASSISTANT }
    }
    // 每行的「当前生效文案」一次算好：滚动时不再重复解析
    val labelById = remember(candidates, overrides, baseDisplayConfig, settings.providers, assistantName) {
        candidates.associate { message ->
            val messageId = message.id.toString()
            val override = overrides[messageId]
            val config = messageExportDisplayConfig(baseDisplayConfig, override)
            messageId to resolveExportModelLabel(
                message = message,
                providers = settings.providers,
                config = config,
                assistantName = assistantName,
            ).label
        }
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_export_message_override_list_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.chat_page_export_message_override_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { selectedIds = candidates.map { it.id.toString() }.toSet() },
                        enabled = candidates.isNotEmpty() && selectedIds.size < candidates.size,
                    ) {
                        Text(stringResource(R.string.chat_page_export_message_override_select_all))
                    }
                    TextButton(
                        onClick = { selectedIds = emptySet() },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.chat_page_export_message_override_clear_selection))
                    }
                }
            }

            HorizontalDivider()

            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_page_export_message_override_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(candidates, key = { it.id.toString() }) { message ->
                        val messageId = message.id.toString()
                        val override = overrides[messageId]
                        val checked = messageId in selectedIds
                        val label = labelById[messageId].orEmpty()
                            .ifBlank { stringResource(R.string.setting_display_page_export_model_preview_hidden) }
                        ListItem(
                            modifier = Modifier.clickable {
                                selectedIds = if (checked) selectedIds - messageId else selectedIds + messageId
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedIds = if (checked) selectedIds - messageId else selectedIds + messageId
                                    },
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = message.previewSnippet(),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            // ListItem.supportingContent 只渲染第一个直接子节点 ⇒ 单 Text，不要再平级加内容
                            supportingContent = {
                                Text(
                                    text = if (override != null) {
                                        stringResource(
                                            R.string.chat_page_export_message_override_override,
                                            label,
                                        )
                                    } else {
                                        stringResource(
                                            R.string.chat_page_export_message_override_follow_session,
                                            label,
                                        )
                                    },
                                    color = if (override != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.chat_page_export_message_override_selected,
                        selectedIds.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        onOverridesChange(overrides - selectedIds)
                        selectedIds = emptySet()
                    },
                    enabled = selectedIds.any { overrides.containsKey(it) },
                ) {
                    Text(stringResource(R.string.chat_page_export_message_override_clear_selected))
                }
                Button(
                    onClick = { editorVisible = true },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.chat_page_export_message_override_apply))
                }
            }
        }
    }

    if (editorVisible && selectedIds.isNotEmpty()) {
        // 多选设置：全部已选消息都用同一个覆盖时以它为起点；混合配置则从会话级起步
        // （用户此刻是在给这批消息「重设」，从会话默认值开始更可预期）
        val initialConfig = selectedIds.mapNotNull { overrides[it] }.distinct().singleOrNull()
            ?: baseDisplayConfig
        ExportDisplaySchemeSheet(
            config = initialConfig,
            titleOverride = stringResource(
                R.string.chat_page_export_message_override_editor_title,
                selectedIds.size,
            ),
            descriptionOverride = stringResource(R.string.chat_page_export_message_override_editor_desc),
            resetLabel = stringResource(R.string.chat_page_export_message_override_clear_selected),
            onSave = { config ->
                onOverridesChange(overrides + selectedIds.associateWith { config })
                selectedIds = emptySet()
                editorVisible = false
            },
            // 只有「选中里确实存在覆盖」才给清除入口（否则没有可清的）
            onReset = if (selectedIds.any { overrides.containsKey(it) }) {
                {
                    onOverridesChange(overrides - selectedIds)
                    selectedIds = emptySet()
                    editorVisible = false
                }
            } else {
                null
            },
            onDismiss = { editorVisible = false },
        )
    }
}

/** 行内消息摘要：取第一条文本内容，压成单行；纯图片/工具消息给占位。 */
private fun UIMessage.previewSnippet(): String {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString(" ") { it.text }
        .replace('\n', ' ')
        .trim()
    return text.take(120).ifBlank { "—" }
}
