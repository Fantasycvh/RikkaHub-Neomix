package app.androidrik.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.documentfile.provider.DocumentFile
import app.androidrik.rikkahub.data.export.WorkspaceBatchResult
import app.androidrik.rikkahub.data.export.WorkspaceExportNames
import app.androidrik.rikkahub.ui.components.workspace.WorkspaceSelectionToolbar
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.utils.fileSizeToString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.ImagePreviewDialog
import app.androidrik.rikkahub.ui.components.workspace.WorkspaceFileBrowser
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileSizeToString
import app.androidrik.rikkahub.utils.plus
import app.androidrik.workspace.RootfsInstallProgress
import app.androidrik.workspace.RootfsInstallStage
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceShellStatus
import app.androidrik.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    // SAF launcher 回调里要用的"这次导哪些"（launcher 不能带参数，只能借状态传递）
    var pendingExportEntries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }

    // ---- 多选（设计 design-workspace-multiselect-export.md；选择态由页面持有、组件无状态）----
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedPaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showExportChooser by remember { mutableStateOf(false) }
    var showDeleteSelectionConfirm by remember { mutableStateOf(false) }
    var showRootfsExportNotice by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<BatchExportKind?>(null) }
    var pendingLaunch by remember { mutableStateOf<BatchExportKind?>(null) }
    var largeExportConfirm by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var exportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val selectedEntries = state.entries.filter { it.path in selectedPaths }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }

    fun finishSelection() {
        selecting = false
        selectedPaths = emptyList()
    }

    fun exportResultMessage(result: WorkspaceBatchResult?): String = when {
        result == null -> context.getString(R.string.workspace_export_failed)
        result.skipped == 0 && result.failed == 0 ->
            context.getString(R.string.workspace_export_done_ok, result.succeeded)

        else -> context.getString(
            R.string.workspace_export_done_partial,
            result.succeeded,
            result.skipped,
            result.failed,
        )
    }

    /**
     * 请求启动某个 SAF 流程。
     *
     * **只改状态、不直接调 launcher**：launcher 是后声明的（Kotlin 局部函数不能前向引用），
     * 而"大目录先确认"的路径需要先决定再启动——用状态把"该启哪个"传下去，
     * 由 launcher 声明之后的 [LaunchedEffect] 真正启动。
     */
    fun launchExport(kind: BatchExportKind) {
        pendingLaunch = kind
    }

    /** 开始导出（含"大目录先提示"这一步）。 */
    fun startExport(entries: List<WorkspaceFileEntry>, kind: BatchExportKind) {
        if (state.area == WorkspaceStorageArea.LINUX) {
            // rootfs 不进导出（用户决议 §六.4）：树可能数 GB、含特殊文件，且它在备份里本就排除
            showRootfsExportNotice = true
            return
        }
        pendingExportEntries = entries
        // 只有"含文件夹 / 选择很多"时才多走一次树做体量预览——小选择不必付这个成本
        if (entries.any { it.isDirectory } || entries.size > LARGE_SELECTION_HINT_COUNT) {
            scope.launch {
                val preview = vm.previewSelection(entries)
                if (preview != null && (preview.second > LARGE_EXPORT_BYTES || preview.first > LARGE_EXPORT_FILES)) {
                    pendingExport = kind
                    largeExportConfirm = preview
                } else {
                    launchExport(kind)
                }
            }
        } else {
            launchExport(kind)
        }
    }



    /**
     * 打包导出：`CreateDocument("application/zip")`。
     *
     * 与单文件导出的区别只在"写什么"——这里写的是 zip 流；**仍然不落临时文件**。
     */
    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val entries = pendingExportEntries
        pendingExport = null
        pendingExportEntries = emptyList()
        if (uri == null || entries.isEmpty()) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri)
        if (outputStream == null) {
            toaster.show(context.getString(R.string.workspace_export_failed))
            return@rememberLauncherForActivityResult
        }
        exportProgress = 0 to entries.size
        scope.launch {
            val result = vm.exportSelectionAsZip(entries, outputStream) { done, total ->
                exportProgress = done to total
            }
            exportProgress = null
            finishSelection()
            toaster.show(exportResultMessage(result))
        }
    }

    /**
     * 逐个导出到目录：`OpenDocumentTree`（全仓首次使用该 contract）。
     *
     * **刻意不 take persistable permission**：这是一次性写，不需要跨会话保留授权；
     * 持久化授权反而留下"用户看不见的长期写权限"。
     */
    val treeExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val entries = pendingExportEntries
        pendingExport = null
        pendingExportEntries = emptyList()
        if (uri == null || entries.isEmpty()) return@rememberLauncherForActivityResult
        val tree = DocumentFile.fromTreeUri(context, uri)
        if (tree == null) {
            toaster.show(context.getString(R.string.workspace_export_failed))
            return@rememberLauncherForActivityResult
        }
        exportProgress = 0 to entries.size
        scope.launch {
            val result = vm.exportSelectionToTree(
                entries = entries,
                tree = tree,
                openOutputStream = { documentUri -> context.contentResolver.openOutputStream(documentUri) },
            ) { done, total ->
                exportProgress = done to total
            }
            exportProgress = null
            finishSelection()
            toaster.show(exportResultMessage(result))
        }
    }

    // 真正启动 SAF 流程（放在两个 launcher 之后：局部函数不能前向引用）
    LaunchedEffect(pendingLaunch) {
        when (pendingLaunch) {
            null -> Unit
            BatchExportKind.ZIP -> {
                pendingLaunch = null
                zipExportLauncher.launch(
                    WorkspaceExportNames.defaultArchiveName(state.path, System.currentTimeMillis())
                )
            }

            BatchExportKind.TREE -> {
                pendingLaunch = null
                treeExportLauncher.launch(null)
            }
        }
    }

    // 选择态下返回键先退出选择（用最后注册的 BackHandler 抢占路径返回的语义）
    BackHandler(enabled = selecting) { finishSelection() }

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank() && !selecting) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            // 选择态下把底部导航整体换成选择工具条（不留两排、也不让用户切 tab 切丢选择）
            if (selecting && pagerState.currentPage == 1) {
                WorkspaceSelectionToolbar(
                    selectedCount = selectedEntries.size,
                    visible = true,
                    onCancel = { finishSelection() },
                    onSelectAll = {
                        selectedPaths = if (selectedPaths.size == state.entries.size) {
                            emptyList()
                        } else {
                            state.entries.map { it.path }
                        }
                    },
                    onExport = {
                        val entries = selectedEntries
                        if (entries.size == 1 && !entries.first().isDirectory) {
                            // 单个文件不打包：直接走既有的单文件导出（零新增概念）
                            showExportChooser = false
                            exportTarget = entries.first()
                            exportLauncher.launch(entries.first().name)
                        } else {
                            showExportChooser = true
                        }
                    },
                    onDelete = { showDeleteSelectionConfirm = true },
                    modifier = Modifier
                        .padding(bottom = 12.dp),
                )
            } else {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onToolApprovalChange = vm::setToolApproval,
                    onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                )

                1 -> WorkspaceFileBrowser(
                    entries = state.entries,
                    path = state.path,
                    area = state.area,
                    loading = state.loading,
                    error = state.error,
                    contentPadding = PaddingValues(),
                    onSelectArea = { area ->
                        // 切区等于换了一棵树：清空选择（跨目录/跨区多选不做，见设计 §五.3）
                        finishSelection()
                        vm.selectArea(area)
                    },
                    onGoUp = {
                        finishSelection()
                        vm.goUp()
                    },
                    selecting = selecting,
                    selectedPaths = selectedPaths.toSet(),
                    onSelectToggle = { entry ->
                        selectedPaths = if (entry.path in selectedPaths) {
                            selectedPaths - entry.path
                        } else {
                            selectedPaths + entry.path
                        }
                    },
                    onLongPress = { entry ->
                        // 长按是**快捷入口**：⋮ 菜单里也有等价项（隐藏手势需要可发现性，本仓有过教训）
                        if (!selecting) {
                            selecting = true
                            selectedPaths = listOf(entry.path)
                        } else {
                            selectedPaths = if (entry.path in selectedPaths) {
                                selectedPaths - entry.path
                            } else {
                                selectedPaths + entry.path
                            }
                        }
                    },
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            entry.name.substringAfterLast('.').equals("svg", ignoreCase = true) ->
                                navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                    // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                    onExportToDir = { entry, dir, onReady ->
                        vm.exportToCacheDir(entry, dir, onReady)
                    },
                )
            }
        }
    }

    if (showExportChooser) {
        AlertDialog(
            onDismissRequest = { showExportChooser = false },
            title = { Text(stringResource(R.string.workspace_export_choose_title, selectedEntries.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showExportChooser = false
                            startExport(selectedEntries, BatchExportKind.ZIP)
                        },
                    ) {
                        Text(stringResource(R.string.workspace_export_option_zip))
                    }
                    TextButton(
                        onClick = {
                            showExportChooser = false
                            startExport(selectedEntries, BatchExportKind.TREE)
                        },
                    ) {
                        Text(stringResource(R.string.workspace_export_option_tree))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportChooser = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    largeExportConfirm?.let { (files, bytes) ->
        AlertDialog(
            onDismissRequest = { largeExportConfirm = null; pendingExport = null },
            title = { Text(stringResource(R.string.workspace_export_large_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.workspace_export_large_message,
                        files,
                        bytes.fileSizeToString(),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val kind = pendingExport
                        largeExportConfirm = null
                        if (kind != null) launchExport(kind)
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { largeExportConfirm = null; pendingExport = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showRootfsExportNotice) {
        AlertDialog(
            onDismissRequest = { showRootfsExportNotice = false },
            title = { Text(stringResource(R.string.workspace_export_rootfs_title)) },
            text = { Text(stringResource(R.string.workspace_export_rootfs_message)) },
            confirmButton = {
                TextButton(onClick = { showRootfsExportNotice = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    if (showDeleteSelectionConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectionConfirm = false },
            title = {
                Text(stringResource(R.string.workspace_delete_selection_title, selectedEntries.size))
            },
            text = { Text(stringResource(R.string.workspace_delete_selection_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val entries = selectedEntries
                        showDeleteSelectionConfirm = false
                        scope.launch {
                            val failed = vm.deleteSelection(entries)
                            finishSelection()
                            if (failed.isEmpty()) {
                                toaster.show(
                                    context.getString(R.string.workspace_delete_selection_done, entries.size)
                                )
                            } else {
                                toaster.show(
                                    context.getString(
                                        R.string.workspace_delete_selection_partial,
                                        entries.size - failed.size,
                                        failed.size,
                                    )
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectionConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    exportProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.workspace_export_progress, done, total)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_detail_shell_compatibility),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.workspace_detail_shell_compatibility_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = workspace?.shellCompatibilityMode ?: false,
                            onCheckedChange = onShellCompatibilityModeChange,
                            enabled = workspace != null,
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
    "workspace_run_background" to stringResource(R.string.workspace_detail_tool_run_background),
    "workspace_task_status" to stringResource(R.string.workspace_detail_tool_task_status),
    "workspace_kill_task" to stringResource(R.string.workspace_detail_tool_kill_task),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

/** 批量导出的两种产物（用户决议 §6.1：打包作为**可选项**）。 */
private enum class BatchExportKind { ZIP, TREE }

/** 超过这两个阈值就先提示（设计 §五.5）：避免用户点完才发现要压几 GB。 */
private const val LARGE_EXPORT_BYTES = 200L * 1024 * 1024
private const val LARGE_EXPORT_FILES = 2_000

/** 选择项多于这个数就先做一次体量预览（小选择不付这次遍历成本）。 */
private const val LARGE_SELECTION_HINT_COUNT = 50

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
