package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEW_EACH_RUN 自动清理的目标挑选（§4.2）纯函数测试。
 *
 * 核心回归：**自动删用户数据的安全边界**——未收尾的账本行（时间线不可信）与
 * 「本轮结束后用户又发过消息」的会话（用户接手了的对话）一律不删；
 * 开关关闭（keep <= 0）时不产生任何删除目标。
 */
class ScheduledTaskCleanupTest {

    private fun run(
        conversationId: String?,
        startedAt: Long,
        finishedAt: Long? = startedAt + 1000,
    ) = ScheduledTaskRunEntity(
        id = "run-$startedAt",
        taskId = "task",
        source = "alarm",
        conversationId = conversationId,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = if (finishedAt == null) null else "OK",
    )

    @Test
    fun `keeps newest N and returns older ones (same order as input, newest first)`() {
        val runs = listOf(
            run("c4", 4000), run("c3", 3000), run("c2", 2000), run("c1", 1000),
        )
        assertEquals(listOf("c2", "c1"), pickAutoCleanupTargets(runs, keep = 2, lastMessageAt = emptyMap()))
    }

    @Test
    fun `skips unfinished rows (never delete on an untrusted timeline)`() {
        val runs = listOf(
            run("c3", 3000),
            run("c2", 2000, finishedAt = null), // 执行层中途死亡
            run("c1", 1000),
        )
        assertEquals(listOf("c1"), pickAutoCleanupTargets(runs, keep = 1, lastMessageAt = emptyMap()))
    }

    @Test
    fun `skips conversations the user continued after the run`() {
        val runs = listOf(
            run("c2", 2000), run("c1", 1000),
        )
        // c1 本轮 11:00 结束，用户 12:00 又发了消息 → 不删
        val targets = pickAutoCleanupTargets(
            runs, keep = 1,
            lastMessageAt = mapOf("c1" to 1000L * 60 * 60 * 12),
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `deletes when last message is from the run itself (before finish)`() {
        val runs = listOf(
            run("c2", 2000), run("c1", 1000, finishedAt = 5000),
        )
        // c1 的最后消息 4000 < finished 5000：那是任务自己落的回复，可删
        val targets = pickAutoCleanupTargets(
            runs, keep = 1,
            lastMessageAt = mapOf("c1" to 4000L),
        )
        assertEquals(listOf("c1"), targets)
    }

    @Test
    fun `keep zero means never clean`() {
        val runs = listOf(run("c1", 1000))
        assertTrue(pickAutoCleanupTargets(runs, keep = 0, lastMessageAt = emptyMap()).isEmpty())
    }
}
