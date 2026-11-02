package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExecutionScope] 的纯逻辑单测（P3，design-agent-task-runtime §5.2）。
 *
 * 级联取消一旦判断错，要么杀不掉孤儿进程，要么误杀别的轮次/run 的任务；两个方向都不可接受，
 * 所以把「父链覆盖」和「不是字符串前缀匹配」分开钉住。
 */
class ExecutionScopeTest {

    @Test
    fun `canonical wires match the ledger formats`() {
        assertEquals("turn:abc", ExecutionScope.turn("abc").wire)
        assertEquals("run:r1", ExecutionScope.run("r1").wire)
        assertEquals("sched:r2", ExecutionScope.scheduled("r2").wire)
    }

    @Test
    fun `covers follows the in-memory parent chain`() {
        val turn = ExecutionScope.turn("t1")
        val run = turn.child(ExecutionScope.run("r1").wire)
        val shell = run.child("shell:x")

        assertTrue("自身也要被覆盖（取消 scope 应包含自己启动的任务）", turn.covers(turn))
        assertTrue(turn.covers(run))
        assertTrue(turn.covers(shell))
        assertTrue(run.covers(shell))
        assertFalse(shell.covers(turn))
        assertFalse(run.covers(turn))
        assertFalse(turn.covers(null))
        assertFalse(turn.covers(ExecutionScope.turn("t2")))
    }

    @Test
    fun `covers is not string prefix matching`() {
        val short = ExecutionScope.parse("run:ab")!!
        val long = ExecutionScope.parse("run:abc")!!
        assertFalse("run:ab 不能误伤 run:abc", short.covers(long))
        assertFalse(long.covers(short))
        assertTrue(short.covers(short))
    }

    @Test
    fun `parse keeps legacy wires and treats blank as unowned`() {
        // 历史令牌（P0 的 turn-<uuid>）不丢：parse 原样保留，避免升级后旧行匹配不上。
        assertEquals("turn-legacy-uuid", ExecutionScope.parse("turn-legacy-uuid")!!.wire)
        assertEquals("run:r1", ExecutionScope.parse(" run:r1 ")!!.wire)
        assertNull(ExecutionScope.parse(null))
        assertNull(ExecutionScope.parse(""))
        assertNull(ExecutionScope.parse("   "))
    }

    @Test
    fun `blank wire is rejected at construction`() {
        try {
            ExecutionScope("")
            org.junit.Assert.fail("空 wire 必须拒绝，否则会变成匹配一切的 scope")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("wire"))
        }
    }
}
