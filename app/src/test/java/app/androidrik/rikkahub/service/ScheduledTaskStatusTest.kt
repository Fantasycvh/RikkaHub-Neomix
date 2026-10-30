package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 定时任务执行结果判定的纯函数测试（docs/design/design-scheduled-ai-tasks.md §五/§九）。
 *
 * 核心回归：**「生成结束 + 无错误」不等于成功**。2026-10-30 真机事故里
 * `handleMessageComplete` 在冷启动读不到真实设置时静默 `return`——job 秒结、无错误、
 * 队列空，旧判定因此记 OK：用户只看到「后台触发了」而没有回复，管理页也显示成功，
 * 完全没有可诊断线索。补上「必须产出新的 assistant 消息」这条硬证据。
 */
class ScheduledTaskStatusTest {

    @Test
    fun `quiesced without errors and with a reply is OK`() {
        assertEquals(
            TaskRunStatus.OK,
            evaluateRunStatus(quiesced = true, hasNewError = false, producedReply = true),
        )
    }

    @Test
    fun `silent no-op is FAILED not OK`() {
        // 静默收场（例如模型解析失败直接 return）：生成结束、没报错、但一条 assistant 都没有
        assertEquals(
            TaskRunStatus.FAILED,
            evaluateRunStatus(quiesced = true, hasNewError = false, producedReply = false),
        )
    }

    @Test
    fun `new error is FAILED`() {
        assertEquals(
            TaskRunStatus.FAILED,
            evaluateRunStatus(quiesced = true, hasNewError = true, producedReply = true),
        )
    }

    @Test
    fun `not quiesced is TIMEOUT regardless of partial output`() {
        assertEquals(
            TaskRunStatus.TIMEOUT,
            evaluateRunStatus(quiesced = false, hasNewError = false, producedReply = true),
        )
        assertEquals(
            TaskRunStatus.TIMEOUT,
            evaluateRunStatus(quiesced = false, hasNewError = true, producedReply = false),
        )
    }
}
