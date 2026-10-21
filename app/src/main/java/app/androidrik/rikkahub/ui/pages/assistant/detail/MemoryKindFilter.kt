package app.androidrik.rikkahub.ui.pages.assistant.detail

import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.data.db.entity.MemoryEntity

/** Counts include zero-value kinds, so empty categories remain visible and selectable. */
internal fun memoryKindCounts(memories: List<AssistantMemory>): Map<MemoryKind, Int> =
    MemoryKind.entries.associateWith { kind -> memories.count { it.kind == kind } }

/** Null means all; filtering never changes the repository's stable list order. */
internal fun filterMemoriesByKind(memories: List<AssistantMemory>, kind: MemoryKind?): List<AssistantMemory> =
    if (kind == null) memories else memories.filter { it.kind == kind }

internal data class MemorySnapshotPresentation(
    val kind: MemoryKind,
    val taskStatus: MemoryTaskStatus?,
    val dueAt: Long?,
)

/** Recovery must describe the chosen historical snapshot, not the live memory. */
internal fun memorySnapshotPresentation(snapshot: MemoryEntity): MemorySnapshotPresentation {
    val kind = MemoryKind.fromStorage(snapshot.kind)
    return MemorySnapshotPresentation(
        kind = kind,
        taskStatus = if (kind == MemoryKind.TASK) MemoryTaskStatus.fromStorage(snapshot.taskStatus) else null,
        dueAt = if (kind == MemoryKind.TASK) snapshot.dueAt.takeIf { it > 0L } else null,
    )
}
