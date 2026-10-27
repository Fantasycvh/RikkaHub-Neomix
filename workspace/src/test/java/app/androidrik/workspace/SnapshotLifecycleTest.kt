package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 只读快照的**代际生命周期**与**快速指纹**（审计 #19 / #20）：
 *
 * - 租赁：命令/任务持有租约期间旧代不被回收，「已被取代且无人引用」的代在 close 后立即删除
 * - 回收：shell 污染快照后反复换代不再把 64 代 / 1GB 预算吃光（#20 的 shell 工具链级 DoS）
 * - 快速指纹：源未变时不再读文件内容（#19，用 `contentBytesReadForTest` 断言字节增量）
 * - WorkspaceManager 接线：每条命令结束都释放租约，孤儿代在下一条命令开始时被回收
 *
 * 污染一律用「往快照里写文件」模拟，不依赖 symlink 权限（Windows 无开发者模式也能跑）。
 */
class SnapshotLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun source() = temporaryFolder.newFolder().also {
        File(it, "original.txt").writeText("original")
    }

    private fun mount(source: File, target: String = "/upload", readOnly: Boolean = true) =
        WorkspaceBindMount(source, target, readOnly)

    private fun generationDirs(storage: File): List<File> =
        storage.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("generation-") }

    @Test
    fun leasedPreparationReclaimsSupersededGeneration() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()

        val first = snapshots.prepareLeased(listOf(mount(source)), directory)
        val firstGeneration = first.mounts.single().source
        assertTrue(firstGeneration.isDirectory)

        // 源变化 ⇒ 新代；旧代仍被租约持有，不能删（后台进程/终端可能还在读它）
        File(source, "added.txt").writeText("added")
        val second = snapshots.prepareLeased(listOf(mount(source)), directory)
        val secondGeneration = second.mounts.single().source
        assertNotEquals(firstGeneration, secondGeneration)
        assertTrue("lease 未释放前旧代必须保留", firstGeneration.exists())

        first.close()
        assertFalse("旧代已被取代且引用归零 ⇒ 立即回收", firstGeneration.exists())
        assertEquals("目录里只剩当前代", listOf(secondGeneration), generationDirs(directory))

        // 当前代被 close 时只是减引用，不能把正在用的代删掉
        second.close()
        assertTrue(secondGeneration.exists())
    }

    @Test
    fun pollutedGenerationCyclesDoNotExhaustBudget() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        // 紧预算：若陈旧代泄漏，5 轮就会撞满 3 代上限并永久报 budget exhausted
        val snapshots = ReadOnlyMountSnapshots(maxStorageBytes = 1024L * 1024, maxGenerations = 3)

        repeat(5) { round ->
            val lease = snapshots.prepareLeased(listOf(mount(source)), directory)
            // 模拟 shell 污染快照：每条命令都写一个新文件，快速签名必然变化 ⇒ 每条命令都会换代
            File(lease.mounts.single().source, "shell-write-$round.txt").writeText("pollution")
            lease.close()
        }

        // 每轮「污染 → 换代 → 释放」后目录里最多是「当前代 + 上一代（等下一次 GC）」；预算没被吃光
        val remaining = generationDirs(directory)
        assertTrue("回收后代数不应增长，实际 ${remaining.size}: $remaining", remaining.size <= 2)
        remaining.forEach { assertTrue("每一代都应含原始文件：$it", File(it, "original.txt").isFile) }

        // 快速签名稳定后再跑两条命令：GC 收掉遗留孤儿，稳态只剩当前代
        repeat(2) { snapshots.prepareLeased(listOf(mount(source)), directory).close() }
        val settled = generationDirs(directory)
        assertEquals("稳态应只剩当前代，实际 $settled", 1, settled.size)
        assertEquals("original", File(settled.single(), "original.txt").readText())
    }

    @Test
    fun veryLargeUploadIsNotRehashedPerCommand() {
        val source = temporaryFolder.newFolder()
        File(source, "original.txt").writeText("original")
        val bigFile = File(source, "big.bin")
        bigFile.writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() })
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()

        val first = snapshots.prepareLeased(listOf(mount(source)), directory)
        val firstGeneration = first.mounts.single().source
        val firstRead = snapshots.contentBytesReadForTest
        assertTrue("首次建档必须读内容（全量指纹 + 复制）", firstRead > 0)
        first.close()

        // 源未变（快速签名一致）⇒ 复用全量指纹，0 字节内容读取
        val second = snapshots.prepareLeased(listOf(mount(source)), directory)
        assertEquals(firstGeneration, second.mounts.single().source)
        assertEquals(
            "源未变时每条命令不应再读文件内容（#19）",
            firstRead,
            snapshots.contentBytesReadForTest,
        )
        second.close()

        // 对照：源内容真的变了（同字节数，靠 mtime 变化触发快速签名）⇒ 必须重新读内容
        bigFile.writeBytes(bigFile.readBytes().reversedArray())
        Files.setLastModifiedTime(
            bigFile.toPath(),
            FileTime.fromMillis(System.currentTimeMillis() + 10_000L),
        )
        val third = snapshots.prepareLeased(listOf(mount(source)), directory)
        assertNotEquals(firstGeneration, third.mounts.single().source)
        assertTrue("源内容变化后必须重新读内容", snapshots.contentBytesReadForTest > firstRead)
        third.close()
    }

    @Test
    fun leasedGenerationStillUsedByRunningTaskIsNotDeleted() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()

        // 模拟「任务仍在运行」：租约一直不 close
        val running = snapshots.prepareLeased(listOf(mount(source)), directory)
        val runningGeneration = running.mounts.single().source

        File(source, "added.txt").writeText("added")
        val next = snapshots.prepareLeased(listOf(mount(source)), directory)
        val nextGeneration = next.mounts.single().source

        assertNotEquals(runningGeneration, nextGeneration)
        assertTrue("运行中的任务还在用的代不能被 GC 回收", runningGeneration.exists())
        assertEquals("original", File(runningGeneration, "original.txt").readText())

        running.close()
        assertFalse("任务结束（租约释放）后才能回收旧代", runningGeneration.exists())
        assertTrue(nextGeneration.exists())
        next.close()
    }

    @Test
    fun managerReleasesLeaseAfterEachCommandSoSupersededGenerationsAreReclaimed() {
        val source = source()
        val storage = temporaryFolder.newFolder()
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder(),
            bindMounts = listOf(mount(source)),
            shellRunner = object : WorkspaceShellRunner {
                override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                    WorkspaceCommandResult(0, "", "")
            },
            readOnlySnapshotStorage = storage,
        )
        manager.ensureWorkspace("test-workspace")

        manager.executeCommand("test-workspace", "one")
        assertEquals(1, generationDirs(storage).size)

        // 源变化 ⇒ 第二条命令开新代；旧代此刻仍是 current，第一次 GC 不能删它
        File(source, "added.txt").writeText("added")
        manager.executeCommand("test-workspace", "two")
        assertEquals(2, generationDirs(storage).size)

        // 第三条命令开始时：旧代「不在 current 且无租约」⇒ 回收；说明前两条命令都释放了租约
        manager.executeCommand("test-workspace", "three")
        assertEquals("孤儿代应在下一条命令开始时被回收", 1, generationDirs(storage).size)
        val current = generationDirs(storage).single()
        assertEquals("original", File(current, "original.txt").readText())
        assertEquals("added", File(current, "added.txt").readText())
    }

    @Test
    fun managerReleasesBackgroundTaskLeaseWhenTheTaskExits() {
        val source = source()
        val storage = temporaryFolder.newFolder()
        val running = ControllableHandle()
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder(),
            bindMounts = listOf(mount(source)),
            shellRunner = object : WorkspaceShellRunner {
                override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                    WorkspaceCommandResult(0, "", "")
            },
            backgroundLauncher = object : WorkspaceTaskLauncher {
                override fun launch(
                    context: WorkspaceShellContext,
                    marker: String,
                    logs: WorkspaceTaskLogs,
                    stdout: TailBuffer,
                    stderr: TailBuffer,
                ): WorkspaceBackgroundHandle = running
            },
            readOnlySnapshotStorage = storage,
        )
        manager.ensureWorkspace("test-workspace")

        val task = manager.startBackgroundCommand("test-workspace", "long-running-job")
        val backgroundGeneration = generationDirs(storage).single()

        // 后台任务运行中：源变化 + 前台命令换代，旧代仍被后台任务的租约持有，不能回收
        File(source, "added.txt").writeText("added")
        manager.executeCommand("test-workspace", "foreground")
        assertEquals(2, generationDirs(storage).size)
        assertEquals(WorkspaceTaskStatus.RUNNING, manager.backgroundTask(task.id)!!.status)
        assertTrue(backgroundGeneration.exists())

        // 进程退出 ⇒ reaper 收尾后释放租约 ⇒ 已被取代的旧代立即回收
        running.finish()
        awaitGenerationCount(storage, 1)
        val current = generationDirs(storage).single()
        assertEquals("original", File(current, "original.txt").readText())
        assertEquals("added", File(current, "added.txt").readText())
    }

    /** 等待代目录数量收敛（租约由 reaper 线程异步释放，不能立即断言）。 */
    private fun awaitGenerationCount(storage: File, expected: Int, timeoutMillis: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (generationDirs(storage).size == expected) return
            Thread.sleep(10)
        }
        assertEquals("等待代目录数量收敛失败", expected, generationDirs(storage).size)
    }

    /** 假后台进程：`finish()` 之前一直「运行」，用于验证租约活到进程退出之后。 */
    private class ControllableHandle : WorkspaceBackgroundHandle {
        private val done = CountDownLatch(1)

        @Volatile
        private var exitCode: Int = 0

        fun finish() {
            done.countDown()
        }

        override fun kill() {
            exitCode = 137
            done.countDown()
        }

        override fun awaitExit(timeoutMillis: Long): Int? =
            if (done.await(timeoutMillis, TimeUnit.MILLISECONDS)) exitCode else null
    }
}
