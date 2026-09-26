package app.androidrik.workspace

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 工作空间**后台任务**：让长命令（构建、下载、训练脚本）不再阻塞一轮对话。
 *
 * 出处：`docs/peer-apps-benchmark.md` 的 `workspace_run_background` /
 * `workspace_task_status` / `workspace_kill_task` 三件套 + `TailBuffer`，以及
 * `docs/roadmap.md` 工作空间条目 ④「长任务不阻塞对话」。
 *
 * 设计要点（与同步 `workspace_shell` 的差别）：
 *
 * 1. **输出不进上下文**：同步 shell 把 stdout 直接回填给模型，长命令的输出会挤爆窗口；
 *    后台任务只把输出写进**尾部环形缓冲**（[TailBuffer]），模型按需轮询最后若干字符。
 * 2. **无超时**：后台任务的定义就是「比一轮对话活得更久」，超时参数对它没有意义；
 *    失控任务由**并发上限**（[WorkspaceTaskRegistry]）+ `workspace_kill_task` 约束。
 * 3. **内存态、进程内**：任务表不持久化，应用进程结束即全部消失（子进程随之被杀）。
 *    v1 明确不做「进程重启后接管旧任务」——那需要脱离应用进程的常驻服务，收益不足。
 * 4. **可测**：本文件除了 [ProotBackgroundLauncher] 全是纯逻辑/接口，可上 JVM 单测
 *    （`WorkspaceTaskRegistryTest`）；真实进程启动被隔离在 [WorkspaceTaskLauncher] 接口之后。
 */

/** 单个流尾部保留的默认字符数。8K 字符 ≈ 2K token，够看清编译/下载的结尾。 */
const val DEFAULT_TASK_TAIL_CHARS = 8 * 1024

/** 同时运行的后台任务数上限。 */
const val DEFAULT_MAX_CONCURRENT_TASKS = 4

/** 已结束任务在内存中保留的条数上限（超出时淘汰最旧的已结束任务）。 */
const val DEFAULT_MAX_FINISHED_TASKS = 20

/** 尾部缓冲快照。 */
data class TailSnapshot(
    /** 保留的尾部文本（最多 [capacity] 字符）。 */
    val text: String,
    /** 累计写入的字符总数（含被丢弃的部分），用于判断输出规模。 */
    val totalChars: Long,
    /** 是否发生过丢弃（前面还有更多输出）。 */
    val truncated: Boolean,
    val capacity: Int,
)

/**
 * 尾部环形缓冲：只保留**最近** [capacityChars] 个字符，同时记住累计规模与是否截断。
 *
 * 用尾部而非头部，是因为「构建失败原因」「下载进度结尾」都在输出的最后；而同步 shell 的
 * `StreamCollector` 保留的是头部（那里适合一次性命令，不适合长任务）。
 *
 * 线程安全：读（[snapshot]）写（[append]）会从采集线程/调用线程并发发生，故全部同步。
 */
class TailBuffer(val capacityChars: Int = DEFAULT_TASK_TAIL_CHARS) {
    init {
        require(capacityChars > 0) { "capacityChars must be positive: $capacityChars" }
    }

    private val builder = StringBuilder()
    private var total = 0L
    private var dropped = false

    fun append(text: CharSequence) {
        if (text.isEmpty()) return
        synchronized(builder) {
            total += text.length
            if (text.length >= capacityChars) {
                // 单批就超过容量：只留这批的尾部，之前的内容全部丢弃
                builder.setLength(0)
                builder.append(text, text.length - capacityChars, text.length)
                dropped = true
                return
            }
            val overflow = builder.length + text.length - capacityChars
            if (overflow > 0) {
                builder.delete(0, overflow)
                dropped = true
            }
            builder.append(text)
        }
    }

    fun snapshot(): TailSnapshot = synchronized(builder) {
        TailSnapshot(
            text = builder.toString(),
            totalChars = total,
            truncated = dropped,
            capacity = capacityChars,
        )
    }
}

/**
 * 把 [stream] 的文本泵入 [sink]，直到 EOF。
 *
 * 流在进程被强杀时会抛出 `IOException`（`InterruptedIOException` 等），此时保留已读内容即可；
 * 异常不能逃逸，否则线程默认异常处理会崩掉应用（与 `StreamCollector` 同因）。
 */
fun pumpText(
    stream: InputStream,
    sink: TailBuffer,
    charset: Charset = StandardCharsets.UTF_8,
) {
    try {
        stream.reader(charset).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                sink.append(buffer.concatToString(0, read))
            }
        }
    } catch (_: IOException) {
        // 进程被强杀/超时终止导致流关闭，保留已读内容
    }
}

/** 后台任务的运行状态。 */
enum class WorkspaceTaskStatus {
    /** 仍在运行。 */
    RUNNING,

    /** 已自行结束（[WorkspaceTaskSnapshot.exitCode] 可能为 0 也可能非 0）。 */
    EXITED,

    /** 被 `workspace_kill_task` 终止。 */
    KILLED,

    /** 启动失败或进程无法回收（原因见 [WorkspaceTaskSnapshot.stderr]）。 */
    FAILED,
}

/** 后台任务的可读快照（跨线程安全：一次性拷贝，不暴露内部可变状态）。 */
data class WorkspaceTaskSnapshot(
    val id: String,
    val root: String,
    val command: String,
    val cwd: String,
    val status: WorkspaceTaskStatus,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stdoutTotalChars: Long,
    val stderrTotalChars: Long,
    val outputTruncated: Boolean,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
) {
    val isRunning: Boolean get() = status == WorkspaceTaskStatus.RUNNING

    /** 运行时长（毫秒）；未结束时按 [nowMillis] 计算。 */
    fun durationMillis(nowMillis: Long): Long = (finishedAtMillis ?: nowMillis) - startedAtMillis
}

/** `workspace_kill_task` 的结果，便于工具层给出精确文案。 */
enum class WorkspaceKillResult {
    /** 已下发终止信号（状态随后由回收线程改写为 KILLED）。 */
    KILLED,

    /** 任务已自行结束，无需终止。 */
    ALREADY_FINISHED,

    /** 任务 id 不存在（可能是已被淘汰，或进程重启后任务表丢失）。 */
    NOT_FOUND,
}

/** 已启动的后台进程句柄；真实实现包装 `java.lang.Process`，单测用假实现。 */
interface WorkspaceBackgroundHandle {
    /** 强制终止进程树。 */
    fun kill()

    /** 阻塞直到进程退出并返回退出码；无法回收时抛异常。 */
    fun awaitExit(): Int
}

/**
 * 后台命令启动器。
 *
 * 实现负责：启动进程、把两个输出流泵入给定 sink（daemon 线程）、返回 kill/等待句柄。
 * 启动失败（rootfs 未安装、proot 缺失、进程创建异常）时，把原因写入 [stderr] 并返回 `null`，
 * 由注册表记成一条 [WorkspaceTaskStatus.FAILED] 任务——模型能通过 `_status` 看到原因，
 * 而不是收到一个不透明的异常。
 */
interface WorkspaceTaskLauncher {
    fun launch(
        context: WorkspaceShellContext,
        stdout: TailBuffer,
        stderr: TailBuffer,
    ): WorkspaceBackgroundHandle?
}

/**
 * 后台任务注册表：启动 / 查询 / 终止，并约束并发数与内存占用。
 *
 * 并发上限而不是队列：命令被排队会带来「为什么还没开始跑」的困惑，直接拒绝并让模型
 * 先处理运行中的任务更可解释（工具错误文案里会列出正在运行的任务）。
 */
class WorkspaceTaskRegistry(
    private val launcher: WorkspaceTaskLauncher,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT_TASKS,
    private val maxFinished: Int = DEFAULT_MAX_FINISHED_TASKS,
    val tailChars: Int = DEFAULT_TASK_TAIL_CHARS,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) {
    private class Entry(
        val id: String,
        val root: String,
        val command: String,
        val cwd: String,
        val startedAtMillis: Long,
        val stdout: TailBuffer,
        val stderr: TailBuffer,
    ) {
        @Volatile
        var status: WorkspaceTaskStatus = WorkspaceTaskStatus.RUNNING

        @Volatile
        var exitCode: Int? = null

        @Volatile
        var finishedAtMillis: Long? = null

        /** 是否已下发过 kill（决定回收线程把状态写成 KILLED 还是 EXITED）。 */
        @Volatile
        var killRequested: Boolean = false

        @Volatile
        var handle: WorkspaceBackgroundHandle? = null
    }

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Entry>()

    /** 启动一个后台任务；超出并发上限时抛 [IllegalStateException]（工具层转成可见错误）。 */
    fun start(context: WorkspaceShellContext): WorkspaceTaskSnapshot {
        val entry: Entry
        synchronized(lock) {
            evictFinishedLocked()
            val running = tasks.values.count { it.status == WorkspaceTaskStatus.RUNNING }
            check(running < maxConcurrent) {
                "Background task limit reached ($running running, max $maxConcurrent). " +
                    "Inspect them with workspace_task_status, then free a slot with workspace_kill_task."
            }
            // id 碰撞概率极低（8 位十六进制），撞了就重取，避免覆盖已有任务
            var id = newId()
            while (tasks.containsKey(id)) id = newId()
            entry = Entry(
                id = id,
                root = context.root,
                command = context.command,
                cwd = context.cwd,
                startedAtMillis = now(),
                stdout = TailBuffer(tailChars),
                stderr = TailBuffer(tailChars),
            )
            tasks[id] = entry
        }

        // 启动在锁外：launch 会做进程创建等重操作，不应占着注册表锁
        val handle = try {
            launcher.launch(context, entry.stdout, entry.stderr)
        } catch (e: Exception) {
            entry.stderr.append("启动失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (handle == null) {
            if (entry.stderr.snapshot().text.isEmpty()) {
                entry.stderr.append("启动失败：后台任务启动器未返回句柄")
            }
            entry.status = WorkspaceTaskStatus.FAILED
            entry.finishedAtMillis = now()
            return snapshotOf(entry)
        }

        entry.handle = handle
        // kill 可能在 handle 赋值前就被下发（见 kill()）：这里补一次，避免终止请求丢失
        if (entry.killRequested) handle.kill()
        startReaper(entry, handle)
        return snapshotOf(entry)
    }

    /** 列出任务；[root] 非空时只列该工作空间的。按启动时间正序（旧任务在前）。 */
    fun list(root: String? = null): List<WorkspaceTaskSnapshot> = synchronized(lock) {
        tasks.values
            .filter { root == null || it.root == root }
            .map { snapshotOf(it) }
            .sortedBy { it.startedAtMillis }
    }

    /** 运行中的任务数。 */
    fun runningCount(root: String? = null): Int = synchronized(lock) {
        tasks.values.count { it.status == WorkspaceTaskStatus.RUNNING && (root == null || it.root == root) }
    }

    fun snapshot(id: String): WorkspaceTaskSnapshot? = synchronized(lock) {
        tasks[id]?.let { snapshotOf(it) }
    }

    /** 终止任务。[id] 不存在返回 [WorkspaceKillResult.NOT_FOUND]，已结束返回 ALREADY_FINISHED。 */
    fun kill(id: String): WorkspaceKillResult {
        val entry = synchronized(lock) { tasks[id] } ?: return WorkspaceKillResult.NOT_FOUND
        if (entry.status != WorkspaceTaskStatus.RUNNING) return WorkspaceKillResult.ALREADY_FINISHED
        entry.killRequested = true
        // handle 可能尚未赋值（launch 在注册表锁外执行）：此时终止请求由 start() 收尾下发
        entry.handle?.kill()
        return WorkspaceKillResult.KILLED
    }

    /** 终止某工作空间下所有运行中的任务，返回终止数量（删除工作区前调用）。 */
    fun killAll(root: String? = null): Int = synchronized(lock) {
        tasks.values
            .filter { it.status == WorkspaceTaskStatus.RUNNING && (root == null || it.root == root) }
            .map { it.id }
    }.count { kill(it) == WorkspaceKillResult.KILLED }

    private fun startReaper(entry: Entry, handle: WorkspaceBackgroundHandle) {
        Thread {
            val code = try {
                handle.awaitExit()
            } catch (_: Throwable) {
                null
            }
            entry.exitCode = code
            entry.finishedAtMillis = now()
            entry.status = when {
                entry.killRequested -> WorkspaceTaskStatus.KILLED
                code == null -> WorkspaceTaskStatus.FAILED
                else -> WorkspaceTaskStatus.EXITED
            }
            // 结束即淘汰：只在 start() 里淘汰会有一段时间超限（连续完成的旧任务还留着占内存）
            synchronized(lock) { evictFinishedLocked() }
        }.apply {
            isDaemon = true
            name = "workspace-task-reaper-${entry.id}"
            start()
        }
    }

    /** 淘汰最旧的已结束任务，保证任务表不会无限增长（运行中的任务永不淘汰）。 */
    private fun evictFinishedLocked() {
        val finished = tasks.values
            .filter { it.status != WorkspaceTaskStatus.RUNNING }
            .sortedBy { it.finishedAtMillis ?: it.startedAtMillis }
        val excess = finished.size - maxFinished
        if (excess > 0) {
            for (index in 0 until excess) tasks.remove(finished[index].id)
        }
    }

    private fun snapshotOf(entry: Entry): WorkspaceTaskSnapshot {
        val stdout = entry.stdout.snapshot()
        val stderr = entry.stderr.snapshot()
        return WorkspaceTaskSnapshot(
            id = entry.id,
            root = entry.root,
            command = entry.command,
            cwd = entry.cwd,
            status = entry.status,
            exitCode = entry.exitCode,
            stdout = stdout.text,
            stderr = stderr.text,
            stdoutTotalChars = stdout.totalChars,
            stderrTotalChars = stderr.totalChars,
            outputTruncated = stdout.truncated || stderr.truncated,
            startedAtMillis = entry.startedAtMillis,
            finishedAtMillis = entry.finishedAtMillis,
        )
    }
}

/**
 * 真实实现：复用 [ProotShellRunner] 的预检与命令行构造，启动后立即返回句柄。
 *
 * 只支持 PRoot rootfs 路径——后台任务的意义在于「跑 rootfs 里的长命令」；宿主机 shell
 * （[HostShellRunner]，兼容模式）无需 rootfs，本轮不提供后台变体。
 */
class ProotBackgroundLauncher(private val runner: ProotShellRunner) : WorkspaceTaskLauncher {
    override fun launch(
        context: WorkspaceShellContext,
        stdout: TailBuffer,
        stderr: TailBuffer,
    ): WorkspaceBackgroundHandle? {
        val started = try {
            runner.start(context)
        } catch (e: Exception) {
            stderr.append("启动失败: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (started is ProotStart.Failed) {
            stderr.append(started.message)
            return null
        }
        val process = (started as ProotStart.Started).process

        // 后台任务暂不接收 stdin：立即关闭管道，否则按 isatty 判断的工具会永久等输入
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // 进程已提前退出，忽略
        }

        pumpAsync(process.inputStream, stdout, "workspace-task-out")
        pumpAsync(process.errorStream, stderr, "workspace-task-err")

        return object : WorkspaceBackgroundHandle {
            override fun kill() {
                process.destroyForcibly()
            }

            override fun awaitExit(): Int = process.waitFor()
        }
    }

    private fun pumpAsync(stream: InputStream, sink: TailBuffer, threadName: String) {
        Thread {
            pumpText(stream, sink)
        }.apply {
            // daemon：即使 proot 孙进程残留 fd 让 read() 永久阻塞，也不阻止 JVM 退出
            isDaemon = true
            name = threadName
            start()
        }
    }
}
