package app.androidrik.rikkahub.ui.pages.assistant.detail

import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryKindFilterTest {
    private val memories = listOf(
        AssistantMemory("z", "First topic", kind = MemoryKind.TOPIC),
        AssistantMemory("b", "Open task", kind = MemoryKind.TASK),
        AssistantMemory("a", "Profile", kind = MemoryKind.PROFILE),
        AssistantMemory("y", "Done task", kind = MemoryKind.TASK, taskStatus = MemoryTaskStatus.DONE),
        AssistantMemory("c", "Second topic", kind = MemoryKind.TOPIC),
        AssistantMemory("x", "Cancelled task", kind = MemoryKind.TASK, taskStatus = MemoryTaskStatus.DROPPED),
    )

    @Test
    fun `all filter preserves original order and every task status`() {
        assertEquals(memories, filterMemoriesByKind(memories, null))
        assertEquals(listOf("b", "y", "x"), filterMemoriesByKind(memories, MemoryKind.TASK).map { it.id })
        assertEquals(listOf("z", "c"), filterMemoriesByKind(memories, MemoryKind.TOPIC).map { it.id })
        assertEquals(listOf("a"), filterMemoriesByKind(memories, MemoryKind.PROFILE).map { it.id })
    }

    @Test
    fun `counts include all statuses and sum to total`() {
        val counts = memoryKindCounts(memories)
        assertEquals(2, counts.getValue(MemoryKind.TOPIC))
        assertEquals(1, counts.getValue(MemoryKind.PROFILE))
        assertEquals(3, counts.getValue(MemoryKind.TASK))
        assertEquals(memories.size, counts.values.sum())
    }

    @Test
    fun `snapshot presentation uses historical fields and tolerates unknown values`() {
        val historical = MemoryEntity(assistantId = "test", kind = "task", taskStatus = "dropped", dueAt = 1234L)
        val live = historical.copy(kind = "profile", taskStatus = "open", dueAt = 0L)
        assertEquals(MemorySnapshotPresentation(MemoryKind.TASK, MemoryTaskStatus.DROPPED, 1234L), memorySnapshotPresentation(historical))
        assertEquals(MemorySnapshotPresentation(MemoryKind.PROFILE, null, null), memorySnapshotPresentation(live))
        assertEquals(MemorySnapshotPresentation(MemoryKind.TOPIC, null, null), memorySnapshotPresentation(historical.copy(kind = "future")))
        assertEquals(MemorySnapshotPresentation(MemoryKind.TASK, MemoryTaskStatus.OPEN, null), memorySnapshotPresentation(historical.copy(taskStatus = "future", dueAt = 0L)))
    }

    @Test
    fun `empty categories retain zero counts and empty results`() {
        val topicOnly = memories.filter { it.kind == MemoryKind.TOPIC }
        assertEquals(0, memoryKindCounts(topicOnly).getValue(MemoryKind.TASK))
        assertEquals(emptyList<AssistantMemory>(), filterMemoriesByKind(topicOnly, MemoryKind.TASK))
        assertEquals(MemoryKind.entries.associateWith { 0 }, memoryKindCounts(emptyList()))
        assertEquals(emptyList<AssistantMemory>(), filterMemoriesByKind(emptyList(), null))
    }
}
