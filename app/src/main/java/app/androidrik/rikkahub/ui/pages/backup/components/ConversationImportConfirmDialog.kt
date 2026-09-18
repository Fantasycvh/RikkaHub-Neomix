package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.ui.components.ai.AssistantPickerSheet
import app.androidrik.rikkahub.utils.toLocalDate
import java.time.Instant

/**
 * 「对话 JSON」导入确认弹窗：**选择目标助手** + **重复导入提示**。
 *
 * 两件事合在一个弹窗里（设计稿 §4.3/§十.3）：用户在按"确认"前就能看到会导到哪个助手、
 * 以及是否重复导入过——避免"导完才发现选错/重复"。
 *
 * - **目标助手**默认 = 当前助手（= 原有隐式行为，零回归）；用 [AssistantPickerSheet] 纯选择器
 *   （**不用**外层 `AssistantPicker`，后者会写 `settings.assistantId` 改掉用户的当前助手）。
 * - **重复检测**：`duplicateRecord` 非空时显示警告（原标题 + 导入日期），但**允许继续**
 *   （继续仍会导入，只是产生重复副本）。
 *
 * @param duplicateRecord 该文件此前导入过的记录（null = 未导入过 → 不显示警告）。
 */
@Composable
fun ConversationImportConfirmDialog(
    settings: Settings,
    conversationTitle: String,
    duplicateRecord: DuplicateImportRecord?,
    onConfirm: (targetAssistantId: kotlin.uuid.Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentAssistant = settings.getCurrentAssistant()
    var selected by remember { mutableStateOf<Assistant>(currentAssistant) }
    var showPicker by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.assistant_page_default_assistant)

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = selected,
            onAssistantSelected = {
                selected = it
                showPicker = false
            },
            onDismiss = { showPicker = false },
            // 导入目标是管理动作：已停用（归档）助手仍可作为目标，仅标注显示
            showDisabled = true,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_import_conversation_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.backup_page_import_conversation_confirm_message, conversationTitle),
                    style = MaterialTheme.typography.bodyMedium,
                )

                // 重复导入警告（允许继续）
                duplicateRecord?.let { record ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(
                                R.string.backup_page_import_duplicate_warning,
                                record.title?.takeIf { it.isNotBlank() } ?: conversationTitle,
                                formatImportDate(record.importedAt),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.backup_page_import_duplicate_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                // 目标助手选择（导入文件里的 assistantId 一律丢弃——跨设备必失效）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPicker = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.backup_page_import_target_assistant),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = selected.name.ifEmpty { defaultName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.id) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 重复导入记录（UI 层形态，避免把 Settings 内部类型带进组件签名）。 */
data class DuplicateImportRecord(
    val title: String?,
    val importedAt: Long,
)

/**
 * 导入日期：走 [Instant.toLocalDate]（**按系统语言取当地习惯格式**）——
 * 用户给的中文形态「yyyy年MM月dd日」正是 zh 下 MEDIUM 的产物，其它语言若照抄中文年号会读不通。
 */
private fun formatImportDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).toLocalDate()
