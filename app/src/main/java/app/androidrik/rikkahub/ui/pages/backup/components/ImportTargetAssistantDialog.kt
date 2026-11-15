package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
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
import app.androidrik.rikkahub.ui.pages.backup.DeepSeekRestoreResult
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
            // 导入目标是管理动作：已停用（归档）助手仍可作为目标，仅标注显示
            showDisabled = true,
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

/**
 * DeepSeek 官方导出包的导入确认：**目标助手 + 「保留搜索轨迹」开关**。
 *
 * 为什么不复用 [ImportTargetAssistantDialog]：这里多一个会改变导入内容形态的选项，且必须同时
 * 说明「物理上无法跨端还原」的部分（失败轮、READ_LINK 签名正文）——不说明的话，用户会把
 * 内容缺失当成导入 bug。
 *
 * 会话判重（`existsConversationById`）在导入过程中逐条进行，此处只能静态说明
 * 「已导入过的会自动跳过」，不谎称已检测。
 *
 * @param onConfirm 目标助手 id + 是否把搜索轨迹保留为纯文本（默认关）。
 */
@Composable
fun DeepSeekImportConfirmDialog(
    settings: Settings,
    onConfirm: (targetAssistantId: Uuid, keepSearchTrails: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentAssistant = settings.getCurrentAssistant()
    var selected by remember { mutableStateOf<Assistant>(currentAssistant) }
    var keepSearchTrails by remember { mutableStateOf(false) }
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
            showDisabled = true,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_import_deepseek_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.backup_page_import_deepseek_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                // 搜索轨迹：默认关（signature 无法还原正文，只留标题/链接的纯文本痕迹）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keepSearchTrails = !keepSearchTrails },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = keepSearchTrails,
                        onCheckedChange = { keepSearchTrails = it },
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.backup_page_import_deepseek_keep_search),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.backup_page_import_deepseek_keep_search_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.backup_page_import_deepseek_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.id, keepSearchTrails) }) {
                Text(stringResource(R.string.backup_page_import_deepseek_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * DeepSeek 导入完成摘要：导入/跳过/失败会话数 + 各类不可还原内容的丢弃计数。
 *
 * 只在「有内容被丢弃」或「有会话失败」时才值得单开弹窗；全量成功时调用方走普通 toast。
 */
@Composable
fun DeepSeekImportResultDialog(
    result: DeepSeekRestoreResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_import_deepseek_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        R.string.backup_page_import_deepseek_result_imported,
                        result.importedConversations,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.skippedExistingConversations > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_skipped,
                            result.skippedExistingConversations,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (result.failedSessions > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_failed,
                            result.failedSessions,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (result.failedInserts > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_insert_failed,
                            result.failedInserts,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (result.restoredImages > 0 || result.missingImages > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_images,
                            result.restoredImages,
                            result.missingImages,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (result.nonImageAttachments > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_non_image,
                            result.nonImageAttachments,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val dropped = result.droppedFailedReplies + result.droppedSearchTrails +
                    result.droppedTemplateResponses + result.droppedServiceTips
                if (dropped > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_dropped,
                            result.droppedFailedReplies,
                            result.droppedSearchTrails,
                            result.droppedTemplateResponses,
                            result.droppedServiceTips,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 物理上无法跨端还原的两项：必须显式报出来，否则用户会当成导入 bug
                if (result.droppedReadLinks > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_read_link,
                            result.droppedReadLinks,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.droppedTokenUsageMessages > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_page_import_deepseek_result_token_usage,
                            result.droppedTokenUsageMessages,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}
