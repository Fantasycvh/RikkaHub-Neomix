package app.androidrik.rikkahub.workspace

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.sync.WorkspaceBackupHelper
import app.androidrik.workspace.DEFAULT_MAX_TASK_LIFETIME_MS
import app.androidrik.workspace.ProotBackgroundLauncher
import app.androidrik.workspace.ProotShellRunner
import app.androidrik.workspace.WorkspaceBindMount
import app.androidrik.workspace.WorkspaceKillResult
import app.androidrik.workspace.WorkspaceManager
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 后台任务三件套的**真机**验证：真实 PRoot 进程 + 真实输出采集 + 真实强杀。
 *
 * 为什么必须是 instrumented：JVM 单测（`workspace` 模块的 `WorkspaceBackgroundTasksTest`）用
 * `FakeLauncher` 替代了进程启动，能证明「调度与状态机」正确，**证明不了**
 * 「proot 真的能起、输出真的被泵进 TailBuffer、kill 真的能杀掉进程树」——native `libproot_exec.so`
 * 只打进 APK，且 `destroyForcibly()` 的行为只有在真进程上才有意义。
 *
 * 与线上路径的差异只有一处：这里直接构造 `WorkspaceManager` 而不是走 Koin，其余
 * （rootfs 预检、绑定挂载、`RootfsPatcher`、命令行构造、采集线程）完全相同。
 *
 * 设备上若没有任何装了 rootfs 的工作区，测试**跳过**（`assumeTrue`）而不是失败——
 * 「没装 rootfs」是环境缺省，不是实现缺陷。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceBackgroundTaskInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var manager: WorkspaceManager
    private lateinit var root: String

    /** 与 DI（RepositoryModule）同款装配：绑定挂载必须一致，否则 rootfs 内看不到工作区文件区。 */
    private fun buildManager(
        maxBackgroundTasks: Int = 4,
        maxLifetimeMillis: Long = DEFAULT_MAX_TASK_LIFETIME_MS,
    ): WorkspaceManager {
        val prootRunner = ProotShellRunner(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        )
        return WorkspaceManager(
            baseDir = WorkspaceBackupHelper.workspacesDir(context),
            shellRunner = prootRunner,
            bindMounts = listOf(
                WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"),
                WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"),
                WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"),
            ),
            backgroundLauncher = ProotBackgroundLauncher(prootRunner),
            maxBackgroundTasks = maxBackgroundTasks,
            maxBackgroundTaskLifetimeMillis = maxLifetimeMillis,
        )
    }

    @Before
    fun setUp() {
        manager = buildManager()

        // 取一个真的装了 rootfs 的工作区；没有则跳过（环境缺省，不是实现缺陷）
        val candidate = WorkspaceBackupHelper.workspacesDir(context)
            .listFiles()
            ?.filter { it.isDirectory && manager.hasRootfs(it.name) }
            ?.firstOrNull()
        assumeTrue("设备上没有已安装 rootfs 的工作区，跳过后台任务真机验证", candidate != null)
        root = candidate!!.name
        manager.ensureWorkspace(root)
        Log.i(TAG, "using workspace root=$root")
    }

    /** 端到端①：启动 → 轮询 → 拿到退出码与输出尾部（真实 proot 进程跑完）。 */
    @Test
    fun backgroundCommandRunsToCompletionAndReportsTail() {
        val task = manager.startBackgroundCommand(
            root,
            "echo bg-start; sleep 2; echo bg-done; exit 7",
        )
        assertEquals(WorkspaceTaskStatus.RUNNING, task.status)
        assertTrue(task.isRunning)
        Log.i(TAG, "started task=${task.id}")

        val finished = awaitTerminal(task.id, timeoutMillis = 60_000)
        assertEquals(WorkspaceTaskStatus.EXITED, finished.status)
        assertEquals(7, finished.exitCode)
        assertTrue("stdout 应含起始标记，实际=${finished.stdout}", finished.stdout.contains("bg-start"))
        assertTrue("stdout 应含结束标记，实际=${finished.stdout}", finished.stdout.contains("bg-done"))
        assertTrue("已完成任务应仍可查（保留 20 条以内）", manager.backgroundTask(task.id) != null)
    }

    /** 端到端②：长任务被 `killBackgroundTask` 真的杀掉（不是等它自己退出）。 */
    @Test
    fun killTerminatesRunningCommand() {
        val task = manager.startBackgroundCommand(root, "echo before-sleep; sleep 300; echo never")
        // 等第一行输出落进缓冲，确认采集线程在工作
        awaitStdout(task.id, "before-sleep", timeoutMillis = 30_000)

        assertEquals(WorkspaceKillResult.KILLED, manager.killBackgroundTask(task.id))

        val killed = awaitTerminal(task.id, timeoutMillis = 30_000)
        assertEquals(WorkspaceTaskStatus.KILLED, killed.status)
        assertTrue("被杀后不应再出现后续输出", !killed.stdout.contains("never"))
    }

    /** 端到端②b：kill 之后**整棵进程树**都得消失——含 guest 的子进程（孤儿泄漏是真实风险）。 */
    @Test
    fun killLeavesNoLiveProcessBehind() {
        // 用独一无二的 sleep 时长做标记：它能出现在**最深那个子进程**的 cmdline 里，
        // 所以「ps 里再也搜不到它」就能证明 guest 的子孙也死了（只杀 proot 会留下孤儿）
        val task = manager.startBackgroundCommand(root, "echo before-sleep; sleep 297")
        awaitStdout(task.id, "before-sleep", timeoutMillis = 30_000)

        assertEquals(WorkspaceKillResult.KILLED, manager.killBackgroundTask(task.id))

        // 给信号一点时间落地，然后必须搜不到任何相关进程
        var leftovers = ""
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            leftovers = psGrep("297")
            if (leftovers.isEmpty()) break
            Thread.sleep(500)
        }
        assertTrue("kill 之后仍有进程存活：$leftovers", leftovers.isEmpty())
    }

    /** 端到端④b：完整输出落盘——尾部只有 8KB，日志文件必须是全量（否则「事后回看」是空话）。 */
    @Test
    fun fullOutputIsWrittenToWorkspaceLog() {
        val task = manager.startBackgroundCommand(root, "seq 1 20000")
        assertNotNull("任务应报告日志路径", task.stdoutLogPath)

        val finished = awaitTerminal(task.id, timeoutMillis = 120_000)

        // 尾部缓冲按容量截断，但日志文件必须是全量：这正是「输出落盘」要解决的问题
        assertTrue("尾部应已截断（否则这条用例没有意义）", finished.outputTruncated)
        val logFile = File(context.filesDir, "workspaces/$root/files/.tasks/${task.id}.stdout.log")
        assertTrue("日志文件不存在: ${logFile.absolutePath}", logFile.isFile)
        val text = logFile.readText()
        assertTrue("日志应是全量而非尾部（实际 ${text.length} 字符）", text.length > 100_000)
        assertTrue("日志应包含最早的行（尾部缓冲里早已丢弃）", text.startsWith("1\n"))
        assertTrue("日志应包含最后的行", text.trimEnd().endsWith("20000"))
        assertEquals("20000\n", text.takeLast(6))
    }

    /** 端到端⑤b：超过时长上限的任务被自动终止（并发上限只管数量，管不了时长）。 */
    @Test
    fun lifetimeLimitTerminatesLongRunningTask() {
        val limited = buildManager(maxLifetimeMillis = 5_000)

        val task = limited.startBackgroundCommand(root, "echo starting; sleep 297")
        assertEquals(5_000L, task.lifetimeLimitMillis)
        assertTrue("运行中应报剩余时间", (task.remainingMillis(System.currentTimeMillis()) ?: 0L) > 0L)

        val finished = awaitTerminal(limited, task.id, timeoutMillis = 30_000)

        assertEquals(WorkspaceTaskStatus.TIMED_OUT, finished.status)
        // 状态翻转不算数：必须真的没有进程残留
        var leftovers = ""
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            leftovers = psGrep("297")
            if (leftovers.isEmpty()) break
            Thread.sleep(500)
        }
        assertTrue("到点终止后仍有进程存活：$leftovers", leftovers.isEmpty())
    }

    /** 端到端③：并发上限用真任务压到边界，超限拒绝且失败不占名额。 */
    @Test
    fun concurrencyLimitRejectsExtraRealTasks() {
        // 用 2 的小上限另起一个 manager（同一份 rootfs，独立注册表），避免真机上留 4 个 sleep
        val limited = buildManager(maxBackgroundTasks = 2)
        val first = limited.startBackgroundCommand(root, "sleep 300")
        val second = limited.startBackgroundCommand(root, "sleep 300")
        // 不用 try/error：若上限未生效，第三个任务会真的起起来，失败路径上必须一并清掉
        val third = runCatching { limited.startBackgroundCommand(root, "sleep 300") }
        assertTrue("超限应被拒绝，实际=${third.getOrNull()}", third.exceptionOrNull() is IllegalStateException)
        assertTrue(
            "错误文案要给出恢复路径：${third.exceptionOrNull()?.message}",
            third.exceptionOrNull()?.message?.contains("workspace_kill_task") == true,
        )
        assertEquals(2, limited.backgroundTasks(root).count { it.isRunning })

        limited.killBackgroundTasks(root)
        awaitTerminal(limited, first.id, timeoutMillis = 30_000)
        awaitTerminal(limited, second.id, timeoutMillis = 30_000)
        assertEquals(0, limited.backgroundTasks(root).count { it.isRunning })
    }

    /** 端到端④：输出远超尾部容量时只留尾部、累计规模与截断标记如实上报。 */
    @Test
    fun hugeOutputKeepsTailOnlyAndFlagsTruncation() {
        // seq 1 40000 ≈ 240KB，远超 8KB 尾部容量
        val task = manager.startBackgroundCommand(root, "seq 1 40000")
        val finished = awaitTerminal(task.id, timeoutMillis = 120_000)

        assertEquals(WorkspaceTaskStatus.EXITED, finished.status)
        assertEquals(0, finished.exitCode)
        assertTrue("应标记截断", finished.outputTruncated)
        assertTrue("累计字符数应反映真实输出规模（实际 ${finished.stdoutTotalChars}）", finished.stdoutTotalChars > 100_000)
        assertTrue("尾部应保留最后的行（实际尾部=${finished.stdout.takeLast(40)}）", finished.stdout.trimEnd().endsWith("40000"))
        assertTrue("被丢弃的头部不应出现", !finished.stdout.contains("1\n2\n3\n"))
    }

    /** 端到端⑤：不存在的 cwd 当场拒绝（比起了任务再失败更早暴露问题），且不占名额。 */
    @Test
    fun missingWorkingDirectoryFailsWithoutOccupyingSlot() {
        val rejected = runCatching { manager.startBackgroundCommand(root, "echo nope", cwd = "no-such-dir-xyz") }
        assertTrue(
            "不存在的 cwd 应被拒绝，实际=${rejected.getOrNull()}",
            rejected.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            rejected.exceptionOrNull()?.message?.contains("Working directory does not exist") == true,
        )
        // 名额未被占用：仍能正常起任务
        assertNotNull(manager.startBackgroundCommand(root, "true"))
    }

    // ---------- helpers ----------

    private fun awaitTerminal(
        id: String,
        timeoutMillis: Long,
    ): WorkspaceTaskSnapshot = awaitTerminal(manager, id, timeoutMillis)

    private fun awaitTerminal(
        target: WorkspaceManager,
        id: String,
        timeoutMillis: Long,
    ): WorkspaceTaskSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val snapshot = target.backgroundTask(id)
            assumeTrue("任务 $id 消失在任务表里（不该发生）", snapshot != null)
            if (snapshot!!.status != WorkspaceTaskStatus.RUNNING) return snapshot
            Thread.sleep(250)
        }
        val stuck = target.backgroundTask(id)
        target.killBackgroundTask(id)
        error("任务 $id 在 ${timeoutMillis}ms 内没有到终态，最后状态=${stuck?.status} stdout=${stuck?.stdout?.take(300)}")
    }

    private fun awaitStdout(id: String, needle: String, timeoutMillis: Long) {        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (manager.backgroundTask(id)?.stdout?.contains(needle) == true) return
            Thread.sleep(200)
        }
        val snapshot = manager.backgroundTask(id)
        manager.killBackgroundTask(id)
        error("${timeoutMillis}ms 内没有等到 stdout 出现 '$needle'，实际=${snapshot?.stdout}")
    }

    /** `ps -A` 里命令行含 [needle] 的进程（诊断/断言用），没有则返回空串。 */
    private fun psGrep(needle: String): String = try {
        val process = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            "ps -A -o PID,PPID,NAME,ARGS | grep -F '$needle' | grep -v grep | head -10",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        text
    } catch (t: Throwable) {
        error("ps 失败: ${t.message}")
    }

    private companion object {
        const val TAG = "WorkspaceBgTaskTest"
    }
}
