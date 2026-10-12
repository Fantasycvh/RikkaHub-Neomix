package app.androidrik.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

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

/**
 * 后台任务的默认最长存活时长（30 分钟）。
 *
 * **必须有上限**：后台任务刻意没有命令级超时（那是它存在的理由），于是「模型起了 4 个长命令后本轮
 * 结束」会让手机一直跑下去。并发上限只管数量、不管时长，两者互补。[WorkspaceTaskRegistry] 在
 * 到点时按同一条终止路径杀整棵树并把状态记为 [WorkspaceTaskStatus.TIMED_OUT]。
 * `<= 0` 表示不限制（仅测试/特殊配置用）。
 */
const val DEFAULT_MAX_TASK_LIFETIME_MS = 30 * 60 * 1000L

/** 超时终止后额外等待回收的时间（收尸用；进程已收到 SIGKILL，通常毫秒级返回）。 */
private const val TIMED_OUT_REAP_GRACE_MS = 5_000L

/** 不设时长上限时的等待分片（只用一次极长等待怕平台实现把毫秒换算成纳秒溢出）。 */
private const val UNLIMITED_WAIT_SLICE_MS = 60 * 60 * 1000L

/** 任务日志的保留时长：按修改时间淘汰更旧的文件（任务表重启即丢，日志不该无限堆积）。 */
const val DEFAULT_TASK_LOG_RETENTION_MS = 24 * 60 * 60 * 1000L

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
) = pumpText(stream, charset) { sink.append(it) }

/**
 * 同上，但输出交给任意 [sink]（后台任务用它做**双写**：尾部缓冲给上下文，文件给完整日志）。
 */
fun pumpText(
    stream: InputStream,
    charset: Charset = StandardCharsets.UTF_8,
    sink: (CharSequence) -> Unit,
) {
    try {
        stream.reader(charset).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                sink(buffer.concatToString(0, read))
            }
        }
    } catch (_: IOException) {
        // 进程被强杀/超时终止导致流关闭，保留已读内容
    }
}

/** 任务日志目录（工作区文件区下）。用点前缀是为了和用户文件区分，但仍可在文件浏览器里看到。 */
const val TASK_LOG_DIR_NAME = ".tasks"

/** 一个任务的完整输出日志（宿主路径）。 */
data class WorkspaceTaskLogs(val stdout: File, val stderr: File)

fun taskLogDir(filesDir: File): File = File(filesDir, TASK_LOG_DIR_NAME)

fun taskLogsFor(filesDir: File, id: String): WorkspaceTaskLogs = WorkspaceTaskLogs(
    stdout = File(taskLogDir(filesDir), "$id.stdout.log"),
    stderr = File(taskLogDir(filesDir), "$id.stderr.log"),
)

/**
 * 删除 [retentionMillis] 之前修改过的任务日志，返回删除数量。
 *
 * 为什么需要：任务表是内存态（重启即丢），日志却在磁盘上——不清就永远堆着。日志按任务 id 命名，
 * 任务一旦重启就再也引用不到，所以按**修改时间**淘汰是最简单且不会误删正在写的新日志的做法。
 */
fun pruneTaskLogs(filesDir: File, retentionMillis: Long, nowMillis: Long): Int {
    if (retentionMillis <= 0L) return 0
    val cutoff = nowMillis - retentionMillis
    return taskLogDir(filesDir).listFiles().orEmpty().count { file ->
        file.isFile && file.lastModified() > 0L && file.lastModified() < cutoff && file.delete()
    }
}

/**
 * 双写 sink：尾部缓冲（给上下文/轮询）+ 完整日志文件（给事后回看）。
 *
 * 后台任务刻意不让输出进上下文，但**尾部 8KB 之外的内容不该凭空消失**——长编译的失败原因常在早期，
 * 模型需要时用 `workspace_read_file` 读日志文件即可。写入失败（磁盘满等）只降级为「没有日志」，
 * 不影响任务本身与尾部缓冲。
 */
class TaskLogSink(private val tail: TailBuffer, private val file: File?) {
    private val writer: Writer? = file?.let { target ->
        runCatching {
            target.parentFile?.mkdirs()
            target.bufferedWriter(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /** 日志是否真的落盘（写不进去时为 false，调用方据此决定要不要把路径告诉模型）。 */
    val isEnabled: Boolean get() = writer != null

    fun append(text: CharSequence) {
        tail.append(text)
        val target = writer ?: return
        runCatching {
            target.write(text.toString())
            // 每块都 flush：任务可能跑很久，用户/模型读日志时不该只看到缓冲里的内容
            target.flush()
        }.onFailure { close() }
    }

    fun close() {
        runCatching { writer?.close() }
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

    /** 超过任务时长上限被自动终止（[WorkspaceTaskSnapshot.lifetimeLimitMillis]）。 */
    TIMED_OUT,

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
    /** 任务时长上限；`0` 表示不限制。 */
    val lifetimeLimitMillis: Long = 0L,
    /** 完整输出的落盘位置（**rootfs 内可见路径**，模型可用 `workspace_read_file` 读）；失败时为 null。 */
    val stdoutLogPath: String? = null,
    val stderrLogPath: String? = null,
) {
    val isRunning: Boolean get() = status == WorkspaceTaskStatus.RUNNING

    /** 运行时长（毫秒）；未结束时按 [nowMillis] 计算。 */
    fun durationMillis(nowMillis: Long): Long = (finishedAtMillis ?: nowMillis) - startedAtMillis

    /** 距被自动终止还剩多少毫秒；不限制时返回 null，已结束时返回 null。 */
    fun remainingMillis(nowMillis: Long): Long? {
        if (!isRunning || lifetimeLimitMillis <= 0L) return null
        return (startedAtMillis + lifetimeLimitMillis - nowMillis).coerceAtLeast(0L)
    }
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
    /** 完整 stdout 日志的**rootfs 内可见路径**（未落盘时为 null）。 */
    val stdoutLogPath: String?
        get() = null

    /** 完整 stderr 日志的 **rootfs 内可见路径**（未落盘时为 null）。 */
    val stderrLogPath: String?
        get() = null

    /** 强制终止**整棵**进程树（不是只杀直接子进程，否则孙进程会变孤儿继续跑）。 */
    fun kill()

    /**
     * 等待退出。
     *
     * @param timeoutMillis 最长等待时间；超时返回 null（进程仍在跑）
     * @return 退出码，或超时时的 null
     */
    fun awaitExit(timeoutMillis: Long): Int?
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
    /**
     * @param marker 该任务的唯一进程树标记（进 argv 的 `$0` 槽位）。终止时靠它在 `/proc` 里定位
     *   整棵进程树——`Process.destroyForcibly()` 在 Android 上无效，见 [ProcessSignals] 的类注释。
     */
    fun launch(
        context: WorkspaceShellContext,
        marker: String,
        logs: WorkspaceTaskLogs,
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
    /** 任务最长存活时长；`<= 0` 表示不限制。到点按同一条终止路径杀整棵树并记 `TIMED_OUT`。 */
    val maxLifetimeMillis: Long = DEFAULT_MAX_TASK_LIFETIME_MS,
    private val logRetentionMillis: Long = DEFAULT_TASK_LOG_RETENTION_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) {
    private class Entry(
        val id: String,
        val root: String,
        val command: String,
        val cwd: String,
        val startedAtMillis: Long,
        val marker: String,
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

        /** 是否由时长看门狗终止（优先于 killRequested：用户没动手，别记成 KILLED）。 */
        @Volatile
        var timedOut: Boolean = false

        /** 完整日志的**rootfs 内可见路径**（写盘失败时为 null）。 */
        @Volatile
        var stdoutLogPath: String? = null

        @Volatile
        var stderrLogPath: String? = null

        /** 日志文件的宿主路径，用于淘汰任务时一并删除。 */
        lateinit var logFiles: WorkspaceTaskLogs

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
                marker = newTaskMarker(id),
                stdout = TailBuffer(tailChars),
                stderr = TailBuffer(tailChars),
            ).apply {
                logFiles = taskLogsFor(context.filesDir, id)
            }
            tasks[id] = entry
        }

        // 过期日志清理放在锁外：只是目录列举 + 删除，不影响任务表；每个新任务顺带清一次即可
        runCatching { pruneTaskLogs(context.filesDir, logRetentionMillis, now()) }

        // 启动在锁外：launch 会做进程创建等重操作，不应占着注册表锁
        val handle = try {
            launcher.launch(context, entry.marker, entry.logFiles, entry.stdout, entry.stderr)
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
        entry.stdoutLogPath = handle.stdoutLogPath
        entry.stderrLogPath = handle.stderrLogPath
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
        // 状态**立即**置终态：信号已经发出，把「已下发终止」当作终态上报，避免调用方轮询等一个
        // 可能永远不会返回的 waitFor（真机实测的教训）。回收线程随后只补 exitCode。
        if (entry.status == WorkspaceTaskStatus.RUNNING) {
            entry.status = WorkspaceTaskStatus.KILLED
            entry.finishedAtMillis = now()
            synchronized(lock) { evictFinishedLocked() }
        }
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
            var code: Int? = null
            try {
                val limit = maxLifetimeMillis
                if (limit <= 0L) {
                    // 不限制：分片等待，避免把超大 timeout 交给平台实现（nanos 溢出风险）
                    while (code == null) code = handle.awaitExit(UNLIMITED_WAIT_SLICE_MS)
                } else {
                    val deadline = entry.startedAtMillis + limit
                    code = handle.awaitExit((deadline - now()).coerceAtLeast(0L))
                    if (code == null) {
                        // 到点仍在跑：按同一条终止路径杀整棵树；状态记 TIMED_OUT（用户没动手）
                        entry.timedOut = true
                        handle.kill()
                        code = handle.awaitExit(TIMED_OUT_REAP_GRACE_MS)
                    }
                }
            } catch (_: Throwable) {
                code = null
            }
            entry.exitCode = code
            if (entry.finishedAtMillis == null) entry.finishedAtMillis = now()
            // 先写终态的赢（kill() / 看门狗 / 正常退出三选一），回收线程只补 exitCode
            if (entry.status == WorkspaceTaskStatus.RUNNING) {
                entry.status = when {
                    entry.timedOut -> WorkspaceTaskStatus.TIMED_OUT
                    entry.killRequested -> WorkspaceTaskStatus.KILLED
                    code == null -> WorkspaceTaskStatus.FAILED
                    else -> WorkspaceTaskStatus.EXITED
                }
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
            for (index in 0 until excess) {
                val evicted = tasks.remove(finished[index].id) ?: continue
                // 日志与被淘汰的任务同寿：任务表都不再引用它们了，留着只会占磁盘
                runCatching {
                    evicted.logFiles.stdout.delete()
                    evicted.logFiles.stderr.delete()
                }
            }
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
            lifetimeLimitMillis = maxLifetimeMillis.coerceAtLeast(0L),
            stdoutLogPath = entry.stdoutLogPath,
            stderrLogPath = entry.stderrLogPath,
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
        marker: String,
        logs: WorkspaceTaskLogs,
        stdout: TailBuffer,
        stderr: TailBuffer,
    ): WorkspaceBackgroundHandle? {
        val started = try {
            runner.start(context, marker)
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

        val outSink = TaskLogSink(stdout, logs.stdout)
        val errSink = TaskLogSink(stderr, logs.stderr)
        pumpAsync(process.inputStream, outSink, "workspace-task-out")
        pumpAsync(process.errorStream, errSink, "workspace-task-err")

        // 日志路径以 **rootfs 内可见路径** 上报（模型用 workspace_read_file 读的就是这个路径）；
        // 写盘失败时给 null，让上层如实告诉模型「没有完整日志」而不是给一个不存在的路径
        val outPath = if (outSink.isEnabled) guestLogPath(logs.stdout) else null
        val errPath = if (errSink.isEnabled) guestLogPath(logs.stderr) else null

        return object : WorkspaceBackgroundHandle {
            override val stdoutLogPath: String? = outPath
            override val stderrLogPath: String? = errPath

            override fun kill() {
                // 必须按 marker 找回整棵进程树再逐个 SIGKILL：实测 destroyForcibly() 对本机 proot
                // 子进程无效，而只杀 proot 又会让 guest 的子孙变孤儿继续跑（见 ProcessSignals 类注释）
                val killed = runCatching {
                    killProcessTreeByMarker(RealProcFs, currentPid(), marker)
                }.getOrDefault(0)
                if (killed == 0) {
                    // 进程表没找到（命令已自行结束/读 /proc 失败）：退回平台常规做法
                    process.destroyForcibly()
                }
            }

            override fun awaitExit(timeoutMillis: Long): Int? =
                if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) process.exitValue() else null
        }
    }

    /** 工作区文件区的宿主路径 → rootfs 内的可见路径（模型/用户在 rootfs 里用的路径）。 */
    private fun guestLogPath(file: File): String =
        "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/${TASK_LOG_DIR_NAME}/${file.name}"

    private fun pumpAsync(stream: InputStream, sink: TaskLogSink, threadName: String) {
        Thread {
            try {
                pumpText(stream) { sink.append(it) }
            } finally {
                // 流到 EOF 就收尾：把日志 flush 落盘，否则最后一块可能留在缓冲里
                sink.close()
            }
        }.apply {
            // daemon：即使 proot 孙进程残留 fd 让 read() 永久阻塞，也不阻止 JVM 退出
            isDaemon = true
            name = threadName
            start()
        }
    }
}
