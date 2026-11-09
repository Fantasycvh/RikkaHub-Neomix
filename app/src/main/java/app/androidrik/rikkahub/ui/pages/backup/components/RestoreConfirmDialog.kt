package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.BackupItem

/**
 * 恢复确认对话框（WebDAV / S3 / 本地三个入口共用）。
 *
 * **为何必要**：恢复会**覆盖**现有数据（对话记录是整库替换），且不可逆。用户容易误以为
 * 「恢复」只是补数据，或没意识到勾了「对话记录」= 覆盖全部聊天。此弹窗在恢复前明确列出
 * 将被覆盖的内容范围。
 *
 * @param items 本次恢复会处理的项（= 当前「备份内容」勾选）
 * @param databaseWarning 数据库跨版本恢复提示（null = 同版本）；旧版本快照按当前结构恢复，
 *   新增/改名字段无法自动迁移，必须让用户看到。
 */
@Composable
fun RestoreConfirmDialog(
    items: List<BackupItem>,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    databaseWarning: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.backup_page_restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TextButton(onClick = onConfirm) {
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
