package app.androidrik.rikkahub.ui.pages.chat

// 批次 7 拆出：WorkspaceFilesPanel 自 ChatDrawer.kt 拆出（同包，private→internal）

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import com.composables.icons.lucide.Import
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.workspace.WorkspaceFileEntry
import kotlinx.coroutines.launch

/**
 * 抽屉「文件」面板：工作区文件浏览（FILES 区）。
 * 目录导航 + 文件名搜索；点击目录进入，点击文件跳工作区文件详情页。
 * 列表数据来自 ChatDrawerVM（目录级缓存 + BFS 搜索）。
 */
@Composable
internal fun WorkspaceFilesPanel(
    workspaceId: String,
    files: List<WorkspaceFileEntry>,
    path: String,
    searchQuery: String,
    searchResults: List<WorkspaceFileEntry>?,
    onSearchChange: (String) -> Unit,
    onEnterDir: (String) -> Unit,
    onUpDir: () -> Unit,
    onChanged: () -> Unit,
    navController: Navigator,
    modifier: Modifier = Modifier,
) {
    val repository = org.koin.compose.koinInject<app.androidrik.rikkahub.data.repository.WorkspaceRepository>()
    val workspaceEntity by androidx.compose.runtime.produceState<app.androidrik.rikkahub.data.db.entity.WorkspaceEntity?>(
        initialValue = null,
        key1 = workspaceId,
    ) { value = repository.getById(workspaceId) }
    // 批量导出与重命名只支持本地工作区；远程工作区保留浏览、导入、打开与删除
    val localFiles = workspaceEntity?.capabilities()?.localFiles ?: true
    val location = app.androidrik.rikkahub.ui.components.workspace.WorkspaceFileLocation(
        workspaceId, app.androidrik.workspace.WorkspaceStorageArea.FILES, path,
    )
    val actions = app.androidrik.rikkahub.ui.components.workspace.rememberWorkspaceFileActions(location, repository, onChanged)
    val context = androidx.compose.ui.platform.LocalContext.current
    val toaster = app.androidrik.rikkahub.ui.context.LocalToaster.current
    val scope = rememberCoroutineScope()
    var selecting by remember(workspaceId, path, searchQuery) { mutableStateOf(false) }
    var selected by remember(workspaceId, path, searchQuery) { mutableStateOf(setOf<String>()) }
    var deleteEntries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var exportEntries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var exportPath by remember { mutableStateOf("") }
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val entries = exportEntries.also { exportEntries = emptyList() }
        val base = exportPath
        if (uri != null && entries.isNotEmpty()) scope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        repository.exportSelectionAsZip(workspaceId, app.androidrik.workspace.WorkspaceStorageArea.FILES, base, entries, output)
                    } ?: error(context.getString(R.string.chat_drawer_export_write_failed))
                }
                toaster.show(context.getString(R.string.chat_drawer_export_result, result.succeeded, result.skipped, result.failed))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { toaster.show(context.getString(R.string.chat_drawer_export_failed_with_reason, error.message)) }
        }
    }
    fun export(entries: List<WorkspaceFileEntry>) {
        exportEntries = entries.toList()
        // Search can include descendants; use the workspace root as the stable export base.
        exportPath = if (searchQuery.isBlank()) path else ""
        exportPicker.launch("workspace-files.zip")
    }
    val visible = if (searchQuery.isNotBlank()) searchResults.orEmpty() else files
    // 搜索结果可能同时含目录与其后代：删除折叠到最外层目录（递归删除会带上后代），
    // 重命名禁止祖先/后代同批（先改父目录会让子项旧路径失效，与预览不符）。
    val chosenPaths = visible.filter { it.path in selected }.map { it.path }
    val chosenForDelete = chosenPaths.filter { path ->
        chosenPaths.none { other -> path != other && path.startsWith("$other/") }
    }
    val chosenForRename = chosenForDelete.filter { path ->
        chosenPaths.none { other -> other != path && (path.startsWith("$other/") || other.startsWith("$path/")) }
    }
    val chosen = visible.filter { it.path in chosenForDelete.toSet() }
    if (deleteEntries.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteEntries = emptyList() },
            title = { Text(stringResource(R.string.chat_drawer_delete_title, deleteEntries.size)) },
            text = { Text(stringResource(R.string.chat_drawer_delete_unrecoverable)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val entries = deleteEntries.also { deleteEntries = emptyList() }
                    scope.launch {
                        val failed = mutableListOf<String>()
                        for (entry in entries) {
                            try {
                                check(repository.deleteFile(workspaceId, app.androidrik.workspace.WorkspaceStorageArea.FILES, entry.path, entry.isDirectory))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                            } catch (error: Exception) { failed += "${entry.path}: ${error.message}" }
                        }
                        selecting = false
                        onChanged()
                        toaster.show(
                            if (failed.isEmpty()) context.getString(R.string.chat_drawer_delete_result_ok, entries.size - failed.size)
                            else context.getString(R.string.chat_drawer_delete_result, entries.size - failed.size, failed.joinToString())
                        )
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleteEntries = emptyList() }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_drawer_files_search_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = actions.importFiles) {
                Icon(com.composables.icons.lucide.Lucide.Import, contentDescription = stringResource(R.string.workspace_detail_import_file))
            }
        }
        app.androidrik.rikkahub.ui.components.workspace.WorkspaceFileBrowser(
            entries = visible, path = path,
            area = app.androidrik.workspace.WorkspaceStorageArea.FILES,
            loading = false, error = null, compact = true, showAreaSelector = false,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            modifier = Modifier.weight(1f),
            selecting = selecting, selectedPaths = selected,
            allowExport = localFiles,
            onSelectToggle = { entry -> selected = if (entry.path in selected) selected - entry.path else selected + entry.path },
            onLongPress = if (localFiles) {
                { entry -> selecting = true; selected = selected + entry.path }
            } else null,
            onGoUp = onUpDir,
            onOpen = { entry ->
                if (entry.isDirectory) onEnterDir(entry.path)
                else navController.navigate(Screen.WorkspaceFileEditor(workspaceId, "FILES", entry.path))
            },
            onDelete = { deleteEntries = listOf(it) },
            onExport = { export(listOf(it)) },
            onRename = if (localFiles) {
                { actions.rename(listOf(it)) }
            } else null,
        )
        app.androidrik.rikkahub.ui.components.workspace.WorkspaceSelectionToolbar(
            selectedCount = chosen.size, visible = selecting,
            onCancel = { selecting = false; selected = emptySet() },
            onSelectAll = { selected = if (selected.size == visible.size) emptySet() else visible.map { it.path }.toSet() },
            onExport = { export(chosen) },
            onDelete = { deleteEntries = chosen.toList() },
            onRename = if (localFiles) {
                { actions.rename(chosenForRename.mapNotNull { p -> visible.firstOrNull { it.path == p } }); selecting = false }
            } else null,
        )
    }
}
