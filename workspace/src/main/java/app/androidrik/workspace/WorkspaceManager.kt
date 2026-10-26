package app.androidrik.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    /**
     * 后台任务启动器。为 null 时后台任务工具不可用（`startBackgroundCommand` 抛错），
     * 用于「只有宿主机 shell、没有 rootfs」的配置。
     */
    private val backgroundLauncher: WorkspaceTaskLauncher? = null,
    /** 后台任务并发上限（透传给 [WorkspaceTaskRegistry]，便于单测收紧）。 */
    private val maxBackgroundTasks: Int = DEFAULT_MAX_CONCURRENT_TASKS,
    /** 后台任务最长存活时长；`<= 0` 表示不限制（见 [DEFAULT_MAX_TASK_LIFETIME_MS]）。 */
    private val maxBackgroundTaskLifetimeMillis: Long = DEFAULT_MAX_TASK_LIFETIME_MS,
    /**
     * 只读挂载快照的存储目录（design-readonly-mount-snapshots §3.3）。null = 关闭（测试用）。
     * 全局一份而非按工作区：/upload 源本就是全局的，快照跨工作区复用同一代才省空间。
     */
    private val readOnlySnapshotStorage: File? = null,
) {
    private val fileSystem = WorkspaceFileSystem(config)

    private val readOnlySnapshots = ReadOnlyMountSnapshots()

    /** 工作区在执行环境内的目录约定；可写区与工具审批口径保持一致 */
    val layout = WorkspaceLayout(
        workspaceDir = ROOTFS_WORKSPACE_DIR,
        writableRoots = listOf(ROOTFS_WORKSPACE_DIR, "/tmp"),
    )

    /** 后台任务表：进程内内存态，见 [WorkspaceTaskRegistry] 的类注释。 */
    private val backgroundTaskRegistry: WorkspaceTaskRegistry? =
        backgroundLauncher?.let {
            WorkspaceTaskRegistry(
                it,
                maxConcurrent = maxBackgroundTasks,
                maxLifetimeMillis = maxBackgroundTaskLifetimeMillis,
            )
        }

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun backend(root: String, shellCompatibilityMode: Boolean = false): WorkspaceBackend =
        LocalWorkspaceBackend(this, root, shellCompatibilityMode)

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean {
        // 先终止该工作空间的后台任务：否则进程还持有已删除目录里的 cwd/fd，行为不可预期
        killBackgroundTasks(root)
        return workspaceDir(root).deleteRecursively()
    }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        fileSystem.validateName(fileName)
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isEmpty()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    /** 存储区内文件在宿主机上的真实文件，已校验存在且为文件 */
    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    /**
     * 取工作区内文件的**真实 File**（已校验存在/可读、内核伪文件系统已拒绝）。
     *
     * 只给"需要真实文件路径的解析器"用（PDF 等原生解析器都吃 `File`）：调用方只读，不得移动/删除。
     */
    fun resolveReadableRootfsFile(root: String, path: String): File {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        return file
    }

    /**
     * 按行窗口读取（**流式**）：只把窗口内的行进内存。
     *
     * 为什么要单独一条路径：整文件读受「单次读取上限」约束，而窗口读的意义正是**读大文件的局部**
     * ——上限管的是「一次把多少内容交给模型」，不该管「能不能翻到一个大文件的第一万行」。
     *
     * 行分隔符统一按 `\n` 归一（`readLine` 会吃掉 `\r\n` 的 `\r`）：模型拿到的是归一化后的行，
     * 逐行拼接回去时不会带上 `\r`。
     *
     * @param startLine 1 基起始行；超过文件行数 ⇒ 返回空窗口
     * @param limit 最多返回的行数
     */
    fun readRootfsLineWindow(
        root: String,
        path: String,
        startLine: Long,
        limit: Int,
    ): RootfsLineWindow {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        val lines = ArrayList<String>(limit.coerceAtMost(4096))
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var skipped = 0L
            while (skipped < startLine - 1) {
                if (reader.readLine() == null) break
                skipped++
            }
            while (lines.size < limit) {
                lines += reader.readLine() ?: break
            }
            // 再探一行判断窗口之后还有没有内容（这一行不返回，只用来置 truncated）
            return RootfsLineWindow(lines = lines, moreLines = reader.readLine() != null)
        }
    }

    /** Rootfs 内文件的最后修改时间（毫秒）；不存在或不是文件时返回 null（写入冲突检测用） */
    fun rootfsFileMtime(root: String, path: String): Long? {
        val file = resolveRootfsFile(root, path)
        if (!file.exists() || !file.isFile) return null
        return file.lastModified()
    }

    /**
     * Rootfs 内**实际可用**的 shell：优先 `/bin/bash`，缺失时回退 `/bin/sh`。
     *
     * 为什么需要：`/bin/bash` 此前硬编码在两处执行点（工具执行与终端页）⇒ 没有 bash 的 rootfs
     * （自制精简 rootfs、Alpine 等）shell 工具直接失败。系统提示里告知的 shell 也必须取这里，
     * 否则会「说的和跑的不是一个」（对齐上游 `298e2771`）。
     */
    fun rootfsShell(root: String): String = rootfsShellFor(linuxDir(root))

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Rootfs 内绝对路径对应的宿主机文件，不检查是否存在 */
    fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun renameFile(root: String, path: String, name: String, area: WorkspaceStorageArea): WorkspaceFileEntry {
        require(area == WorkspaceStorageArea.FILES) {
            "Linux 区重命名暂不可用：无法安全维护 proot 链接元数据，请使用 Linux 终端。"
        }
        return fileSystem.rename(filesDir(root), path, name)
    }

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        // 只读挂载先换快照（design-readonly-mount-snapshots §3.3）：shell（前台 + 后台任务）
        // 是 /upload 只读保护的唯一绕过面；失败向上传播，与 cwd 预检同层
        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = preparedBindMounts(),
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
    }

    /**
     * 启动一个**后台**命令（不阻塞当前对话轮），返回任务快照。
     *
     * 与 [executeCommand] 的差别：无超时（后台任务的定义就是比一轮对话活得久），
     * 输出不进上下文而是写进尾部缓冲，由 `workspace_task_status` 按需轮询。
     *
     * @param owner 启动方标记（上层传「本轮生成」的令牌），供 [killBackgroundTasksByOwner]
     *   在本轮被打断时精确收掉这一轮起的任务。语义见 [WorkspaceTaskRegistry.start]。
     */
    fun startBackgroundCommand(
        root: String,
        command: String,
        cwd: String = "",
        shellCompatibilityMode: Boolean = false,
        owner: String? = null,
    ): WorkspaceTaskSnapshot {
        require(command.isNotBlank()) { "Command is required" }
        val registry = requireNotNull(backgroundTaskRegistry) {
            "Background tasks are not available: no launcher configured (rootfs shell runner missing)"
        }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return registry.start(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                // 后台任务无超时；这里的 timeoutMillis 不被启动器使用
                timeoutMillis = 0L,
                stdin = null,
                bindMounts = preparedBindMounts(),
                shellCompatibilityMode = shellCompatibilityMode,
            ),
            owner = owner,
        )
    }

    /**
     * 给 shell 上下文的挂载表：只读挂载（/upload）先经 [ReadOnlyMountSnapshots.prepare]
     * 换成私有快照（readOnly=false 下发，runner 的 `require(!readOnly)` 才能通过）。
     * 无只读挂载或快照关闭时原样返回。
     */
    private fun preparedBindMounts(): List<WorkspaceBindMount> {
        if (readOnlySnapshotStorage == null) return bindMounts
        if (bindMounts.none { it.readOnly }) return bindMounts
        return readOnlySnapshots.prepare(bindMounts, readOnlySnapshotStorage)
    }

    /** 后台任务列表；[root] 为 null 时列出所有工作空间的。 */
    fun backgroundTasks(root: String? = null): List<WorkspaceTaskSnapshot> =
        backgroundTaskRegistry?.list(root).orEmpty()

    fun backgroundTask(id: String): WorkspaceTaskSnapshot? = backgroundTaskRegistry?.snapshot(id)

    fun killBackgroundTask(id: String): WorkspaceKillResult =
        backgroundTaskRegistry?.kill(id) ?: WorkspaceKillResult.NOT_FOUND

    /** 终止某工作空间全部运行中的后台任务，返回终止数量。 */
    fun killBackgroundTasks(root: String): Int = backgroundTaskRegistry?.killAll(root) ?: 0

    /**
     * 终止 [owner] 启动的全部后台任务，返回终止数量（跨工作空间）。
     *
     * 调用方是**生成被打断**那条路径：本轮的 owner 令牌随轮次更换，所以「这一轮起的任务」
     * 可以精确收掉，而更早轮次起的任务（已经写进对话、模型还在轮询的）不受影响。
     */
    fun killBackgroundTasksByOwner(owner: String): Int =
        backgroundTaskRegistry?.killByOwner(owner) ?: 0

    /** 是否支持后台任务（DI 配置了启动器）。 */
    val supportsBackgroundTasks: Boolean get() = backgroundTaskRegistry != null

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        /**
         * 由 Rootfs 目录判定的可用 shell。**public**：`app` 模块的终端页手上只有 linuxDir，
         * 与 [ProotShellRunner] 共用这一处判定才不会「终端说的 shell ≠ 实际启动的 shell」。
         * 判定只看文件是否存在，不解析软链——与既有 [hasRootfs] 同口径。
         */
        fun rootfsShellFor(linuxDir: File): String =
            if (File(linuxDir, "bin/bash").isFile) "/bin/bash" else "/bin/sh"

        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)

/**
 * 行窗口读取的结果。
 *
 * [moreLines] 表示**窗口之后还有内容**：让模型知道"这不是文件末尾"，能决定要不要继续翻
 * （比返回总行数便宜得多——总行数要把整个文件读一遍）。
 */
data class RootfsLineWindow(
    val lines: List<String>,
    val moreLines: Boolean,
)
