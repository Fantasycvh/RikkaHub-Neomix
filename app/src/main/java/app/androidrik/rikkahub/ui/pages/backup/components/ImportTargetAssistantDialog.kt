package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import kotlin.uuid.Uuid

/**
 * 导入前的**目标助手选择**（不带其它信息的纯选择弹窗）。
 *
 * 用于 **Chatbox 导入**：它会导入多个会话，这些会话必须归属到某个助手。
 * 此前隐式为「当前助手」（`settings.value.assistantId`），用户既看不到也无从更改；
 * 与自家格式导入「让用户选助手」的做法不一致（见设计稿 §八）。
 *
 * 与 [ConversationImportConfirmDialog] 的区别：后者把"重复导入警告 + 选助手"合并在一个弹窗里
 * （因为那时两者都是必须展示的信息）；Chatbox 没有"单个来源可判重的锚点"（一个文件含多个会话），
 * 故这里只需要选助手。**不合并**是为避免两个弹窗各自长出一堆可选字段。
 *
 * 默认值 = 当前助手（= 原有隐式行为，零回归）。
 *
 * @param onConfirm 用户确认的目标助手 id。
 */
@Composable
fun ImportTargetAssistantDialog(
    settings: Settings,
    onConfirm: (targetAssistantId: Uuid) -> Unit,
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
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_import_target_assistant_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.backup_page_import_target_assistant_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 目标助手选择（用纯选择器：不写 settings.assistantId）
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
