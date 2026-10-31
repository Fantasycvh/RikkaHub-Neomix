package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            text = "降级数据恢复",
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
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(entry.marker.quarantinedAt)) + " 备份",
                        )
                    },
                    supportingContent = {
                        Text(
                            buildString {
                                append("schema v${entry.marker.schemaVersion} · 约 ${totalMb}MB")
                                when {
                                    !recoverable -> append(" · 需升级到 v${entry.marker.schemaVersion}+ 才能恢复")
                                    entry.dismissed -> append(" · 已忽略启动提示")
                                    else -> append(" · 点击恢复")
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
                                            toaster.show(message = e.message ?: "操作失败")
                                        }
                                    }
                                }) { Text("忽略") }
                            }
                            TextButton(onClick = { pendingDelete = entry }) { Text("删除") }
                        }
                    },
                )
            }
        }
    }

    pendingRestore?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复这份数据？") },
            text = {
                Text(
                    "恢复 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.marker.quarantinedAt))} " +
                        "备份的数据（schema v${entry.marker.schemaVersion}）。\n\n" +
                        "当前数据库将被完整备份后覆盖，重启后生效。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        app.restoreDowngradeEntry(entry).onFailure { e ->
                            toaster.show(message = e.message ?: "恢复失败")
                        }
                    }
                }) { Text("恢复并重启") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这份数据？") },
            text = {
                Text(
                    "删除 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.marker.quarantinedAt))} " +
                        "备份的降级前数据？删除后无法找回。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        app.deleteDowngradeEntry(entry).onFailure { e ->
                            toaster.show(message = e.message ?: "删除失败")
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
