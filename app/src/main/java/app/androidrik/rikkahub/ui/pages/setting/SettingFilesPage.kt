package app.androidrik.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.ui.components.files.BatchRenameDialog
import app.androidrik.rikkahub.ui.components.files.RenameItem
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileProviderUri
import app.androidrik.rikkahub.utils.fileSizeToString
import app.androidrik.rikkahub.utils.shareFile
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File as JavaFile

@Composable
fun SettingFilesPage(filesManager: FilesManager = koinInject()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val folders = remember { listOf(FileFolders.UPLOAD, FileFolders.EXPORTS) }
    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var importFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<ManagedFileEntity>?>(null) }
    var pendingRename by remember { mutableStateOf<List<ManagedFileEntity>?>(null) }
    var report by remember { mutableStateOf<Pair<String, List<ManagedFileOperationResult>>?>(null) }
    val files by remember(selectedFolder) { filesManager.observe(selectedFolder) }.collectAsState(initial = emptyList())
    val selectedFiles = files.filter { it.id in selectedIds }
    val enabled = !busy && !picking

    fun clearSelection() { selectedIds = emptySet(); selectionMode = false }
    fun toggle(file: ManagedFileEntity) {
        selectionMode = true
        selectedIds = if (file.id in selectedIds) selectedIds - file.id else selectedIds + file.id
    }
    fun operate(title: String, action: suspend () -> List<ManagedFileOperationResult>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val results = action()
                clearSelection()
                if (results.any { it.error != null }) report = title to results
                else toaster.show("$title：成功 ${results.size} 项")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                toaster.show("$title 失败：${failure.message}")
            } finally { busy = false }
        }
    }
    BackHandler(selectionMode && enabled) { clearSelection() }
    LaunchedEffect(files) { selectedIds = selectedIds.intersect(files.map { it.id }.toSet()) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picking = false
        val folder = importFolder.also { importFolder = null }
        if (uris.isNotEmpty() && folder != null) {
            operate("导入") { filesManager.importMany(folder, uris) }
        }
    }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${targets.size} 个文件？") },
            text = { Text("将永久删除所选文件（${targets.sumOf { it.sizeBytes }.fileSizeToString()}），无法撤销。\n聊天中引用这些文件的附件将无法打开；聊天消息本身不会删除。导出文件的留存副本也会被删除。") },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                pendingDelete = null
                operate("删除") { filesManager.deleteMany(targets) }
            }) { Text("删除 ${targets.size} 项") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    pendingRename?.let { targets ->
        BatchRenameDialog(
            items = targets.map { RenameItem(it.id.toString(), it.displayName) },
            onDismiss = { pendingRename = null },
            onConfirm = { names ->
                pendingRename = null
                operate("重命名") { filesManager.renameMany(targets, names) }
            },
        )
    }
    report?.let { (title, results) ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("$title：${results.count { it.error == null }} 成功，${results.count { it.error != null }} 失败") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                results.filter { it.error != null }.forEach { Text("${it.name}\n${it.error}", modifier = Modifier.padding(bottom = 12.dp)) }
            } },
            confirmButton = { TextButton(onClick = { report = null }) { Text("知道了") } },
        )
    }
    if (showRetentionDialog) {
        var daysText by remember(settings.exportRetentionDays) { mutableStateOf(settings.exportRetentionDays.toString()) }
        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            title = { Text(stringResource(R.string.setting_page_export_retention)) },
            text = { Column {
                Text(stringResource(R.string.setting_page_export_retention_desc))
                OutlinedTextField(value = daysText, onValueChange = { daysText = it.filter(Char::isDigit).take(4) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } },
            confirmButton = { TextButton(onClick = {
                val days = daysText.toIntOrNull() ?: DEFAULT_EXPORT_RETENTION_DAYS
                scope.launch { settingsStore.update { it.copy(exportRetentionDays = days.coerceIn(0, EXPORT_RETENTION_DAYS_MAX)) } }
                showRetentionDialog = false
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showRetentionDialog = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (selectionMode) "已选 ${selectedFiles.size} 项" else stringResource(R.string.setting_files_page_title)) },
                navigationIcon = {
                    if (selectionMode) IconButton(enabled = enabled, onClick = { clearSelection() }) { Icon(Lucide.X, "退出多选") }
                    else BackButton()
                },
                actions = {
                    if (selectionMode) {
                        TextButton(enabled = enabled && files.isNotEmpty(), onClick = {
                            selectedIds = if (selectedFiles.size == files.size) emptySet() else files.map { it.id }.toSet()
                        }) { Text(if (selectedFiles.size == files.size && files.isNotEmpty()) "取消全选" else "全选") }
                    } else {
                        IconButton(enabled = enabled, onClick = { showRetentionDialog = true }) { Icon(Lucide.Timer, "导出留存设置") }
                        IconButton(enabled = enabled && files.isNotEmpty(), onClick = { selectionMode = true }) { Icon(Lucide.ListChecks, "选择文件") }
                    }
                }, scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { folder ->
                    FilterChip(selected = folder == selectedFolder, enabled = enabled,
                        onClick = { if (selectedFolder != folder) { clearSelection(); selectedFolder = folder } },
                        label = { Text(if (folder == FileFolders.UPLOAD) stringResource(R.string.setting_files_page_folder_upload) else stringResource(R.string.setting_files_page_folder_exports)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    TextButton(enabled = enabled && selectedFiles.isNotEmpty(), onClick = { pendingRename = selectedFiles.toList() }) { Icon(Lucide.Pencil, null); Text("重命名") }
                    TextButton(enabled = enabled && selectedFiles.isNotEmpty(), onClick = { pendingDelete = selectedFiles.toList() }) { Icon(Lucide.Trash2, null); Text("删除 (${selectedFiles.size})") }
                } else {
                    TextButton(enabled = enabled, onClick = {
                        importFolder = selectedFolder
                        picking = true
                        try { importLauncher.launch(arrayOf("*/*")) }
                        catch (failure: Exception) {
                            picking = false
                            importFolder = null
                            toaster.show("无法打开文件选择器：${failure.message}")
                        }
                    }) { Icon(Lucide.Import, null); Text("批量导入") }
                    TextButton(enabled = enabled && files.isNotEmpty(), onClick = { pendingDelete = files.toList() }) { Icon(Lucide.Trash2, null); Text("清空当前文件夹") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (selectedFolder == FileFolders.EXPORTS) R.string.setting_files_page_exports_empty_hint else R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp), verticalItemSpacing = 8.dp, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.id }) { file ->
                        FileItem(file, filesManager.getFile(file), selectionMode, file.id in selectedIds, enabled,
                            onToggle = { toggle(file) }, onRename = { pendingRename = listOf(file) },
                            onDelete = { pendingDelete = listOf(file) }, onShare = {
                                try { context.shareFile(context.fileProviderUri(filesManager.getFile(file)), file.mimeType) }
                                catch (failure: Exception) { toaster.show("分享失败：${failure.message}") }
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileItem(
    file: ManagedFileEntity, fileOnDisk: JavaFile, selectionMode: Boolean, selected: Boolean, enabled: Boolean,
    onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = enabled,
            onClick = { if (selectionMode) onToggle() else onShare() }, onLongClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else CustomColors.listItemColors.containerColor),
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (file.mimeType.startsWith("image/")) AsyncImage(model = fileOnDisk, contentDescription = file.displayName,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) { Icon(Lucide.File, null) }
            if (selectionMode) Checkbox(checked = selected, enabled = enabled, onCheckedChange = { onToggle() }, modifier = Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(file.mimeType, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.sizeBytes.fileSizeToString(), style = MaterialTheme.typography.bodySmall)
        }
        if (!selectionMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(enabled = enabled, onClick = onRename) { Icon(Lucide.Pencil, "重命名") }
            IconButton(enabled = enabled, onClick = onShare) { Icon(Lucide.Share2, "分享") }
            IconButton(enabled = enabled, onClick = onDelete) { Icon(Lucide.Trash2, "删除") }
        }
    }
}
