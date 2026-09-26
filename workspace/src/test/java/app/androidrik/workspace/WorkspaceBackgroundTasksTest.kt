package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 后台任务三件套的 JVM 单测：尾部缓冲、流泵、注册表（启动/查询/终止/并发上限/淘汰）。
 *
 * 真实进程启动被隔离在 [WorkspaceTaskLauncher] 之后，这里用 [FakeLauncher] 替代，
 * 于是「调度与状态机」可以在 JVM 上确定性地验证，不需要 rootfs 与真机。
 */
class WorkspaceBackgroundTasksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---------- TailBuffer ----------

    @Test
    fun `tail buffer keeps everything under capacity`() {
        val buffer = TailBuffer(capacityChars = 100)
        buffer.append("hello ")
        buffer.append("world")

        val snapshot = buffer.snapshot()
        assertEquals("hello world", snapshot.text)
        assertEquals(11L, snapshot.totalChars)
        assertFalse(snapshot.truncated)
        assertEquals(100, snapshot.capacity)
    }

    @Test
    fun `tail buffer keeps only the tail when overflowing`() {
        val buffer = TailBuffer(capacityChars = 10)
        buffer.append("0123456789")
        buffer.append("abc")

        val snapshot = buffer.snapshot()
        // 容量 10: 丢掉前 3 个字符, 保留最后 10 个
        assertEquals("3456789abc", snapshot.text)
        assertEquals(10, snapshot.text.length)
        // 累计规模要如实记录，否则模型无法判断输出到底有多大
        assertEquals(13L, snapshot.totalChars)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `tail buffer handles a single batch larger than capacity`() {
        val buffer = TailBuffer(capacityChars = 5)
        buffer.append("0123456789")

        val snapshot = buffer.snapshot()
        assertEquals("56789", snapshot.text)
        assertEquals(10L, snapshot.totalChars)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `tail buffer rejects non positive capacity`() {
        try {
            TailBuffer(capacityChars = 0)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("capacityChars"))
        }
    }

    // ---------- pumpText ----------

    @Test
    fun `pump text copies a stream into the tail buffer`() {
        val buffer = TailBuffer(capacityChars = 1024)
        pumpText("构建完成 ✅ done".byteInputStream(Charsets.UTF_8), buffer)

        assertEquals("构建完成 ✅ done", buffer.snapshot().text)
    }

    @Test
    fun `pump text keeps what was read when the stream breaks`() {
        val buffer = TailBuffer(capacityChars = 1024)
        // 进程被强杀时流会中途抛 IOException：已读内容必须保留, 异常不能逃逸
        pumpText(
            object : InputStream() {
                private val head = "partial output".byteInputStream(Charsets.UTF_8)
                override fun read(): Int = head.read().let { if (it < 0) throw IOException("killed") else it }
            },
            buffer,
        )

        assertEquals("partial output", buffer.snapshot().text)
    }

    // ---------- 注册表 ----------

    @Test
    fun `start returns a running task that the launcher feeds`() {
        val launcher = FakeLauncher().apply {
            enqueue(stdout = "building", stderr = "")
        }
        val registry = WorkspaceTaskRegistry(launcher)

        val task = registry.start(context(command = "make -j4"))

        assertEquals(WorkspaceTaskStatus.RUNNING, task.status)
        assertEquals("make -j4", task.command)
        assertEquals("demo", task.root)
        assertTrue(task.isRunning)
        assertEquals("building", registry.snapshot(task.id)!!.stdout)
        assertTrue(launcher.startedProcesses.single().awaitCalled.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `exit code and status are recorded when the process ends`() {
        val launcher = FakeLauncher()
        val registry = WorkspaceTaskRegistry(launcher)
        val task = registry.start(context(command = "./train.sh"))

        launcher.startedProcesses.single().finish(exitCode = 3)

        val done = awaitFinished(registry, task.id)
        assertEquals(WorkspaceTaskStatus.EXITED, done.status)
        assertEquals(3, done.exitCode)
        assertFalse(done.isRunning)
        assertNotNull(done.finishedAtMillis)
        assertTrue(done.durationMillis(System.currentTimeMillis()) >= 0)
    }

    @Test
    fun `kill stops a running task and marks it killed`() {
        val launcher = FakeLauncher()
        val registry = WorkspaceTaskRegistry(launcher)
        val task = registry.start(context(command = "sleep 1000"))

        assertEquals(WorkspaceKillResult.KILLED, registry.kill(task.id))

        val process = launcher.startedProcesses.single()
        assertTrue(process.killed)
        assertEquals(WorkspaceTaskStatus.KILLED, awaitFinished(registry, task.id).status)
    }

    @Test
    fun `kill reports finished and unknown tasks without throwing`() {
        val launcher = FakeLauncher()
        val registry = WorkspaceTaskRegistry(launcher)
        val task = registry.start(context(command = "true"))
        launcher.startedProcesses.single().finish(0)
        awaitFinished(registry, task.id)

        assertEquals(WorkspaceKillResult.ALREADY_FINISHED, registry.kill(task.id))
        assertEquals(WorkspaceKillResult.NOT_FOUND, registry.kill("missing"))
    }

    @Test
    fun `concurrent task limit rejects extra tasks and frees a slot after kill`() {
        val launcher = FakeLauncher()
        val registry = WorkspaceTaskRegistry(launcher, maxConcurrent = 2)
        val first = registry.start(context(command = "a", root = "ws-1"))
        val second = registry.start(context(command = "b", root = "ws-1"))

        try {
            registry.start(context(command = "c", root = "ws-1"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // 错误文案要能让模型自行恢复：说明上限并指出用哪个工具腾位置
            assertTrue(e.message!!, e.message!!.contains("max 2"))
            assertTrue(e.message!!, e.message!!.contains("workspace_kill_task"))
        }
        assertEquals(2, registry.runningCount())

        registry.kill(first.id)
        awaitFinished(registry, first.id)
        assertEquals(1, registry.runningCount())

        val third = registry.start(context(command = "c", root = "ws-1"))
        assertEquals(WorkspaceTaskStatus.RUNNING, third.status)
        assertEquals(2, registry.runningCount())
        assertNotNull(registry.snapshot(second.id))
    }

    @Test
    fun `launcher failure becomes a failed task carrying the reason`() {
        val launcher = FakeLauncher().apply { failWith = "Rootfs is not installed" }
        val registry = WorkspaceTaskRegistry(launcher, maxConcurrent = 1)

        val task = registry.start(context(command = "make"))

        assertEquals(WorkspaceTaskStatus.FAILED, task.status)
        assertNull(task.exitCode)
        assertTrue(task.stderr.contains("Rootfs is not installed"))
        // 启动失败不应占着并发名额（否则连续几次失败就会把工作空间锁死）
        assertEquals(0, registry.runningCount())
        assertNotNull(registry.start(context(command = "make again")))
    }

    @Test
    fun `launcher exception becomes a failed task instead of crashing`() {
        val launcher = FakeLauncher().apply { throwOnLaunch = IOException("no such file") }
        val registry = WorkspaceTaskRegistry(launcher)

        val task = registry.start(context(command = "make"))

        assertEquals(WorkspaceTaskStatus.FAILED, task.status)
        assertTrue(task.stderr.contains("no such file"))
    }

    @Test
    fun `finished tasks are evicted but running ones are kept`() {
        val launcher = FakeLauncher()
        val registry = WorkspaceTaskRegistry(launcher, maxFinished = 2)

        val longRunning = registry.start(context(command = "keep-running"))
        val finished = (1..3).map { index ->
            val task = registry.start(context(command = "job-$index"))
            launcher.startedProcesses.last().finish(0)
            awaitFinished(registry, task.id)
            task
        }

        val ids = registry.list().map { it.id }
        assertTrue("运行中的任务永不被淘汰", ids.contains(longRunning.id))
        assertNull("最旧的已完成任务应被淘汰", registry.snapshot(finished.first().id))
        assertNotNull(registry.snapshot(finished.last().id))
        assertEquals(3, ids.size)
    }

    @Test
    fun `list and killAll are scoped per workspace root`() {
        val launcher = FakeLauncher()
        val registry = WorkspaceTaskRegistry(launcher)
        val one = registry.start(context(command = "a", root = "ws-1"))
        val other = registry.start(context(command = "b", root = "ws-2"))

        assertEquals(listOf(one.id), registry.list("ws-1").map { it.id })
        assertEquals(2, registry.list().size)
        assertEquals(1, registry.runningCount("ws-2"))

        assertEquals(1, registry.killAll("ws-1"))

        assertEquals(WorkspaceTaskStatus.KILLED, awaitFinished(registry, one.id).status)
        assertEquals(WorkspaceTaskStatus.RUNNING, registry.snapshot(other.id)!!.status)
    }

    // ---------- helpers ----------

    private fun context(command: String, root: String = "demo") = WorkspaceShellContext(
        root = root,
        command = command,
        cwd = "",
        filesDir = tempFolder.root,
        linuxDir = tempFolder.root,
        tempDir = tempFolder.root,
        workingDir = tempFolder.root,
        timeoutMillis = 0L,
    )

    /** 回收线程是异步的：轮询到终态（或超时失败）为止。 */
    private fun awaitFinished(
        registry: WorkspaceTaskRegistry,
        id: String,
        timeoutMillis: Long = 2_000,
    ): WorkspaceTaskSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val snapshot = registry.snapshot(id)
            if (snapshot != null && !snapshot.isRunning) return snapshot
            Thread.sleep(10)
        }
        fail("task $id did not reach a terminal status within ${timeoutMillis}ms")
        error("unreachable")
    }

    private class FakeProcess : WorkspaceBackgroundHandle {
        private val done = CountDownLatch(1)

        @Volatile
        var killed: Boolean = false
            private set

        @Volatile
        private var exitCode: Int = 0

        val awaitCalled = CountDownLatch(1)

        fun finish(exitCode: Int) {
            this.exitCode = exitCode
            done.countDown()
        }

        override fun kill() {
            killed = true
            exitCode = 137
            done.countDown()
        }

        override fun awaitExit(): Int {
            awaitCalled.countDown()
            done.await()
            return exitCode
        }
    }

    private class FakeLauncher : WorkspaceTaskLauncher {
        val startedProcesses = mutableListOf<FakeProcess>()

        var failWith: String? = null
        var throwOnLaunch: Exception? = null

        private val pending = ArrayDeque<Pair<String, String>>()

        fun enqueue(stdout: String, stderr: String) {
            pending.addLast(stdout to stderr)
        }

        override fun launch(
            context: WorkspaceShellContext,
            stdout: TailBuffer,
            stderr: TailBuffer,
        ): WorkspaceBackgroundHandle? {
            throwOnLaunch?.let { throw it }
            failWith?.let {
                stderr.append(it)
                return null
            }
            val (out, err) = if (pending.isEmpty()) "" to "" else pending.removeFirst()
            stdout.append(out)
            stderr.append(err)
            return FakeProcess().also { startedProcesses += it }
        }
    }
}
