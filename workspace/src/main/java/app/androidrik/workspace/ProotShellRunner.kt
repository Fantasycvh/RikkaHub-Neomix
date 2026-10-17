package app.androidrik.workspace

import java.io.File

/** guest 脚本的默认 `$0`（历史值，保持兼容）。 */
private const val DEFAULT_ARGV_TAG = "rikkahub"

/**
 * proot 运行时依赖库的打包名 → 真实文件名。
 *
 * AGP 只从 `jniLibs` 提取 `lib*.so`，而 proot 的 `DT_NEEDED` 是 `libtalloc.so.2`（以 `.2` 结尾，
 * 不会被提取），所以打包名只能是 `libtalloc2.so`，运行时再复制成真实文件名供链接器加载。
 * `libandroid-shmem.so` 打包名本来就合规，但仍要落到可写目录与 talloc 放在一起。
 */
private val PROOT_RUNTIME_LIBS = listOf(
    "libtalloc2.so" to "libtalloc.so.2",
    "libandroid-shmem.so" to "libandroid-shmem.so",
)

/** 可写库目录名（位于 workspace 的 tempDir 下，随工作空间一起清理）。 */
private const val PROOT_LIB_DIR_NAME = "proot-libs"

/**
 * 在 [tempDir] 下准备 proot 运行时库的可写目录，返回该目录（用作 `LD_LIBRARY_PATH`）；
 * 源文件缺失时返回 `null`。
 *
 * 供两处起进程的代码共用：[ProotShellRunner]（命令执行 / 后台任务）与 app 模块的终端会话。
 */
fun prepareProotRuntimeLibs(nativeLibraryDir: File, tempDir: File): File? =
    prepareProotLibDir(nativeLibraryDir, File(tempDir, PROOT_LIB_DIR_NAME))

/**
 * [prepareProotRuntimeLibs] 的实现（可指定目录，便于单测覆盖幂等与缺源行为）。
 *
 * 链接器只认 `DT_NEEDED` 里的真实文件名，因此目标文件名由 [PROOT_RUNTIME_LIBS] 决定。
 *
 * - 首次调用：创建目录并复制两个库。
 * - 幂等：目标已存在且大小与源一致时跳过复制（每次执行 shell 都会调用，没必要反复写）。
 * - 任一源文件缺失：返回 `null`，且不创建/不复制任何东西（只暴露半套库时 proot 的报错更难懂）。
 */
internal fun prepareProotLibDir(nativeLibraryDir: File, libDir: File): File? {
    val pairs = PROOT_RUNTIME_LIBS.map { (sourceName, targetName) ->
        File(nativeLibraryDir, sourceName) to File(libDir, targetName)
    }
    // 先确认源齐全再动手，避免留下“只有一半依赖”的运行目录
    if (pairs.any { (source, _) -> !source.isFile }) return null
    if (!libDir.isDirectory && !libDir.mkdirs() && !libDir.isDirectory) return null

    pairs.forEach { (source, target) ->
        if (target.isFile && target.length() == source.length()) return@forEach
        // 先写临时文件再 rename：中途被杀也不会留下半截 .so 被链接器读到
        val staging = File(libDir, target.name + ".tmp")
        source.copyTo(staging, overwrite = true)
        if (!staging.renameTo(target)) {
            staging.delete()
            source.copyTo(target, overwrite = true)
        }
    }
    return libDir
}

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
        // 新版 proot 依赖 libtalloc.so.2 / libandroid-shmem.so（见 PROOT_RUNTIME_LIBS），
        // 它们只能先落在可写目录，再由 LD_LIBRARY_PATH 暴露给链接器
        val prootLibDir = prepareProotRuntimeLibs(nativeLibraryDir, context.tempDir)
            ?: return ProotStart.Failed(
                "proot runtime libraries not found in ${nativeLibraryDir.absolutePath} " +
                    "(libtalloc2.so, libandroid-shmem.so)"
            )
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
                // proot 自身的动态依赖走这里（app 进程一般没有 LD_LIBRARY_PATH，有则保留其后）
                val inheritedLibPath = environment()["LD_LIBRARY_PATH"]
                environment()["LD_LIBRARY_PATH"] = if (inheritedLibPath.isNullOrBlank()) {
                    prootLibDir.absolutePath
                } else {
                    "${prootLibDir.absolutePath}:$inheritedLibPath"
                }
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
