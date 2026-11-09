package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.sync.DowngradeQuarantineStore
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份页常驻入口（design-db-downgrade-rebuild.md §5.3，2026-10-31 拍板：双入口）：
 * 列出全部降级隔离件，支持恢复（stage + 强制重启闸门）/忽略启动提示/删除腾空间。
 *
 * 无隔离件时整段不渲染。数据直接扫 `filesDir/downgrade_pending/`（不走 VM——
 * 与 [RestoreRestartGate] 读 `pending.marker` 同款轻量路径），操作经 [RikkaHubApp]
 * 的 suspend 方法在后台线程落盘。
 */
@Composable
fun DowngradeQuarantineSection() {
    val context = LocalContext.current
    val app = context.applicationContext as RikkaHubApp
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    var entries by remember { mutableStateOf<List<DowngradeQuarantineStore.QuarantineEntry>>(emptyList()) }
    var pendingRestore by remember { mutableStateOf<DowngradeQuarantineStore.QuarantineEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<DowngradeQuarantineStore.QuarantineEntry?>(null) }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { DowngradeQuarantineStore.listEntries(context.filesDir) }
    }

    if (entries.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.downgrade_quarantine_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        CardGroup {
            entries.forEach { entry ->
                val recoverable = entry.marker.schemaVersion in 1..AppDatabase.SCHEMA_VERSION
                val totalMb = (entry.marker.databaseSize + (entry.marker.walSize ?: 0) + (entry.marker.shmSize ?: 0)) / (1024 * 1024)
                item(
                    onClick = if (recoverable) ({ pendingRestore = entry }) else null,
                    headlineContent = {
                        Text(
                            stringResource(
                                R.string.downgrade_quarantine_backup_label,
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(entry.marker.quarantinedAt)),
                            ),
                        )
                    },
                    supportingContent = {
                        Text(
                            buildString {
                                append(stringResource(R.string.downgrade_quarantine_support_base, entry.marker.schemaVersion, totalMb))
                                when {
                                    !recoverable -> append(stringResource(R.string.downgrade_quarantine_need_upgrade, entry.marker.schemaVersion))
                                    entry.dismissed -> append(stringResource(R.string.downgrade_quarantine_dismissed_hint))
                                    else -> append(stringResource(R.string.downgrade_quarantine_tap_restore))
                                }
                            },
                        )
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            if (!entry.dismissed) {
                                TextButton(onClick = {
                                    scope.launch {
                                        app.dismissDowngradeEntry(entry).onFailure { e ->
                                            toaster.show(message = e.message ?: context.getString(R.string.error_title_operation))
                                        }
                                    }
                                }) { Text(stringResource(R.string.backup_page_reminder_dismiss)) }
                            }
                            TextButton(onClick = { pendingDelete = entry }) { Text(stringResource(R.string.common_delete)) }
                        }
                    },
                )
            }
        }
    }

    pendingRestore?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.downgrade_quarantine_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.downgrade_quarantine_restore_message,
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.marker.quarantinedAt)),
                        entry.marker.schemaVersion,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        app.restoreDowngradeEntry(entry).onFailure { e ->
                            toaster.show(message = e.message ?: context.getString(R.string.downgrade_quarantine_restore_failed))
                        }
                    }
                }) { Text(stringResource(R.string.downgrade_quarantine_restore_and_restart)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.downgrade_quarantine_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.downgrade_quarantine_delete_message,
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.marker.quarantinedAt)),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        app.deleteDowngradeEntry(entry).onFailure { e ->
                            toaster.show(message = e.message ?: context.getString(R.string.setting_files_page_delete_failed_toast))
                        }
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
