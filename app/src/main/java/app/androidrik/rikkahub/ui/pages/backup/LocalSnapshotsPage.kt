package app.androidrik.rikkahub.ui.pages.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.LocalSnapshotConfig
import app.androidrik.rikkahub.data.sync.BackupInspection
import app.androidrik.rikkahub.data.sync.BackupRunKind
import app.androidrik.rikkahub.data.sync.localsnapshot.LocalSnapshot
import app.androidrik.rikkahub.data.sync.localsnapshot.LocalSnapshotPermissionException
import app.androidrik.rikkahub.data.sync.localsnapshot.LocalSnapshotRetentionPolicy
import app.androidrik.rikkahub.data.sync.localsnapshot.LocalSnapshotService
import app.androidrik.rikkahub.data.sync.localsnapshot.LocalSnapshotState
import app.androidrik.rikkahub.data.sync.localsnapshot.LocalSnapshotStatus
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.backup.components.BackupProgressRow
import app.androidrik.rikkahub.ui.pages.backup.components.RestoreConfirmDialog
import app.androidrik.rikkahub.ui.pages.backup.components.RestoreRestartSignal
import app.androidrik.rikkahub.utils.fileSizeToString
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val MIB = 1024L * 1024

@Composable
internal fun LocalSnapshotsPage(vm: BackupVM = koinViewModel(), service: LocalSnapshotService = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val runtime by service.state.collectAsStateWithLifecycle(initialValue = LocalSnapshotState())
    val task by vm.taskState.collectAsStateWithLifecycle()
    val config = settings.localSnapshotConfig
    val treeUri = config.treeUri
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val toaster = LocalToaster.current
    var snapshots by remember(treeUri) { mutableStateOf(emptyList<LocalSnapshot>()) }
    var folderError by remember(treeUri) { mutableStateOf<Int?>(null) }
    var loading by remember(treeUri) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember(treeUri) { mutableStateOf<LocalSnapshot?>(null) }
    var pendingRestore by remember { mutableStateOf<Pair<File, BackupInspection>?>(null) }
    var pendingEncrypted by remember { mutableStateOf<File?>(null) }
    var editPassword by remember { mutableStateOf(false) }
    val idle = !settings.init && task == null && !working
    val canUseFolder = idle && !loading && treeUri != null && folderError == null

    DisposableEffect(Unit) {
        onDispose {
            pendingRestore?.first?.delete()
            pendingEncrypted?.delete()
        }
    }

    LaunchedEffect(treeUri, runtime.lastSuccessAt, refresh) {
        if (treeUri == null) return@LaunchedEffect
        loading = true
        try {
            snapshots = service.list(treeUri)
            folderError = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            folderError = if (failure is SecurityException || failure is LocalSnapshotPermissionException) {
                R.string.local_snapshot_permission_required
            } else R.string.local_snapshot_folder_read_failed
            snapshots = emptyList()
        } finally {
            loading = false
        }
    }

    fun action(restore: Boolean = false, work: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try {
                work()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                toaster.show(
                    resources.getString(
                        if (restore) R.string.backup_page_restore_failed else R.string.local_snapshot_operation_failed,
                        failure.localizedMessage.orEmpty(),
                    ),
                    type = ToastType.Error,
                )
            } finally {
                working = false
                refresh++
            }
        }
    }

    fun update(transform: (LocalSnapshotConfig) -> LocalSnapshotConfig) = vm.updateSettings {
        it.copy(localSnapshotConfig = transform(it.localSnapshotConfig))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = result.data
        val uri = intent?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) action { service.attach(uri, intent.flags) }
    }

    suspend fun preview(file: File, checkEncryption: Boolean = true) {
        try {
            if (checkEncryption && service.isEncryptedArchive(file)) pendingEncrypted = file
            else pendingRestore = file to vm.inspectLocalBackup(file)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { action(restore = true) { preview(service.stageImportedArchive(it)) } }
    }

    BackupDetailScaffold(stringResource(R.string.local_snapshot_title)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_enable)) },
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { enabled -> update { it.copy(enabled = enabled && it.treeUri != null) } },
                            enabled = idle && (treeUri != null || config.enabled),
                        )
                    },
                )
                item(
                    onClick = if (idle) ({
                        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                        ))
                    }) else null,
                    headlineContent = { Text(stringResource(R.string.local_snapshot_choose_folder)) },
                    supportingContent = {
                        Column {
                            treeUri?.let {
                                Text(DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':').substringAfterLast('/'))
                            }
                            folderError?.let { Text(stringResource(it)) }
                        }
                    },
                    trailingContent = if (treeUri != null) ({
                        TextButton(onClick = { action { service.detach() } }, enabled = idle) {
                            Text(stringResource(R.string.local_snapshot_detach_folder))
                        }
                    }) else null,
                )
                item(
                    onClick = if (idle) ({ editPassword = true }) else null,
                    headlineContent = { Text(stringResource(R.string.local_snapshot_encryption)) },
                    supportingContent = {
                        Text(stringResource(if (config.encryptedPassword == null) {
                            R.string.local_snapshot_plaintext
                        } else R.string.local_snapshot_encryption_on))
                    },
                )
                item(
                    onClick = if (idle) ({ archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) else null,
                    headlineContent = { Text(stringResource(R.string.local_snapshot_open_archive)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_interval)) },
                    trailingContent = {
                        SnapshotChoice(config.intervalDays, listOf(1, 2, 3, 5, 7, 14, 30), idle,
                            onSelect = { days -> update { it.copy(intervalDays = days) } },
                        ) { if (it == 1) stringResource(R.string.local_snapshot_daily) else stringResource(R.string.local_snapshot_days, it) }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_keep_recent)) },
                    trailingContent = {
                        SnapshotChoice(config.keepRecent, (1..10).toList(), idle,
                            onSelect = { count -> update { it.copy(keepRecent = count) } },
                        ) { it.toString() }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.local_snapshot_capacity)) },
                    trailingContent = {
                        SnapshotChoice(config.capacityBytes, listOf(0L, 256, 512, 1_024, 2_048, 5_120, 10_240).map { it * MIB }, idle,
                            onSelect = { bytes -> update { it.copy(capacityBytes = bytes) } },
                        ) { if (it == 0L) stringResource(R.string.local_snapshot_unlimited) else it.fileSizeToString() }
                    },
                )
                item(
                    headlineContent = {
                        TextButton(onClick = { action { service.runNow() } }, enabled = canUseFolder) {
                            Text(stringResource(R.string.backup_page_backup_now))
                        }
                    },
                    supportingContent = {
                        Column {
                            if (task?.kind == BackupRunKind.LOCAL_SNAPSHOT) BackupProgressRow(task?.progress)
                            if (runtime.treeUri == treeUri) {
                                runtime.status?.let { status -> Text(stringResource(status.message())) }
                                runtime.lastSuccessAt?.let {
                                    val date = DateFormat.getDateTimeInstance().format(Date(it))
                                    Text(stringResource(R.string.local_snapshot_last_success, date))
                                }
                            }
                        }
                    },
                )
            }

            if (treeUri != null && folderError == null && !loading) {
                val usage = snapshots.sumOf { it.bytes ?: 0L }
                val newest = LocalSnapshotRetentionPolicy.newestNonEmpty(snapshots)
                CardGroup(title = { Text(stringResource(R.string.local_snapshot_copies)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.local_snapshot_usage, usage.fileSizeToString())) },
                        supportingContent = {
                            Column {
                                if (config.capacityBytes > 0 && usage > config.capacityBytes) {
                                    Text(stringResource(R.string.local_snapshot_over_capacity))
                                }
                                if (snapshots.isEmpty()) Text(stringResource(R.string.local_snapshot_empty))
                            }
                        },
                    )
                    snapshots.forEach { snapshot ->
                        item(
                            headlineContent = { Text(snapshot.name) },
                            supportingContent = {
                                Column {
                                    snapshot.bytes?.let { Text(it.fileSizeToString()) }
                                    if (snapshot == newest) Text(stringResource(R.string.local_snapshot_protected))
                                    Row {
                                        TextButton(enabled = canUseFolder && snapshot.bytes != 0L, onClick = {
                                            action(restore = true) {
                                                preview(service.stageForRestore(treeUri, snapshot))
                                            }
                                        }) { Text(stringResource(R.string.backup_page_restore)) }
                                        TextButton(enabled = canUseFolder && snapshot != newest, onClick = { pendingDelete = snapshot }) {
                                            Text(stringResource(R.string.delete))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (editPassword) {
        SnapshotPasswordSheet(
            configuring = true,
            enabled = !working,
            onDismiss = { editPassword = false },
            onRemove = if (config.encryptedPassword != null) ({ action { service.setPassword(null); editPassword = false } }) else null,
            onSubmit = { password -> action { service.setPassword(password); editPassword = false } },
        )
    }
    pendingEncrypted?.let { encrypted ->
        SnapshotPasswordSheet(
            configuring = false,
            enabled = !working,
            onDismiss = { pendingEncrypted = null; encrypted.delete() },
            onSubmit = { password ->
                action(restore = true) {
                    preview(service.unlockArchive(encrypted, password), checkEncryption = false)
                    pendingEncrypted = null
                    encrypted.delete()
                }
            },
        )
    }

    pendingDelete?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.local_snapshot_delete_confirm, snapshot.name)) },
            confirmButton = {
                TextButton(enabled = canUseFolder, onClick = {
                    pendingDelete = null
                    if (treeUri != null) action { service.delete(treeUri, snapshot) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingRestore?.let { (file, inspection) ->
        RestoreConfirmDialog(
            items = inspection.manifest?.items ?: settings.backupItems,
            scope = inspection.manifest?.scope,
            onDismissRequest = {
                pendingRestore = null
                file.delete()
            },
            onConfirm = { mode, backupScope ->
                pendingRestore = null
                action(restore = true) {
                    val outcome = vm.restoreFromLocalFile(file, mode, backupScope)
                    toaster.show(resources.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                    if (outcome.settingsWarnings.isNotEmpty()) {
                        toaster.show(resources.getString(R.string.backup_page_restore_settings_dropped,
                            outcome.settingsWarnings.joinToString(", ")), type = ToastType.Warning)
                    }
                    if (outcome.mergedConversations > 0 || outcome.skippedConversations > 0) {
                        toaster.show(resources.getString(R.string.backup_page_restore_merge_result,
                            outcome.mergedConversations.toString(), outcome.skippedConversations.toString()), type = ToastType.Info)
                    }
                    RestoreRestartSignal.requestRestart()
                }
            },
        )
    }
}

@Composable
private fun <T> SnapshotChoice(value: T, options: List<T>, enabled: Boolean, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, enabled = enabled) { Text(label(value)) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
            DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                expanded = false
                onSelect(option)
            }, enabled = enabled)
        }
    }
}

private fun LocalSnapshotStatus.message(): Int = when (this) {
    LocalSnapshotStatus.SAVED -> R.string.local_snapshot_saved
    LocalSnapshotStatus.UNCHANGED -> R.string.local_snapshot_unchanged
    LocalSnapshotStatus.FAILED -> R.string.local_snapshot_failed
    LocalSnapshotStatus.PERMISSION_REQUIRED -> R.string.local_snapshot_permission_required
    LocalSnapshotStatus.CLEANUP_FAILED -> R.string.local_snapshot_cleanup_failed
}
