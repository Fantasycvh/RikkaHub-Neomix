package app.androidrik.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.export.WorkspaceBatchResult
import app.androidrik.rikkahub.data.export.WorkspaceExportNames
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.ImagePreviewDialog
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.workspace.WorkspaceFileBrowser
import app.androidrik.rikkahub.ui.components.workspace.WorkspaceSelectionToolbar
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileSizeToString
import app.androidrik.rikkahub.utils.writeClipboardText
import app.androidrik.workspace.RootfsCatalog
import app.androidrik.workspace.RootfsMirrorSpeed
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceShellStatus
import app.androidrik.workspace.WorkspaceStorageArea
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val mirrorSpeeds by vm.mirrorSpeeds.collectAsStateWithLifecycle()
    val mirrorSetupRunning by vm.mirrorSetupRunning.collectAsStateWithLifecycle()
    val mirrorPicks by vm.mirrorPicks.collectAsStateWithLifecycle()
    val aptIndexRefresh by vm.aptIndexRefresh.collectAsStateWithLifecycle()
    val aptIndexDetail by vm.aptIndexDetail.collectAsStateWithLifecycle()
    val mirrorError by vm.mirrorError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    // 后台任务的剩余时长需要「现在」这个输入；轮询的每一跳顺带刷新它，避免再挂一个秒级 ticker
    var tasksNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshTasks()
            tasksNow = System.currentTimeMillis()
            delay(if (tasks.any { it.isRunning }) TASKS_POLL_RUNNING_MS else TASKS_POLL_IDLE_MS)
        }
    }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val fileActions = app.androidrik.rikkahub.ui.components.workspace.rememberWorkspaceFileActions(
        location = app.androidrik.rikkahub.ui.components.workspace.WorkspaceFileLocation(id, state.area, state.path),
        repository = org.koin.compose.koinInject(),
        onChanged = vm::refresh,
    )
    var exportArea by remember { mutableStateOf(state.area) }
    var batchArea by remember { mutableStateOf(state.area) }
    var batchPath by remember { mutableStateOf(state.path) }
    var deleteArea by remember { mutableStateOf(state.area) }
    var deleteSnapshot by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    // SAF launcher 回调里要用的"这次导哪些"（launcher 不能带参数，只能借状态传递）
    var pendingExportEntries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }

    // ---- 多选（设计 docs/design/design-workspace-multiselect-export.md；选择态由页面持有、组件无状态）----
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
        vm.exportFile(entry, outputStream, exportArea)
    }

    fun finishSelection() {
        selecting = false
        selectedPaths = emptyList()
    }

    fun exportResultMessage(result: WorkspaceBatchResult?): String = when {
        result == null -> resources.getString(R.string.workspace_export_failed)
        result.skipped == 0 && result.failed == 0 ->
            resources.getString(R.string.workspace_export_done_ok, result.succeeded)

        else -> resources.getString(
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
        batchArea = state.area
        batchPath = state.path
        pendingExportEntries = entries
        // 只有"含文件夹 / 选择很多"时才多走一次树做体量预览——小选择不必付这个成本
        if (entries.any { it.isDirectory } || entries.size > LARGE_SELECTION_HINT_COUNT) {
            scope.launch {
                val preview = vm.previewSelection(entries, batchArea, batchPath)
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
            toaster.show(resources.getString(R.string.workspace_export_failed))
            return@rememberLauncherForActivityResult
        }
        exportProgress = 0 to entries.size
        scope.launch {
            val result = vm.exportSelectionAsZip(entries, outputStream, batchArea, batchPath) { done, total ->
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
            toaster.show(resources.getString(R.string.workspace_export_failed))
            return@rememberLauncherForActivityResult
        }
        exportProgress = 0 to entries.size
        scope.launch {
            val result = vm.exportSelectionToTree(
                entries = entries,
                area = batchArea,
                basePath = batchPath,
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
    LaunchedEffect(state.area, state.path, pagerState.currentPage, selecting) {
        if (!selecting || pagerState.currentPage != 1) finishSelection()
    }
    LaunchedEffect(state.area, state.path) { finishSelection() }
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
                        IconButton(onClick = fileActions.importFiles) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.supportsTerminal() == true) {
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
                            exportArea = state.area
                            exportTarget = entries.first()
                            exportLauncher.launch(entries.first().name)
                        } else {
                            showExportChooser = true
                        }
                    },
                    onRename = if (state.area == WorkspaceStorageArea.FILES && state.workspace?.capabilities()?.localFiles != false) {
                        { fileActions.rename(selectedEntries); finishSelection() }
                    } else null,
                    onDelete = { deleteArea = state.area; deleteSnapshot = selectedEntries.toList(); showDeleteSelectionConfirm = true },
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
                    tasks = tasks,
                    tasksNow = tasksNow,
                    onKillTask = vm::killTask,
                    onInstallRootfs = { showInstallDialog = true },
                    onToolApprovalChange = vm::setToolApproval,
                    onMaxReadBytesChange = vm::setMaxReadFileBytes,
                    onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                    onEditSsh = { navController.navigate(Screen.WorkspaceSshEditor(id)) },
                    mirrorSetupRunning = mirrorSetupRunning,
                    mirrorPicks = mirrorPicks,
                    aptIndexRefresh = aptIndexRefresh,
                    aptIndexDetail = aptIndexDetail,
                    onConfigureMirrors = vm::configurePackageMirrors,
                )

                1 -> WorkspaceFileBrowser(
                    entries = state.entries,
                    path = state.path,
                    area = state.area,
                    loading = state.loading,
                    error = state.error,
                    contentPadding = PaddingValues(),
                    hasRootfs = state.workspace?.capabilities()?.rootfs ?: true,
                    workspaceDir = state.workspaceDir,
                    onSelectArea = { area ->
                        // 切区等于换了一棵树：清空选择（跨目录/跨区多选不做，见设计 §五.3）
                        finishSelection()
                        vm.selectArea(area)
                    },
                    onRename = if (state.area == WorkspaceStorageArea.FILES && state.workspace?.capabilities()?.localFiles != false) {
                        { entry -> fileActions.rename(listOf(entry)) }
                    } else null,
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
                    // 批量打包导出只支持本地工作区；远程工作区隐藏多选，单文件导出仍在菜单里
                    onLongPress = if (state.workspace?.capabilities()?.localFiles != false) {
                        { entry ->
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
                        }
                    } else null,
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
                    onDelete = { deleteArea = state.area; deleteTarget = it },
                    onExport = { entry ->
                        exportArea = state.area
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
                    onCopyPath = { absolutePath ->
                        context.writeClipboardText(absolutePath)
                        // Android 13 起系统自己会弹「已复制」提示，低版本才需要自己提示
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.workspace_detail_path_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
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
                        val entries = deleteSnapshot
                        showDeleteSelectionConfirm = false
                        scope.launch {
                            val failed = vm.deleteSelection(entries, deleteArea)
                            finishSelection()
                            if (failed.isEmpty()) {
                                toaster.show(
                                    resources.getString(R.string.workspace_delete_selection_done, entries.size)
                                )
                            } else {
                                toaster.show(
                                    resources.getString(
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
                mirrorSpeeds = mirrorSpeeds,
                fileName = vm.rootfsFileName(),
                onTest = { vm.testRootfsMirrors(force = true) },
                onDismiss = {
                    showInstallDialog = false
                    vm.clearMirrorSpeeds()
                },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    mirrorError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissMirrorError,
            title = { Text(stringResource(R.string.workspace_detail_package_mirrors_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissMirrorError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
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
                vm.delete(entry, deleteArea)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

/** 轮询间隔：有任务在跑时快、空闲时慢（注册表是内存态，代价只是一次 DAO 查询）。 */
private const val TASKS_POLL_RUNNING_MS = 2_000L
private const val TASKS_POLL_IDLE_MS = 10_000L

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    mirrorSpeeds: List<RootfsMirrorSpeed>,
    fileName: String?,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 打开即测速：省掉用户先点一次「测速」的多余交互
    LaunchedEffect(workspace.id, fileName) {
        if (fileName != null) onTest()
    }
    var url by rememberSaveable(workspace.id) {
        mutableStateOf(fileName?.let { RootfsCatalog.MIRRORS.first().urlFor(it) }.orEmpty())
    }
    var edited by rememberSaveable(workspace.id) { mutableStateOf(false) }
    val fastest = mirrorSpeeds.firstOrNull { it.usable }
    // 用户没手动改过地址时，测速一出结果就自动切到最快的源
    LaunchedEffect(fastest?.url) {
        val best = fastest?.url ?: return@LaunchedEffect
        if (!edited) url = best
    }
    val testing = fileName != null && mirrorSpeeds.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (fileName != null) {
                        stringResource(R.string.workspace_detail_rootfs_image, fileName)
                    } else {
                        stringResource(R.string.workspace_detail_rootfs_arch_unsupported)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HugeIcons.Earth, contentDescription = null)
                    Text(
                        text = stringResource(R.string.workspace_detail_mirror_speed),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onTest, enabled = fileName != null && !testing) {
                        Text(stringResource(R.string.workspace_detail_mirror_retest))
                    }
                }

                if (testing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.workspace_detail_mirror_testing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    mirrorSpeeds.forEach { speed ->
                        val selected = speed.url == url
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = speed.usable) {
                                    edited = true
                                    url = speed.url
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    edited = true
                                    url = speed.url
                                },
                                enabled = speed.usable,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (speed == fastest) {
                                        stringResource(
                                            R.string.workspace_detail_mirror_fastest,
                                            speed.mirror.displayName,
                                        )
                                    } else {
                                        speed.mirror.displayName
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (speed.usable) {
                                        stringResource(
                                            R.string.workspace_detail_mirror_result,
                                            speed.bytesPerSecond.fileSizeToString(),
                                            speed.latencyMillis,
                                        )
                                    } else {
                                        speed.error ?: stringResource(R.string.workspace_detail_mirror_unreachable)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        edited = true
                        url = it
                    },
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

/** 列表里展示的状态: 远程工作区显示连接地址, 本地工作区显示 shell 状态 */
@Composable
internal fun WorkspaceEntity.statusLabel(): String =
    if (isSsh()) {
        sshSettings()?.let { "SSH · ${it.username}@${it.host}" } ?: "SSH"
    } else {
        shellStatus.toShellStatusLabel()
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
