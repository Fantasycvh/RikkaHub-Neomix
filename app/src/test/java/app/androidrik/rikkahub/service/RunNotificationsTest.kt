package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.task.TaskVisibility
import org.junit.Assert.*
import org.junit.Test

class RunNotificationsTest {
    private fun run(status: String = "completed", mode: String = "notice", entry: String = "chat") = AgentRunEntity(
        "id", entry, status, "profile", "research", null, null, "goal", "[]", "[]", "answer", null,
        "parent", 1, 2, 3, deliveryMode = mode,
    )

    @Test fun `every terminal background outcome notifies but blocking calls do not`() {
        for (status in listOf("completed", "failed", "cancelled", "interrupted")) {
            assertTrue(shouldNotifyRunCompletion(run(status), false))
            assertFalse(shouldNotifyRunCompletion(run(status), true))
        }
        assertTrue(shouldNotifyRunCompletion(run("failed").copy(error = "timeout: deadline"), false))
        assertFalse(shouldNotifyRunCompletion(run(mode = "await"), false))
        assertTrue(shouldNotifyRunCompletion(run(mode = "notify", entry = "subagent"), false))
        assertFalse(shouldNotifyRunCompletion(run("running").copy(finishedAt = null), false))
    }

    @Test fun `completion ids do not overlap approval ids or other runs`() {
        val ids = (1..8).flatMap { id -> listOf(NotificationIds.runCompleted("$id"),
            NotificationIds.runCompletedDetail("$id"), NotificationIds.subagentApproval("$id")) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it > 0 && it !in setOf(1, 40000, 40010) })
    }

    @Test fun `visibility leases are independently counted and do not hide another run`() {
        val a = TaskVisibility.acquire("run:visible-a")
        val duplicate = TaskVisibility.acquire("run:visible-a")
        try {
            a.close()
            assertTrue(TaskVisibility.isVisible("visible-a", null))
            assertFalse(TaskVisibility.isVisible("visible-b", null))
            duplicate.close()
            assertFalse(TaskVisibility.isVisible("visible-a", null))
        } finally { a.close(); duplicate.close() }
        TaskVisibility.acquire("conversation:visible-parent").use {
            assertTrue(TaskVisibility.isVisible("child", "visible-parent"))
            assertFalse(TaskVisibility.isVisible("child", "other-parent"))
        }
    }
}
