package app.androidrik.rikkahub.ui.pages.extensions.workspace

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.export.WorkspaceBatchResult
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.RootfsInstallProgress
import app.androidrik.workspace.RootfsInstallStage
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceCommandResult
import app.androidrik.workspace.WorkspaceStorageArea
import app.androidrik.workspace.WorkspaceTaskSnapshot

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    /**
     * 后台任务（AI 用 `workspace_run_background` 起的命令）。
     *
     * 注册表是**进程内内存态**、没有 Flow 可订阅，所以这里靠页面定时 [refreshTasks] 拉取
     * （有任务 2s、空闲 10s）。任务的存在与输出都不在文件系统之外，页面是用户唯一的「看得见」入口。
     */
    private val _tasks = MutableStateFlow<List<WorkspaceTaskSnapshot>>(emptyList())
    val tasks = _tasks.asStateFlow()

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    /** 拉取后台任务列表；失败按空处理（内存态查询极少失败，页面不该因此报错）。 */
    fun refreshTasks() {
        viewModelScope.launch {
            _tasks.value = runCatching { repository.listBackgroundTasks(id) }.getOrDefault(emptyList())
        }
    }

    /** 终止后台任务；无论结果如何都重新拉一次，让界面立刻反映真实状态。 */
    fun killTask(taskId: String) {
        viewModelScope.launch {
            runCatching { repository.killBackgroundTask(id, taskId) }
            _tasks.value = runCatching { repository.listBackgroundTasks(id) }.getOrDefault(emptyList())
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     *
     * 文件名走 [workspaceCacheFileName]（**按路径**而不是只按 `entry.name`）：工作区是目录树，
     * 不同子目录下的同名文件曾落到同一个 cache 路径互相覆盖——表现是 #2126「预览第二张同名图片
     * 看到的是第一张的内容」（Coil 以绝对路径为缓存键，路径没变就直接命中上次的字节）。
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, workspaceCacheFileName(entry.path))
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到指定目录, 完成后回调 [onReady].
     * 供列表内 SVG 缩略图渲染等复用 (与 [exportToCacheFile] 的 share 目录分离避免互相覆盖).
     */
    fun exportToCacheDir(entry: WorkspaceFileEntry, dir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                dir.mkdirs()
                // 同名文件在不同子目录会碰撞, 用路径替换分隔符保证唯一（判据与 exportToCacheFile 共用）
                val file = File(dir, workspaceCacheFileName(entry.path))
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady)
        }
    }

    /**
     * 批量删除（多选）。返回**失败项的路径**（空 = 全成功）。
     *
     * 逐项删除而不是"一把梭"：单项失败（权限、被占用）不该让其余项也不删——
     * 与导出那边"部分成功"是同一类现实（用户决议 §6.1）。
     */
    suspend fun deleteSelection(entries: List<WorkspaceFileEntry>): List<String> {
        val area = state.value.area
        val failed = mutableListOf<String>()
        entries.forEach { entry ->
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onFailure { failed += entry.path }
        }
        refresh()
        return failed
    }

    /**
     * 选中集的**体量预览**（走一次遍历，只读）。
     *
     * 用途：大目录导出前先告诉用户"这批有多少"（设计 §五.5 的阈值提示），
     * 而不是点完才发现要压几 GB。**只在选择较大或含文件夹时调用**——小选择没必要多走一次树。
     */
    suspend fun previewSelection(
        entries: List<WorkspaceFileEntry>,
    ): Pair<Int, Long>? = runCatching {
        repository.previewSelectionSize(
            id = id,
            area = state.value.area,
            basePath = state.value.path,
            selection = entries,
        )
    }.getOrNull()

    /** 打包为 zip（流式写宿主给的流；返回 null = 失败，异常已落到 state.error）。 */
    suspend fun exportSelectionAsZip(
        entries: List<WorkspaceFileEntry>,
        outputStream: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WorkspaceBatchResult? = runCatching {
        repository.exportSelectionAsZip(
            id = id,
            area = state.value.area,
            basePath = state.value.path,
            selection = entries,
            outputStream = outputStream,
            onProgress = onProgress,
        )
    }.onFailure { error ->
        _state.update { it.copy(error = error.message ?: "导出失败") }
    }.getOrNull()

    /** 逐个导出到 SAF 授权目录（保留原目录结构）。 */
    suspend fun exportSelectionToTree(
        entries: List<WorkspaceFileEntry>,
        tree: DocumentFile,
        openOutputStream: (Uri) -> OutputStream?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WorkspaceBatchResult? = runCatching {
        repository.exportSelectionToTree(
            id = id,
            area = state.value.area,
            basePath = state.value.path,
            selection = entries,
            tree = tree,
            openOutputStream = openOutputStream,
            onProgress = onProgress,
        )
    }.onFailure { error ->
        _state.update { it.copy(error = error.message ?: "导出失败") }
    }.getOrNull()

    /** 单次读取上限（字节）。窗口读（offset/limit）不受它约束，所以调大它只是为了"整文件读大文件"。 */
    fun setMaxReadFileBytes(bytes: Long) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setMaxReadFileBytes(workspace.id, bytes)
            loadWorkspace()
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    // Shell 兼容模式（上游 03246406）：开关只落库，新开的终端/命令生效；已有终端需重开
    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val workspace = state.value.workspace ?: return@launch
                repository.setShellCompatibilityMode(workspace.id, enabled)
                loadWorkspace()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _installError.value = error.message ?: "保存 Shell 兼容模式失败"
            }
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}

/**
 * 任务时长的紧凑展示（`1h02m` / `5m30s` / `12s`）。
 *
 * 纯函数放在 VM 文件里，便于 JVM 单测；界面只负责展示，不做时间计算。**向上取整**：剩余 0.4 秒时
 * 显示 `1s` 而不是 `0s`——「0 秒后自动终止」会让用户以为已经停了。
 */
internal fun formatTaskDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h%02dm".format(minutes)
        minutes > 0L -> "${minutes}m%02ds".format(seconds)
        else -> "${seconds}s"
    }
}
