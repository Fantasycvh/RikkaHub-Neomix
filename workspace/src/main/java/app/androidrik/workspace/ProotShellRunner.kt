package app.androidrik.workspace

import java.io.File

/** guest 脚本的默认 `$0`（历史值，保持兼容）。 */
private const val DEFAULT_ARGV_TAG = "rikkahub"

/** [ProotShellRunner.start] 的结果：启动成功或预检失败（失败时携带原因文案）。 */
sealed interface ProotStart {
    data class Started(val process: Process) : ProotStart

    data class Failed(val message: String) : ProotStart
}

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        // 唯一进程树标记：超时/取消时靠它在 /proc 里找回进程树（destroyForcibly 在真机无效）
        val marker = newTaskMarker("shell")
        return when (val started = start(context, marker)) {
            is ProotStart.Failed -> WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = started.message,
            )
            is ProotStart.Started ->
                started.process.readResult(context.timeoutMillis, context.stdin, marker)
        }
    }

    /**
     * 预检 + 启动进程，**不等待**。
     *
     * 抽出来是为了让后台任务（`WorkspaceBackgroundTasks.ProotBackgroundLauncher`）复用同一套
     * 预检与命令行构造：两处若各自拼一遍命令行，早晚会漂移（绑定挂载、兼容模式、PROOT_*
     * 环境变量都有坑）。
     *
     * @return [ProotStart.Started] 为已启动进程；[ProotStart.Failed] 为预检不通过（同 [execute] 的 127 语义）。
     * @throws java.io.IOException 进程创建失败（`ProcessBuilder.start()` 抛出的异常照旧透传，
     *   调用方自行决定是回填错误结果还是记为失败任务）
     */
    fun start(context: WorkspaceShellContext, argvTag: String = DEFAULT_ARGV_TAG): ProotStart {
        if (!context.linuxDir.hasUsableRootfs()) {
            return ProotStart.Failed("Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return ProotStart.Failed("proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            return ProotStart.Failed("proot loader not found: ${loader.absolutePath}")
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot, argvTag))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                // Shell 兼容模式：禁用 PRoot seccomp 加速（部分内核/ROM 上 seccomp 导致命令异常，上游 03246406）
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                // proot 二进制为静态链接（DT_NEEDED 只有 libc/liblog），无需运行时库目录
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()

        return ProotStart.Started(process)
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
        argvTag: String = DEFAULT_ARGV_TAG,
    ): List<String> {
        // Shell 兼容模式（PROOT_NO_SECCOMP 纯 ptrace）：初始 cwd 移到 rootfs 内部 /root——
        // getcwd 在绑定挂载点（/workspace）反向解析返回 ENOSYS，bash 启动即报
        // "shell-init: getcwd"；chdir 正常，故启动后由命令容错 cd 到目标目录
        val initialCwd = if (context.shellCompatibilityMode) "/root" else context.prootCwd()
        val cdCommand = if (context.shellCompatibilityMode) {
            "cd -- \"\$1\" 2>/dev/null || true; eval \"\$2\""
        } else {
            "cd -- \"\$1\" && eval \"\$2\""
        }
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            initialCwd,
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定，抑制各类 CLI 的交互行为（确认提示/分页器/颜色转义）
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            // 无 bash 的 rootfs 回退 /bin/sh（对齐上游 298e2771）；脚本体只用 cd/eval，两种 shell 都成立
            WorkspaceManager.rootfsShellFor(context.linuxDir),
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            cdCommand,
            // 该槽位是 guest 脚本的 $0：默认 "rikkahub"，后台任务传入唯一标记以便终止时在 /proc 里定位
            argvTag,
            context.prootCwd(),
            context.command,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
