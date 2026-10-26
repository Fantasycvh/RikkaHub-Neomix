package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.BackupInspection
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.BackupSectionStats
import app.androidrik.rikkahub.utils.fileSizeToString

/**
 * 恢复预览对话框（design-backup-manifest-and-safety §3.5）：
 * 在覆盖确认之后、真正恢复之前，展示**这份备份文件里有什么**
 * （创建时间 / 各 section 条数与大小 / 是否旧格式）——用户确认的依据从
 * 「我勾了什么」升级为「这份文件实际包含什么」。
 *
 * v2 备份有 manifest（含校验和，恢复时会核对）；legacy 备份无 manifest，
 * 统计来自中央目录推断，并提示这一点。
 */
@Composable
fun BackupRestorePreviewDialog(
    inspection: BackupInspection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                inspection.manifest?.createdAt?.let { createdAt ->
                    Text(
                        text = stringResource(R.string.backup_restore_preview_created_at, createdAt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                sectionLabel(
                    BackupManifestSection.SETTINGS,
                    inspection.sections[BackupManifestSection.SETTINGS],
                )?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                sectionLabel(
                    BackupManifestSection.DATABASE,
                    inspection.sections[BackupManifestSection.DATABASE],
                )?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                sectionLabel(
                    BackupManifestSection.FILES,
                    inspection.sections[BackupManifestSection.FILES],
                )?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                sectionLabel(
                    BackupManifestSection.WORKSPACE_FILES,
                    inspection.sections[BackupManifestSection.WORKSPACE_FILES],
                )?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (inspection.manifest == null) {
                    Text(
                        text = stringResource(R.string.backup_restore_preview_legacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.backup_restore_preview_hint),
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun sectionLabel(
    section: BackupManifestSection,
    stats: BackupSectionStats?,
): String? {
    if (stats == null) return null
    val name = when (section) {
        BackupManifestSection.SETTINGS -> stringResource(R.string.backup_restore_preview_section_settings)
        BackupManifestSection.DATABASE -> stringResource(R.string.backup_restore_preview_section_database)
        BackupManifestSection.FILES -> stringResource(R.string.backup_restore_preview_section_files)
        BackupManifestSection.WORKSPACE_FILES -> stringResource(R.string.backup_restore_preview_section_workspace)
    }
    return "• $name: ${stringResource(
        R.string.backup_restore_preview_section_stats,
        stats.entries.toString(),
        stats.uncompressedBytes.toLong().fileSizeToString(),
    )}"
}
