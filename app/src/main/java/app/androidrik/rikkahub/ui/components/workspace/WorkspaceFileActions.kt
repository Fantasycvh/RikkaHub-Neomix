package app.androidrik.rikkahub.ui.components.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.ui.components.files.BatchRenameDialog
import app.androidrik.rikkahub.ui.components.files.RenameItem
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceStorageArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every launcher/dialog captures its own location; subsequent navigation cannot retarget an operation. */
data class WorkspaceFileLocation(val id: String, val area: WorkspaceStorageArea, val path: String)

/** SAF 结果到达前 Activity/进程可能重建：目标三元组必须能从 saved state 恢复，否则所选文件被静默丢弃。 */
private val WorkspaceFileLocationSaver = Saver<WorkspaceFileLocation?, List<String>>(
    save = { it?.let { listOf(it.id, it.area.name, it.path) } ?: emptyList() },
    restore = {
        if (it.size < 3) null
        else WorkspaceFileLocation(it[0], WorkspaceStorageArea.valueOf(it[1]), it[2])
    },
)

class WorkspaceFileActions(
    val importFiles: () -> Unit,
    val rename: (List<WorkspaceFileEntry>) -> Unit,
    val busy: Boolean,
)

@Composable
fun rememberWorkspaceFileActions(
    location: WorkspaceFileLocation,
    repository: WorkspaceRepository,
    onChanged: () -> Unit,
): WorkspaceFileActions {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)
    var busy by remember { mutableStateOf(false) }
    // key = 标题，value = 逐项失败（路径: 原因）；toast 装不下也翻不回去，操作完必须有可回看的明细
    var report by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var importLocation by rememberSaveable(stateSaver = WorkspaceFileLocationSaver) { mutableStateOf(null) }
    var renameLocation by rememberSaveable(stateSaver = WorkspaceFileLocationSaver) { mutableStateOf(null) }
    var renameEntries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = importLocation.also { importLocation = null } ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                var succeeded = 0
                val failures = mutableListOf<String>()
                for (uri in uris) {
                    try {
                        withContext(Dispatchers.IO) {
                            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "imported_file"
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                repository.importFile(target.id, target.area, target.path, name, input)
                            } ?: error("无法读取文件")
                        }
                        succeeded++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures += "${uri.lastPathSegment}: ${error.message}"
                    }
                }
                changed()
                if (failures.isEmpty()) toaster.show("已导入 $succeeded 项")
                else report = "导入" to failures
            } finally { busy = false }
        }
    }
    if (renameEntries.isNotEmpty()) {
        BatchRenameDialog(
            items = renameEntries.map { RenameItem(it.path, it.name, it.isDirectory) },
            onDismiss = { renameEntries = emptyList(); renameLocation = null },
            onConfirm = { names ->
                val target = renameLocation
                val allowed = renameEntries.map { it.path }.toSet()
                renameEntries = emptyList()
                renameLocation = null
                if (target != null) {
                    busy = true
                    scope.launch {
                        try {
                            val requested = names.filterKeys { it in allowed }
                            val failures = repository.renameFiles(target.id, target.area, requested)
                            changed()
                            if (failures.isEmpty()) toaster.show("已重命名 ${requested.size} 项")
                            else report = "重命名" to failures
                        } finally { busy = false }
                    }
                }
            },
        )
    }
    report?.let { (title, failures) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { report = null },
            title = { androidx.compose.material3.Text("$title：${failures.size} 项失败") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    failures.forEach { androidx.compose.material3.Text(it) }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { report = null }) { androidx.compose.material3.Text("知道了") }
            },
        )
    }
    return WorkspaceFileActions(
        importFiles = { if (!busy) { importLocation = location; picker.launch(arrayOf("*/*")) } },
        rename = { entries ->
            when {
                busy -> Unit
                location.area == WorkspaceStorageArea.LINUX ->
                    toaster.show("Linux 区重命名暂不可用：无法安全维护 proot 链接元数据，请使用 Linux 终端。")
                else -> { renameLocation = location; renameEntries = entries.toList() }
            }
        },
        busy = busy,
    )
}
