package app.androidrik.rikkahub.data.repository

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.SshWorkspaceDraft
import app.androidrik.rikkahub.data.model.SshWorkspaceSettings
import app.androidrik.rikkahub.data.export.WorkspaceBatchResult
import app.androidrik.rikkahub.data.export.ExportTreeWriter
import app.androidrik.rikkahub.data.export.collectWorkspaceExportPlan
import app.androidrik.rikkahub.data.export.exportPlanToTree
import app.androidrik.rikkahub.data.export.writeWorkspaceZip
import app.androidrik.rikkahub.data.db.dao.WorkspaceDAO
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.SecretCipher
import app.androidrik.workspace.RootfsInstallProgress
import app.androidrik.workspace.RootfsInstaller
import app.androidrik.workspace.WorkspaceBackend
import app.androidrik.workspace.WorkspaceCommandResult
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.RootfsLineWindow
import app.androidrik.workspace.WorkspaceLayout
import app.androidrik.workspace.WorkspaceManager
import app.androidrik.workspace.WorkspaceKillResult
import app.androidrik.workspace.WorkspaceShellStatus
import app.androidrik.workspace.WorkspaceStorageArea
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceType
import app.androidrik.workspace.ssh.SshAuthType
import app.androidrik.workspace.ssh.SshConnectionConfig
import app.androidrik.workspace.ssh.SshConnectionPool
import app.androidrik.workspace.ssh.SshProbeResult
import app.androidrik.workspace.ssh.SshShell
import app.androidrik.workspace.ssh.SshWorkspaceBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val sshPool: SshConnectionPool,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            // 文件不在本机的工作区（如远程）没有本地目录和 Rootfs
            if (!workspace.capabilities().localFiles) continue
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

    /** 用一条临时连接测试 SSH 配置；[id] 不为空时，表单里留空的凭据沿用该工作区已保存的值 */
    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        return runInterruptible(Dispatchers.IO) {
            sshPool.probe(draft.toSettings(previous).toConnectionConfig())
        }
    }

    /** 新建（[id] 为 null）或更新 SSH 工作区 */
    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
        val existing = id?.let { dao.getById(it) ?: error("Workspace not found: $it") }
        require(existing == null || existing.isSsh()) { "Not an SSH workspace: $id" }
        val finalName = draft.name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        val settings = withContext(Dispatchers.IO) { draft.toSettings(existing?.sshSettings()) }
        val now = System.currentTimeMillis()
        val workspace = existing?.copy(
            name = finalName,
            sshConfig = JsonInstant.encodeToString(settings),
            updatedAt = now,
        ) ?: Uuid.random().toString().let { newId ->
            WorkspaceEntity(
                id = newId,
                name = finalName,
                root = newId,
                // 远程工作区不需要安装 Rootfs, 视为 shell 已就绪
                shellStatus = WorkspaceShellStatus.READY.name,
                createdAt = now,
                updatedAt = now,
                type = WorkspaceType.SSH.name,
                sshConfig = JsonInstant.encodeToString(settings),
            )
        }
        dao.upsert(workspace)
        withContext(Dispatchers.IO) { sshPool.close(workspace.id) }
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
        require(workspace.capabilities().rootfs) { "Rootfs is not available for ${workspace.type} workspaces" }
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
        runInterruptible { backendOf(workspace).listFiles(area, path) }
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
        val backend = backendOf(workspace)
        val results = mutableListOf<WorkspaceFileEntry>()
        val pending = ArrayDeque<String>()
        pending.add("")
        while (pending.isNotEmpty() && results.size < limit) {
            val dir = pending.removeFirst()
            val entries = runCatching {
                backend.listFiles(WorkspaceStorageArea.FILES, dir)
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
    ): String = readFromBackend(id) { it.readText(path) }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withBackend(id) { it.writeText(path, text, overwrite) }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = readFromBackend(id) { backend ->
        when (area) {
            WorkspaceStorageArea.FILES -> backend.readText(path)
            WorkspaceStorageArea.LINUX -> {
                val size = backend.fileSize(area, path)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    backend.exportFile(area, path, out)
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
    ): WorkspaceFileEntry = withBackend(id) {
        it.importFile(area, destinationPath, fileName, inputStream)
    }

    suspend fun renameFile(id: String, area: WorkspaceStorageArea, path: String, name: String): WorkspaceFileEntry =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            require(workspace.capabilities().localFiles) { "Rename is not available for ${workspace.type} workspaces" }
            manager.renameFile(workspace.root, path, name, area)
        }

    /** Sequential, per-item failures; cancellation is never converted into a failed item. */
    suspend fun renameFiles(id: String, area: WorkspaceStorageArea, names: Map<String, String>): List<String> {
        val failed = mutableListOf<String>()
        for ((path, name) in names) {
            try {
                renameFile(id, area, path, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += "$path: ${error.message}"
            }
        }
        return failed
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = readFromBackend(id) { it.fileSize(area, path) }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportFile(area, path, outputStream) }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = readFromBackend(id) { it.absoluteFileSize(path) }

    /**
     * 取 Rootfs 内文件的真实路径（给 PDF 等需要 `File` 的解析器；解析本身在调用方做，
     * 这样 workspace 模块不必认识文档格式）。
     *
     * 只有文件在本机的工作区才有真实路径；SSH 工作区由调用方按能力跳过。
     */
    suspend fun rootfsReadableFile(id: String, path: String): File = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(workspace.capabilities().localFiles) {
            "Local file access is not available for ${workspace.type} workspaces"
        }
        manager.ensureWorkspace(workspace.root)
        manager.resolveReadableRootfsFile(workspace.root, path)
    }

    /**
     * 按行窗口读取 Rootfs 内文件。
     *
     * 本地工作区走流式读取；SSH 工作区没有本地 `File`，退化为按 backend 允许的大小整读后切窗口。
     */
    suspend fun readRootfsLineWindow(
        id: String,
        path: String,
        startLine: Long,
        limit: Int,
    ): RootfsLineWindow = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.capabilities().localFiles) {
            manager.ensureWorkspace(workspace.root)
            manager.readRootfsLineWindow(workspace.root, path, startLine, limit)
        } else {
            // 远程没有本地 File 可流式读：按绝对路径整读后再切窗口，
            // 上限沿用工作区的单次读取上限，避免窗口请求顺手把大文件全拉进内存。
            val backend = backendOf(workspace)
            val maxBytes = workspace.maxReadFileBytes.takeIf { it > 0 }
                ?: WorkspaceEntity.DEFAULT_MAX_READ_FILE_BYTES
            val size = backend.absoluteFileSize(path)
            require(size <= maxBytes) {
                "File is too large for a remote window read: $path (${size / 1024 / 1024}MB, " +
                    "max ${maxBytes / 1024 / 1024}MB). Use shell commands like sed or head instead."
            }
            val content = ByteArrayOutputStream(size.toInt()).use { output ->
                backend.exportAbsoluteFile(path, output)
                output.toString(Charsets.UTF_8.name())
            }
            val lines = content.lineSequence().toList()
            val from = (startLine - 1).coerceAtLeast(0).coerceAtMost(lines.size.toLong()).toInt()
            val window = lines.drop(from).take(limit)
            RootfsLineWindow(
                lines = window,
                moreLines = from + window.size < lines.size,
            )
        }
    }

    /**
     * Rootfs 内**实际可用**的 shell（`/bin/bash` 或回退 `/bin/sh`）。
     *
     * 系统提示与执行点共用它：模型按提示里的 shell 写命令，若这里与实际启动的不一致，
     * 命令会以「提示说 bash、实际起的是 sh」的方式莫名其妙地失败。
     */
    suspend fun rootfsShell(id: String): String = readFromBackend(id) { it.shell() }

    /** Rootfs 内文件的最后修改时间（毫秒）；不存在或不是文件时返回 null（写入冲突检测用） */
    suspend fun rootfsFileMtime(
        id: String,
        path: String,
    ): Long? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (!workspace.capabilities().localFiles) return@withContext null
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileMtime(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportAbsoluteFile(path, outputStream) }

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
        require(workspace.capabilities().localFiles) {
            "Batch export is not available for ${workspace.type} workspaces"
        }
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
        require(workspace.capabilities().localFiles) {
            "Batch export is not available for ${workspace.type} workspaces"
        }
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
        require(workspace.capabilities().localFiles) {
            "Batch export is not available for ${workspace.type} workspaces"
        }
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
    ): Boolean = withBackend(id) { it.deleteFile(area, path, recursive) }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(workspace.capabilities().localFiles) { "Move is not available for ${workspace.type} workspaces" }
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        val workspace = dao.getById(id) ?: return
        require(workspace.capabilities().rootfs) {
            "Shell compatibility mode is not available for ${workspace.type} workspaces"
        }
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
            backendOf(workspace).executeCommand(command, cwd, timeoutMillis, stdin)
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
        require(workspace.capabilities().backgroundTasks) {
            "Background tasks are not available for ${workspace.type} workspaces"
        }
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
        if (!workspace.capabilities().backgroundTasks) return@withContext emptyList()
        manager.backgroundTasks(workspace.root)
    }

    suspend fun getBackgroundTask(id: String, taskId: String): WorkspaceTaskSnapshot? =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext null
            if (!workspace.capabilities().backgroundTasks) return@withContext null
            manager.backgroundTask(taskId)?.takeIf { it.root == workspace.root }
        }

    suspend fun killBackgroundTask(id: String, taskId: String): WorkspaceKillResult =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext WorkspaceKillResult.NOT_FOUND
            if (!workspace.capabilities().backgroundTasks) return@withContext WorkspaceKillResult.NOT_FOUND
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

    /** 工作区在执行环境内的目录约定；工作区不存在时返回 null */
    suspend fun layout(id: String): WorkspaceLayout? = dao.getById(id)?.let { workspace ->
        // 只需要目录约定, 不解密凭据也不建立连接
        if (workspace.isSsh()) {
            workspace.sshSettings()?.let { SshWorkspaceBackend.layoutOf(it.remoteDir) }
        } else {
            manager.layout
        }
    }

    /** 按环境内绝对路径写入文本；本地工作区在 Rootfs 内执行 shell 写入。 */
    suspend fun writeAbsoluteText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).writeAbsoluteText(path, text, overwrite)
        }
    }

    /** 按环境内绝对路径解析宿主机上的文件；没有本地落点时返回 null */
    suspend fun resolveAbsoluteFile(
        id: String,
        path: String,
    ): File? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        runCatching { backendOf(workspace).localAbsoluteFile(path) }
            .getOrNull()
            ?.takeIf { it.isFile }
    }

    /** 打开 SSH 工作区的交互式 shell，阻塞到连接和 shell 都建立好，不能在主线程调用 */
    fun openSshShell(workspace: WorkspaceEntity, columns: Int, rows: Int): SshShell {
        val backend = backendOf(workspace) as? SshWorkspaceBackend
            ?: error("Not an SSH workspace: ${workspace.id}")
        return backend.openShell(columns, rows)
    }

    private fun backendOf(workspace: WorkspaceEntity): WorkspaceBackend =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshWorkspaceBackend(sshPool, workspace.id, settings.toConnectionConfig())
        } else {
            manager.backend(workspace.root, workspace.shellCompatibilityMode)
        }

    private fun SshWorkspaceSettings.toConnectionConfig() = SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = encryptedPassword?.let(::decryptSecret),
        privateKey = encryptedPrivateKey?.let(::decryptSecret),
        passphrase = encryptedPassphrase?.let(::decryptSecret),
        remoteDir = remoteDir,
        hostKey = hostKey,
    )

    /**
     * 表单转成持久化配置并加密凭据。编辑时留空的凭据沿用 [previous] 的密文；
     * 私钥口令跟随私钥：换了新私钥就以表单为准，沿用旧私钥时口令留空也沿用。
     */
    private fun SshWorkspaceDraft.toSettings(previous: SshWorkspaceSettings?): SshWorkspaceSettings {
        val normalizedRemoteDir = remoteDir.trim().trimEnd('/').ifEmpty { "/" }
        require(normalizedRemoteDir.startsWith("/")) { "Remote directory must be an absolute path" }
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
        require(port in 1..65535) { "Invalid port: $port" }
        val keepPrivateKey = privateKey.isBlank()
        return SshWorkspaceSettings(
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            remoteDir = normalizedRemoteDir,
            hostKey = hostKey,
            encryptedPassword = if (authType == SshAuthType.PASSWORD) {
                password.takeIf { it.isNotEmpty() }?.let(SecretCipher::encrypt) ?: previous?.encryptedPassword
            } else {
                null
            },
            encryptedPrivateKey = if (authType == SshAuthType.PRIVATE_KEY) {
                privateKey.takeIf { it.isNotBlank() }?.let { SecretCipher.encrypt(it.trim()) }
                    ?: previous?.encryptedPrivateKey
            } else {
                null
            },
            encryptedPassphrase = when {
                authType != SshAuthType.PRIVATE_KEY -> null
                passphrase.isNotEmpty() -> SecretCipher.encrypt(passphrase)
                keepPrivateKey -> previous?.encryptedPassphrase
                else -> null
            },
        )
    }

    private fun decryptSecret(value: String): String = try {
        SecretCipher.decrypt(value)
    } catch (e: Exception) {
        throw IOException(
            "Saved SSH credentials cannot be decrypted (for example after restoring a backup on another device). " +
                "Re-enter them in the workspace settings.",
            e,
        )
    }

    /**
     * 读操作：取消时中断线程，远程读取卡在网络上时也能及时返回。
     * 中途放弃没有副作用；写操作被打断会留下写了一半的文件，走 [withBackend] 照常执行完。
     */
    private suspend fun <T> readFromBackend(id: String, block: (WorkspaceBackend) -> T): T {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return try {
            runInterruptible(Dispatchers.IO) { block(backendOf(workspace)) }
        } catch (e: Exception) {
            // 被中断的读取会抛出 JSch 包装过的 IO 异常, 已取消时统一按取消处理, 免得被当成连接失败
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private suspend fun <T> withBackend(id: String, block: (WorkspaceBackend) -> T): T =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            block(backendOf(workspace))
        }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            sshPool.close(id)
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
