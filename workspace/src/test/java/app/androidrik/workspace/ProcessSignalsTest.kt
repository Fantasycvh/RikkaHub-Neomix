package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程终止逻辑（[ProcessSignals]）的 JVM 单测。
 *
 * 这里是**平台绕行方案**的核心：真机实测 `Process.destroyForcibly()` 对 PRoot 子进程无效，
 * 所以终止必须自己完成「按标记在 /proc 找回进程树 → 自底向上 SIGKILL」。真机验证见
 * `app/src/androidTest/.../WorkspaceBackgroundTaskInstrumentedTest`；本文件用假进程表把
 * **纯逻辑**（标记匹配范围、杀树的顺序、找不到时的行为）钉死，不需要真进程。
 */
class ProcessSignalsTest {

    private class FakeProcFs(
        private val table: List<ProcNode>,
        private val cmdlines: Map<Int, String>,
    ) : ProcFs {
        val killed = mutableListOf<Int>()

        override fun nodes(): List<ProcNode> = table

        override fun cmdline(pid: Int): String? = cmdlines[pid]

        override fun kill(pid: Int): Boolean {
            killed += pid
            return true
        }
    }

    private fun marker(id: String) = "$TASK_MARKER_PREFIX$id"

    // ---------- 标记匹配范围 ----------

    @Test
    fun `marker is found among our own descendants`() {
        val fs = FakeProcFs(
            table = listOf(
                ProcNode(pid = 100, ppid = 1), // self
                ProcNode(pid = 200, ppid = 100), // proot
                ProcNode(pid = 300, ppid = 200), // guest bash
                ProcNode(pid = 400, ppid = 300), // sleep
            ),
            cmdlines = mapOf(200 to "libproot_exec.so --root-id ... ${marker("abc")}"),
        )

        assertEquals(200, findRootPidByMarker(fs, selfPid = 100, marker = marker("abc")))
    }

    @Test
    fun `marker outside our subtree is never matched`() {
        // 同 uid 的其它进程组即使带着同样的标记也不许碰（防误杀）
        val fs = FakeProcFs(
            table = listOf(
                ProcNode(pid = 100, ppid = 1), // self
                ProcNode(pid = 900, ppid = 1), // 无关进程，恰好带标记
            ),
            cmdlines = mapOf(900 to "sh -c something ${marker("abc")}"),
        )

        assertNull(findRootPidByMarker(fs, selfPid = 100, marker = marker("abc")))
    }

    @Test
    fun `no marker means no root`() {
        val fs = FakeProcFs(
            table = listOf(ProcNode(pid = 100, ppid = 1), ProcNode(pid = 200, ppid = 100)),
            cmdlines = mapOf(200 to "libproot_exec.so --root-id"),
        )

        assertNull(findRootPidByMarker(fs, selfPid = 100, marker = marker("missing")))
    }

    // ---------- 杀树的顺序与范围 ----------

    @Test
    fun `process tree is killed leaves first and self is excluded`() {
        val fs = FakeProcFs(
            table = listOf(
                ProcNode(pid = 100, ppid = 1), // self
                ProcNode(pid = 200, ppid = 100), // 目标树根
                ProcNode(pid = 300, ppid = 200),
                ProcNode(pid = 400, ppid = 300),
                ProcNode(pid = 500, ppid = 200),
            ),
            cmdlines = emptyMap(),
        )

        val killed = killProcessTree(fs, rootPid = 200, alsoRoot = true)

        // 必须自底向上：先杀父进程会让子孙变成孤儿（PPID=1），之后就再也找不到它们。
        // 断言「性质」而非具体顺序：每个 pid 都必须在它的所有祖先之前被杀
        assertEquals(setOf(200, 300, 400, 500), killed.toSet())
        for ((child, parent) in listOf(300 to 200, 400 to 300, 500 to 200)) {
            assertTrue(
                "子进程 $child 必须先于父进程 $parent 被杀，实际顺序=$killed",
                killed.indexOf(child) < killed.indexOf(parent),
            )
        }
        assertTrue("绝不能杀自己（调用方进程）", 100 !in killed)
    }

    @Test
    fun `kill by marker reports zero and kills nothing when command already exited`() {
        val fs = FakeProcFs(
            table = listOf(ProcNode(pid = 100, ppid = 1)),
            cmdlines = emptyMap(),
        )

        assertEquals(0, killProcessTreeByMarker(fs, selfPid = 100, marker = marker("gone")))
        assertTrue(fs.killed.isEmpty())
    }

    @Test
    fun `kill by marker kills the whole tree of the marked process`() {
        val fs = FakeProcFs(
            table = listOf(
                ProcNode(pid = 100, ppid = 1),
                ProcNode(pid = 200, ppid = 100),
                ProcNode(pid = 300, ppid = 200),
            ),
            cmdlines = mapOf(200 to "${marker("xyz")}"),
        )

        assertEquals(2, killProcessTreeByMarker(fs, selfPid = 100, marker = marker("xyz")))
        assertEquals(listOf(300, 200), fs.killed)
    }

    @Test
    fun `pid reuse or cycles do not loop forever`() {
        // 真实 /proc 可能出现「父进程先退出、pid 被复用」造成的畸形父子关系
        val fs = FakeProcFs(
            table = listOf(
                ProcNode(pid = 100, ppid = 1),
                ProcNode(pid = 200, ppid = 100),
                ProcNode(pid = 300, ppid = 400),
                ProcNode(pid = 400, ppid = 300),
            ),
            cmdlines = mapOf(200 to marker("cycle")),
        )

        assertEquals(1, killProcessTreeByMarker(fs, selfPid = 100, marker = marker("cycle")))
    }

    // ---------- /proc/<pid>/stat 解析 ----------

    @Test
    fun `proc stat parses ppid`() {
        val stat = "24013 (bash) S 24008 24013 24013 0 -1 4194304"
        assertEquals(24008, parseProcStat(stat))
    }

    @Test
    fun `proc stat handles comm containing spaces and parens`() {
        // 经典坑：comm 可能含空格/括号，必须从最后一个 ") " 之后切字段
        val stat = "1234 (weird ) name (x)) S 42 1234 1234"
        assertEquals(42, parseProcStat(stat))
    }

    @Test
    fun `proc stat rejects malformed input`() {
        assertNull(parseProcStat("garbage"))
        assertNull(parseProcStat("1234 (x) S"))
    }

    @Test
    fun `marker prefix is namespaced`() {
        // 其它工具的标记不会与本仓撞车（pkill -f 的匹配面很宽，前缀要够特别）
        assertTrue(newTaskMarker("abc123").startsWith(TASK_MARKER_PREFIX))
        assertTrue(newTaskMarker("abc123").contains("abc123"))
    }
}
