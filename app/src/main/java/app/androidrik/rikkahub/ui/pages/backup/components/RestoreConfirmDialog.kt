package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import app.androidrik.rikkahub.data.sync.BackupCategory
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupScope
import app.androidrik.rikkahub.data.sync.RestoreMode

/**
 * 恢复确认对话框（WebDAV / S3 / 本地三个入口共用）。
 *
 * **为何必要**：恢复会**覆盖**现有数据（对话记录是整库替换），且不可逆。用户容易误以为
 * 「恢复」只是补数据，或没意识到勾了「对话记录」= 覆盖全部聊天。此弹窗在恢复前明确列出
 * 将被覆盖的内容范围，并提供恢复模式选择（借鉴 Kelivo 的 overwrite/merge 双模式）。
 *
 * @param items 本次恢复会处理的项（= 当前「备份内容」勾选）
 * @param scope 备份包记录的范围（manifest v3）；被排除的类别恢复时保留本机值。null = v2 旧包
 * @param databaseWarning 数据库跨版本恢复提示（null = 同版本）；旧版本快照按当前结构恢复，
 *   新增/改名字段无法自动迁移，必须让用户看到。
 * @param onConfirm 确认回调，携带用户选定的恢复模式
 */
@Composable
fun RestoreConfirmDialog(
    items: List<BackupItem>,
    onConfirm: (RestoreMode) -> Unit,
    onDismissRequest: () -> Unit,
    databaseWarning: String? = null,
    scope: BackupScope? = null,
) {
    var mode by remember { mutableStateOf(RestoreMode.OVERWRITE) }
    val hasDatabase = BackupItem.DATABASE in items
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.backup_page_restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 恢复模式（仅勾了数据库时可选——merge/overwrite 的差异只在数据库与 settings 合并）
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RestoreMode.entries.forEach { candidate ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mode == candidate,
                                onClick = { mode = candidate },
                                enabled = candidate == RestoreMode.OVERWRITE || hasDatabase,
                            )
                            Text(
                                text = restoreModeLabel(candidate),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(
                        when (mode) {
                            RestoreMode.OVERWRITE -> R.string.backup_page_restore_mode_overwrite_hint
                            RestoreMode.MERGE -> R.string.backup_page_restore_mode_merge_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.backup_page_restore_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                items.forEach { item ->
                    Text(
                        text = "• " + backupItemLabel(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 备份包未包含的 settings 类别（恢复时保留本机值）
                val excludedSettingsLabels = scope?.excluded
                    ?.filter { it == BackupCategory.ASSISTANTS || it == BackupCategory.PROVIDERS || it == BackupCategory.DISPLAY || it == BackupCategory.ABOUT }
                    ?.map { backupCategoryLabel(it) }
                    .orEmpty()
                if (excludedSettingsLabels.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_page_restore_scope_not_included) +
                            " " + excludedSettingsLabels.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                databaseWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.backup_page_restore_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode) }) {
                Text(
                    text = stringResource(R.string.backup_page_restore_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun restoreModeLabel(mode: RestoreMode): String = stringResource(
    when (mode) {
        RestoreMode.OVERWRITE -> R.string.backup_page_restore_mode_overwrite
        RestoreMode.MERGE -> R.string.backup_page_restore_mode_merge
    }
)
