package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKindProposalTextTest {
    @Test fun `confirmation exposes classification and deadline removal`() {
        val target = AssistantMemory("record", "before", kind = MemoryKind.TOPIC)
        val proposal = MemoryConsolidationProposal("edit", "record", "after", kind = "task", dueAt = 0L)
        val summary = proposeChangeSummary(proposal, target)
        assertTrue(summary.contains("topic → task"))
        assertTrue(summary.contains("due: cleared"))
    }

    @Test fun `confirmation uses normalized kind and exposes implicit task clearing`() {
        val task = AssistantMemory("record", "before", kind = MemoryKind.TASK, dueAt = 1_800_000_000_000L)
        val summary = proposeChangeSummary(MemoryConsolidationProposal("edit", "record", "after", kind = "future"), task)
        assertTrue(summary.contains("task → topic"))
        assertTrue(summary.contains("task status and due: cleared"))
        val ordinary = proposeChangeSummary(MemoryConsolidationProposal("create", content = "after", dueAt = 1_800_000_000_000L), null)
        assertEquals("[new] after", ordinary)
    }

    @Test fun `status suggestions show target content transition and nonempty details`() {
        val task = AssistantMemory("record", "submit homework", kind = MemoryKind.TASK, revision = 2)
        for ((action, status) in listOf("complete" to "done", "drop" to "dropped")) {
            val proposal = MemoryConsolidationProposal(action, targetId = task.id, expectedRevision = 2)
            val summary = proposeChangeSummary(proposal, task)
            assertTrue(summary.contains("submit homework"))
            assertTrue(summary.contains("open → $status"))
            assertEquals("$action · record", proposal.describe())
            assertEquals("$action · record", proposal.detailTitle())
            assertTrue(proposal.detailContent().contains("record"))
            assertTrue(proposal.detailContent().contains("task_status → $status"))
            assertTrue(proposeChangeSummary(proposal, task.copy(kind = MemoryKind.TOPIC)).contains("not a task"))
            assertTrue(proposeChangeSummary(proposal, task.copy(revision = 3)).contains("will not apply"))
            assertTrue(proposeChangeSummary(proposal, null).contains("target no longer exists"))
        }
    }

    @Test fun `legacy proposal confirmation remains byte identical`() {
        val target = AssistantMemory("record", "before")
        assertEquals("[edit] record — “before” → “after”",
            proposeChangeSummary(MemoryConsolidationProposal("edit", "record", "after"), target))
        assertEquals("[new] after", proposeChangeSummary(MemoryConsolidationProposal("create", content = "after"), null))
    }
}
