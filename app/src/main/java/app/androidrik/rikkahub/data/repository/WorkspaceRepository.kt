package app.androidrik.rikkahub.data.repository

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.export.WorkspaceBatchResult
import app.androidrik.rikkahub.data.export.ExportTreeWriter
import app.androidrik.rikkahub.data.export.collectWorkspaceExportPlan
import app.androidrik.rikkahub.data.export.exportPlanToTree
import app.androidrik.rikkahub.data.export.writeWorkspaceZip
import app.androidrik.rikkahub.data.db.dao.WorkspaceDAO
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.workspace.RootfsInstallProgress
import app.androidrik.workspace.RootfsInstaller
import app.androidrik.workspace.WorkspaceCommandResult
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.RootfsLineWindow
import app.androidrik.workspace.WorkspaceManager
import app.androidrik.workspace.WorkspaceKillResult
import app.androidrik.workspace.WorkspaceShellStatus
import app.androidrik.workspace.WorkspaceStorageArea
import app.androidrik.workspace.WorkspaceTaskSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    /** 设置工作区的单次读取上限（字节）；非正值忽略（用 `DEFAULT_MAX_READ_FILE_BYTES`） */
    suspend fun setMaxReadFileBytes(id: String, bytes: Long): Boolean {
        val workspace = dao.getById(id) ?: return false
        if (bytes <= 0) return false
        dao.upsert(
            workspace.copy(
                maxReadFileBytes = bytes,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    /**
     * 按文件名搜索（BFS 遍历 + limit），供侧滑文件面板等场景使用。
     *
     * 搜索 FILES 区（/workspace）；大小写不敏感，命中文件名（不含目录）；limit 防止超大 rootfs 拖垮查询。
     */
    suspend fun searchFilesByName(
        id: String,
        query: String,
        limit: Int = 80,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withContext emptyList()
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        val results = mutableListOf<WorkspaceFileEntry>()
        val pending = ArrayDeque<String>()
        pending.add("")
        while (pending.isNotEmpty() && results.size < limit) {
            val dir = pending.removeFirst()
            val entries = runCatching {
                manager.listFiles(workspace.root, dir, WorkspaceStorageArea.FILES)
            }.getOrElse { emptyList() }
            for (entry in entries) {
                if (results.size >= limit) break
                if (entry.isDirectory) {
                    pending.add(entry.path)
                } else if (entry.name.lowercase().contains(needle)) {
                    results.add(entry)
                }
            }
        }
        results
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /**
     * 取 Rootfs 内文件的真实路径（给 PDF 等需要 `File` 的解析器；解析本身在调用方做，
     * 这样 workspace 模块不必认识文档格式）。
     */
    suspend fun rootfsReadableFile(id: String, path: String): File = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveReadableRootfsFile(workspace.root, path)
    }

    /**
     * 按行窗口读取 Rootfs 内文件（流式，见 `WorkspaceManager.readRootfsLineWindow`）。
     *
     * **不受工作区的单次读取上限约束**：这条路径正是为了读大文件的局部而定，上限只作用于
     * 窗口自身的字节数（在工具层按上限裁）。
     */
    suspend fun readRootfsLineWindow(
        id: String,
        path: String,
        startLine: Long,
        limit: Int,
    ): RootfsLineWindow = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readRootfsLineWindow(workspace.root, path, startLine, limit)
    }

    /**
     * Rootfs 内**实际可用**的 shell（`/bin/bash` 或回退 `/bin/sh`）。
     *
     * 系统提示与执行点共用它：模型按提示里的 shell 写命令，若这里与实际启动的不一致，
     * 命令会以「提示说 bash、实际起的是 sh」的方式莫名其妙地失败。
     */
    suspend fun rootfsShell(id: String): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.rootfsShell(workspace.root)
    }

    /** Rootfs 内文件的最后修改时间（毫秒）；不存在或不是文件时返回 null（写入冲突检测用） */
    suspend fun rootfsFileMtime(
        id: String,
        path: String,
    ): Long? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileMtime(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream)
    }

    /**
     * 选中集体量预览（文件数, 总字节）。**只读遍历**，供"导出前先提示"用。
     */
    suspend fun previewSelectionSize(
        id: String,
        area: WorkspaceStorageArea,
        basePath: String,
        selection: List<WorkspaceFileEntry>,
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val (plan, _) = collectWorkspaceExportPlan(
            root = areaRootDir(workspace.root, area),
            selection = selection,
            basePath = basePath,
        )
        plan.fileCount to plan.totalBytes
    }

    /**
     * 批量导出为 **zip 单文档**（设计 `docs/design/design-workspace-multiselect-export.md` §六.1）。
     *
     * 遍历与写出都在 `data/export/WorkspaceExport.kt`（纯逻辑 + 流式写出，可单测），
     * 仓库只负责"把区域根目录交出去"。**不落临时文件**：直接写宿主给的 OutputStream。
     */
    suspend fun exportSelectionAsZip(
        id: String,
        area: WorkspaceStorageArea,
        basePath: String,
        selection: List<WorkspaceFileEntry>,
        outputStream: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WorkspaceBatchResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val (plan, skipped) = collectWorkspaceExportPlan(
            root = areaRootDir(workspace.root, area),
            selection = selection,
            basePath = basePath,
        )
        val written = writeWorkspaceZip(outputStream, plan, onProgress)
        WorkspaceBatchResult.ofTree(
            succeeded = written,
            skippedNames = skipped,
            // zip 是"一个文档"：写出即成功；条目级问题只可能出现在遍历期（已计入 skipped）
            failedNames = emptyList(),
        )
    }

    /**
     * 批量导出到 **SAF 授权目录**（逐个写文件、保留原目录结构）。
     *
     * 遍历与写入规则都在 `data/export/WorkspaceExport.kt` 的 [exportPlanToTree] 里（writer 抽象，
     * **可用内存假实现完整单测**）；本层只做两件事：把区域根目录交出去、把 `DocumentFile` 适配成 writer。
     *
     * 与 zip 的差别不只是"多文件"：这里会出现**部分成功**（写了 7 个、第 8 个失败），
     * 所以要分别计"跳过/失败"并带上名字回给 UI（用户决议 §6.1）。
     */
    suspend fun exportSelectionToTree(
        id: String,
        area: WorkspaceStorageArea,
        basePath: String,
        selection: List<WorkspaceFileEntry>,
        tree: DocumentFile,
        /**
         * 把 SAF 文档写成流的通道。
         *
         * **为什么由调用方给**：`DocumentFile` 只有"建/找/删"这些结构操作，**没有写流**的 API
         * （1.0.x 连 `openOutputStream` 都没有）⇒ 写这一半必须走 `ContentResolver`，
         * 而仓库层没有 Context。与其把 Context 灌进数据层，不如让页面把这一小段能力传进来。
         */
        openOutputStream: (Uri) -> OutputStream?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WorkspaceBatchResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val (plan, skipped) = collectWorkspaceExportPlan(
            root = areaRootDir(workspace.root, area),
            selection = selection,
            basePath = basePath,
        )
        val result = exportPlanToTree(
            plan = plan,
            writer = DocumentFileTreeWriter(tree, openOutputStream),
            onProgress = onProgress,
        )
        // 遍历期的跳过（不在基目录下 / 选中后被删）与写入期的分档合并汇报
        result.copy(
            skipped = result.skipped + skipped.size,
            skippedNames = result.skippedNames + skipped,
        )
    }

    /**
     * `DocumentFile` → [ExportTreeWriter] 的适配层（生产实现；逻辑在 writer 抽象之外，故不需要单测）。
     *
     * mime 由扩展名派生：SAF 的 `createFile` 必须给 mime，未知一律 `application/octet-stream`。
     */
    private class DocumentFileTreeWriter(
        override val root: DocumentFile,
        private val openOutputStream: (Uri) -> OutputStream?,
    ) : ExportTreeWriter<DocumentFile> {
        override fun findChild(parent: DocumentFile, name: String): DocumentFile? = parent.findFile(name)

        override fun isDirectory(handle: DocumentFile): Boolean = handle.isDirectory

        override fun isFile(handle: DocumentFile): Boolean = handle.isFile

        override fun createDirectory(parent: DocumentFile, name: String): DocumentFile? =
            parent.createDirectory(name)

        override fun createFile(parent: DocumentFile, name: String): DocumentFile? =
            parent.createFile(mimeTypeOf(name), name)

        override fun openOutputStream(handle: DocumentFile): OutputStream? =
            openOutputStream(handle.uri)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                workspace.root, command, cwd, timeoutMillis, stdin,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
    }

    /**
     * 启动后台命令（不阻塞对话轮）。任务表在 `WorkspaceManager` 内存里，进程重启即丢失。
     *
     * @param owner 启动方标记（本轮生成令牌）。只用于 [killBackgroundTasksByOwner]：
     *   本轮被打断时把这一轮起的任务一起收掉。**不改并发上限、不改工具可见性**。
     */
    suspend fun startBackgroundCommand(
        id: String,
        command: String,
        cwd: String = "",
        owner: String? = null,
    ): WorkspaceTaskSnapshot = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.startBackgroundCommand(
            workspace.root, command, cwd,
            shellCompatibilityMode = workspace.shellCompatibilityMode,
            owner = owner,
        )
    }

    /** 该工作空间的后台任务列表（内存态，只含本次进程启动后创建的）。 */
    suspend fun listBackgroundTasks(id: String): List<WorkspaceTaskSnapshot> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.backgroundTasks(workspace.root)
    }

    suspend fun getBackgroundTask(id: String, taskId: String): WorkspaceTaskSnapshot? =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext null
            manager.backgroundTask(taskId)?.takeIf { it.root == workspace.root }
        }

    suspend fun killBackgroundTask(id: String, taskId: String): WorkspaceKillResult =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext WorkspaceKillResult.NOT_FOUND
            // 归属校验：只允许终止本工作空间的任务，避免跨工作空间误杀
            val task = manager.backgroundTask(taskId)
                ?: return@withContext WorkspaceKillResult.NOT_FOUND
            if (task.root != workspace.root) return@withContext WorkspaceKillResult.NOT_FOUND
            manager.killBackgroundTask(taskId)
        }

    /**
     * 终止 [owner] 启动的全部后台任务（跨工作空间），返回终止数量。
     *
     * **不走 [killBackgroundTask] 的根归属校验**：owner 令牌由本轮生成独占，命中的任务
     * 天然属于该轮，而调用方（[app.androidrik.rikkahub.service.ChatService.stopGeneration]）
     * 手上只有会话 id、不一定持有 workspace 行——为了一个「已经确定是这轮起的」集合再去查
     * 一次工作区只会多一次可能失败的 IO。空 owner 由 [WorkspaceManager] 侧挡掉（返回 0）。
     */
    suspend fun killBackgroundTasksByOwner(owner: String): Int = withContext(Dispatchers.IO) {
        manager.killBackgroundTasksByOwner(owner)
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    /**
     * 存储区 → 宿主根目录。
     *
     * `WorkspaceManager.areaDir` 是 private，这里按同样的映射重写一份——**只映射两个公开目录**，
     * 不复制其它语义（少一个需要同步的实现）。
     */
    private fun areaRootDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> manager.filesDir(root)
        WorkspaceStorageArea.LINUX -> manager.linuxDir(root)
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}

/**
 * 扩展名 → mime；未知一律 `application/octet-stream`（SAF 的 `createFile` 需要它）。
 *
 * **文件级而不是类成员**：[WorkspaceRepository.DocumentFileTreeWriter] 是嵌套类，
 * 拿不到外部类实例的成员（Kotlin 的嵌套类非 inner）——而这个函数本来也不需要实例状态。
 */
private fun mimeTypeOf(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}
