package app.androidrik.rikkahub.ui.pages.backup.components

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
import app.androidrik.rikkahub.data.sync.BackupCategory
import app.androidrik.rikkahub.data.sync.BackupFileGroup
import app.androidrik.rikkahub.data.sync.BackupInspection
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.BackupScope
import app.androidrik.rikkahub.data.sync.BackupSectionStats
import app.androidrik.rikkahub.utils.fileSizeToString

/**
 * 恢复预览对话框（design-backup-manifest-and-safety §3.5）：
 * 在覆盖确认之后、真正恢复之前，展示**这份备份文件里有什么**
 * （创建时间 / 各 section 条数与大小 / 是否旧格式）——用户确认的依据从
 * 「我勾了什么」升级为「这份文件实际包含什么」。
 *
 * v4：包内包含的 settings 类别与文件分组逐项可取消勾选（**只能缩小不能扩大**——manifest
 * 排除的项不出现），确认时合成 scopeOverride（exclusion 与 manifest 并集）传给恢复 API；
 * 另可勾选「仅恢复对话对象」——只把备份库中的会话并入当前库，其余一律不动（不换库）。
 *
 * v2 备份有 manifest（含校验和，恢复时会核对）；legacy 备份无 manifest，
 * 统计来自中央目录推断，并提示这一点。
 */
@Composable
fun BackupRestorePreviewDialog(
    inspection: BackupInspection,
    onConfirm: (conversationsOnly: Boolean, scopeOverride: BackupScope?) -> Unit,
    onDismiss: () -> Unit,
) {
    var overrideScope by remember { mutableStateOf(BackupScope()) }
    var conversationsOnly by remember { mutableStateOf(false) }
    val scope = inspection.manifest?.scope
    val hasDatabase = inspection.sections.containsKey(BackupManifestSection.DATABASE)
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
                // 包内包含的 settings 类别/文件分组：可取消勾选以缩小恢复范围
                // （恢复排除 = manifest scope 排除 ∪ 本次取消勾选）
                val includedSettings = BackupCategory.entries.filter {
                    it.isSettingsCategory && (scope?.includes(it) ?: false)
                }
                val includedFileGroups = BackupFileGroup.entries.filter { scope?.includesFileGroup(it) ?: false }
                if (includedSettings.size + includedFileGroups.size > 1) {
                    Text(
                        text = stringResource(R.string.backup_page_restore_scope_trim_title),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    includedSettings.forEach { category ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = overrideScope.includes(category),
                                onCheckedChange = { checked ->
                                    overrideScope = overrideScope.withCategory(category, checked)
                                },
                            )
                            Text(
                                text = backupCategoryLabel(category),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    includedFileGroups.forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = overrideScope.includesFileGroup(group),
                                onCheckedChange = { checked ->
                                    overrideScope = overrideScope.withFileGroup(group, checked)
                                },
                            )
                            Text(
                                text = backupFileGroupLabel(group),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 备份包未包含的 settings 类别（恢复时保留本机值）
                val excludedSettingsLabels = scope?.excluded
                    ?.filter { it.isSettingsCategory }
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
                if (hasDatabase) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = conversationsOnly,
                            onCheckedChange = { checked -> conversationsOnly = checked },
                        )
                        Text(
                            text = stringResource(R.string.backup_page_restore_conversations_only),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (conversationsOnly) {
                        Text(
                            text = stringResource(R.string.backup_page_restore_conversations_only_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            TextButton(onClick = {
                val trimmed = overrideScope.takeIf {
                    it.excluded.isNotEmpty() || it.excludedFileGroups.isNotEmpty()
                }
                onConfirm(conversationsOnly, trimmed)
            }) {
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
