package app.androidrik.rikkahub.ui.pages.backup.tabs

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.data.sync.repo.BackupDestination
import app.androidrik.rikkahub.data.sync.repo.BackupSnapshotManifest
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.backup.BackupVM
import app.androidrik.rikkahub.ui.pages.backup.components.BackupItemsSelector
import app.androidrik.rikkahub.ui.pages.backup.components.BackupProgressRow
import app.androidrik.rikkahub.ui.pages.backup.components.RestoreConfirmDialog
import app.androidrik.rikkahub.utils.fileSizeToString
import com.dokar.sonner.ToastType

/** Explicit experimental entry; the existing ZIP backup buttons remain available on their tabs. */
@Composable
fun SnapshotBackupTab(vm: BackupVM) {
    val destination by vm.snapshotDestination.collectAsStateWithLifecycle()
    val target by vm.localBackupTarget.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val busy by vm.snapshotBusy.collectAsStateWithLifecycle()
    val plan by vm.snapshotPlan.collectAsStateWithLifecycle()
    val error by vm.snapshotError.collectAsStateWithLifecycle()
    val notice by vm.snapshotNotice.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var restoring by remember { mutableStateOf<BackupSnapshotManifest?>(null) }
    var deleting by remember { mutableStateOf<BackupSnapshotManifest?>(null) }
    val toaster = LocalToaster.current
    LaunchedEffect(Unit) { vm.loadSnapshots() }
    LaunchedEffect(notice) { notice?.let { toaster.show(it, type = ToastType.Success); vm.snapshotNotice.value = null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data -> data.data?.let { vm.attachBackupFolder(it, data.flags) } }
    }
    val enabled = !busy && plan == null
    val writable = destination != BackupDestination.LOCAL || target?.canWrite == true
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("增量备份（实验）", style = MaterialTheme.typography.titleMedium)
            Text("首次完整写入，之后只保存新增或变化的内容。数据库仍为完整快照；恢复会覆盖设置和所选数据。完整 ZIP 导入、导出仍在原入口。")
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupDestination.entries.forEach { item ->
                    OutlinedButton(onClick = { vm.selectSnapshotDestination(item) }, enabled = enabled && destination != item) {
                        Text(when (item) { BackupDestination.LOCAL -> "备份文件夹"; BackupDestination.WEBDAV -> "WebDAV"; BackupDestination.S3 -> "S3" })
                    }
                }
            }
        }
        item {
            if (destination == BackupDestination.LOCAL) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target?.name ?: "尚未选择备份文件夹")
                    if (target != null && !writable) Text("只读仓库：可以恢复；备份与删除需要重新授予写入权限。")
                    Text("请选择专用空文件夹或已有仓库。存储位置由文件提供方决定。迁移时请复制整个文件夹。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = enabled, onClick = {
                            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                        }) { Text(if (target == null) "选择备份文件夹" else "重新选择文件夹") }
                        if (target != null) TextButton(enabled = enabled, onClick = vm::detachBackupFolder) { Text("解除关联") }
                    }
                }
            } else Text("使用对应 WebDAV / S3 页中已保存的连接配置。")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabled) BackupItemsSelector(settings.backupItems, { vm.updateSettings(settings.copy(backupItems = it)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::prepareSnapshot, enabled = enabled && writable) { Text("扫描并预估") }
                    OutlinedButton(onClick = vm::loadSnapshots, enabled = enabled) { Text("刷新快照") }
                }
                if (busy) {
                    Text("处理中，请保持应用在前台")
                    BackupProgressRow(progress)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("历史备份会一直保留。手动删除恢复点不会立即释放全部占用空间。", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(snapshots, key = { it.snapshotId }) { snapshot ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(java.time.Instant.parse(snapshot.createdAt).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    Text("${snapshot.files.size + snapshot.dbObjects.size} 个对象 · ${snapshot.logicalBytes.fileSizeToString()}")
                    Text("设备 ${snapshot.deviceId.take(8)}", style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(enabled = enabled, onClick = { restoring = snapshot }) { Text("恢复") }
                        TextButton(enabled = enabled && writable, onClick = { deleting = snapshot }) { Text("删除快照") }
                    }
                }
            }
        }
        item { Text(" ") }
    }
    plan?.let { current ->
        AlertDialog(
            onDismissRequest = vm::cancelSnapshotPlan,
            title = { Text("确认增量备份") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("预计新增写入 ${current.uploadBytes.fileSizeToString()}，复用 ${current.reusedBytes.fileSizeToString()}")
                val databaseBytes = current.snapshot.files.singleOrNull { it.path == "rikka_hub.db" }?.size
                    ?: current.snapshot.dbObjects.takeIf { it.isNotEmpty() }?.sumOf { it.size }
                Text("数据库 ${databaseBytes?.fileSizeToString() ?: "未包含"}（对象原始大小）")
                Text("已固定本次内容。计划在 30 分钟后失效；取消不会提交到仓库。")
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = vm::commitSnapshot) { Text("开始备份") } },
            dismissButton = { TextButton(enabled = !busy, onClick = vm::cancelSnapshotPlan) { Text("取消") } },
        )
    }
    restoring?.let { snapshot ->
        RestoreConfirmDialog(snapshot.items, onConfirm = { restoring = null; vm.restoreSnapshot(snapshot) }, onDismissRequest = { restoring = null })
    }
    deleting?.let { snapshot ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除此快照？") },
            text = { Text("删除后不再列出该恢复点，部分备份文件仍会保留，不影响其他恢复点。") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteSnapshot(snapshot) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
