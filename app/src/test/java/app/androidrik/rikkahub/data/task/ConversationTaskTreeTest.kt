package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import org.junit.Assert.*
import org.junit.Test

class ConversationTaskTreeTest {
    private fun run(id: String, scope: String = "turn:one") = AgentRunEntity(
        id, "chat", "running", "profile", "research", null, null, "goal", "[]", "[]", null, null,
        "parent", 1, 2, null, scopeToken = scope,
    )
    private fun shell(id: String, owner: String?) = WorkspaceTaskSnapshot(id, "root", "sleep 1", "/",
        WorkspaceTaskStatus.RUNNING, null, "", "", 0, 0, false, 1, null, owner = owner)

    @Test fun `run commands are nested and unrelated owners never appear`() {
        val result = conversationTaskSnapshot(listOf(run("a")), listOf(
            shell("nested", "run:a"), shell("direct", "turn:one"), shell("foreign", "run:b"),
            shell("unowned", null), shell("other-turn", "turn:other")), emptyList(), "turn:one")
        assertEquals(listOf("nested"), result.runs.single().commands.map { (it.key as TaskKey.Shell).taskId })
        assertEquals(listOf("direct"), result.commands.map { (it.key as TaskKey.Shell).taskId })
        assertEquals(TaskState.RUNNING, result.runs.single().task.state)
    }

    @Test fun `current turn commands are shown even before any subagent exists`() {
        val result = conversationTaskSnapshot(emptyList(), listOf(shell("direct", "turn:current")), emptyList(), "turn:current")
        assertEquals(1, result.commands.size)
        assertTrue(result.runs.isEmpty())
    }

    @Test fun `corrupt transcript does not hide the run or manufacture a tool trace`() {
        val result = conversationTaskSnapshot(listOf(run("a").copy(transcript = "broken")), emptyList(), emptyList(), null)
        assertEquals(1, result.runs.size)
        assertTrue(result.runs.single().tools.isEmpty())
    }
}
