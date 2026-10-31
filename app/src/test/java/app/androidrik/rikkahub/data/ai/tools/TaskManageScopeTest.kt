package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 管理任务的**越权红线**与审批分档单测（design-proactive-task-management.md §三.1/§三.2）。
 * 判定是纯函数，直接覆盖生产逻辑（与 `memoryToolNeedsApproval` 同款测试纪律）。
 */
class TaskManageScopeTest {

    private fun task(
        assistantId: String = "assistant-a",
        createdBy: String = ScheduledTaskEntity.CREATED_BY_AI,
    ) = ScheduledTaskEntity(
        id = "t1",
        conversationId = "",
        targetMode = "NEW_EACH_RUN",
        assistantId = assistantId,
        title = "巡检",
        prompt = "p",
        scheduleType = "DAILY",
        schedulePayload = "{}",
        nextRunAt = 0L,
        missPolicy = "RUN_LATE",
        idleOnly = false,
        deliverables = null,
        createdBy = createdBy,
        sourceConversationId = null,
        enabled = true,
        createdAt = 0L,
    )

    // ---- taskManageVerdict ----

    @Test
    fun `own ai task allows all actions`() {
        val task = task()
        assertNull(taskManageVerdict("enable", task, "assistant-a"))
        assertNull(taskManageVerdict("disable", task, "assistant-a"))
        assertNull(taskManageVerdict("delete", task, "assistant-a"))
    }

    @Test
    fun `foreign assistant task is always denied`() {
        val task = task(assistantId = "assistant-b")
        assertEquals("foreign_assistant", taskManageVerdict("enable", task, "assistant-a"))
        assertEquals("foreign_assistant", taskManageVerdict("delete", task, "assistant-a"))
    }

    @Test
    fun `deleting user-created task is denied outright`() {
        val task = task(createdBy = ScheduledTaskEntity.CREATED_BY_USER)
        assertEquals("user_task_delete_denied", taskManageVerdict("delete", task, "assistant-a"))
        // 但启停仍然允许（决议 §三.1：AI 可启停本助手的用户任务）
        assertNull(taskManageVerdict("enable", task, "assistant-a"))
    }

    @Test
    fun `missing task is not found`() {
        assertEquals("not_found", taskManageVerdict("delete", null, "assistant-a"))
    }

    // ---- taskManageNeedsApproval ----

    @Test
    fun `approvals default on for mutations`() {
        val assistant = Assistant()
        assertTrue(taskManageNeedsApproval(assistant, "create"))
        assertTrue(taskManageNeedsApproval(assistant, "delete"))
        assertTrue(taskManageNeedsApproval(assistant, "enable"))
        assertTrue(taskManageNeedsApproval(assistant, "disable"))
        assertFalse("只读不拦", taskManageNeedsApproval(assistant, "list"))
        assertTrue("未知 action 宁可多拦", taskManageNeedsApproval(assistant, "whatever"))
    }

    @Test
    fun `capability switch is opt-in and defaults off`() {
        assertFalse(Assistant().taskManageEnabled)
    }
}
