package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMemoryTest {
    @Test
    fun settingsDecodeOldAndUnknownModesAndRoundTripFiles() {
        assertEquals(MemoryMode.ENTRIES, JsonInstant.decodeFromString<Assistant>("{}").memoryMode)
        assertEquals(MemoryMode.ENTRIES, JsonInstant.decodeFromString<Assistant>("""{"memoryMode":"future"}""").memoryMode)
        val assistant = Assistant(memoryMode = MemoryMode.FILES)
        assertEquals(MemoryMode.FILES, JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant)).memoryMode)
    }

    @Test
    fun planIncludesPinnedContentAndListsOtherFilesUnderBudget() {
        val files = listOf(
            MemoryFileEntity("a", "/profile.md", "profile contents", "r", 0),
            MemoryFileEntity("a", "/topics/app.md", "---\ndescription: Android app\n---\nTopic body", "r", 0),
        )
        val plan = buildFileMemoryPlan(files, 200, toolsAvailable = true)
        assertTrue(plan.dataText.contains("profile contents"))
        assertTrue(plan.dataText.contains("Android app"))
        assertFalse(plan.dataText.contains("Topic body"))
        assertTrue(plan.totalEstimatedTokens <= 200)
        assertFalse(buildFileMemoryPlan(files, 200, toolsAvailable = false).dataText.contains("/topics/app.md"))
        assertEquals(0, buildFileMemoryPlan(files, 1, toolsAvailable = true).totalEstimatedTokens)
    }

    @Test
    fun resumedApprovalRetainsLibraryAndHistoryUsesExactBinding() {
        val entries = Assistant(enableMemory = true, useGlobalMemory = true)
        val files = entries.copy(memoryMode = MemoryMode.FILES, useGlobalMemory = false)
        val entryCall = UIMessagePart.Tool(
            "one", ENTRY_MEMORY_TOOL, """{"action":"delete"}""",
            approvalState = ToolApprovalState.Approved,
            memoryContext = entries.memoryContext(false),
        )
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(entryCall))
        assertEquals(entries.memoryContext(false), files.forMemoryResume(reply, false).memoryContext(false))
        assertEquals(files, files.forMemoryResume(reply, true))
        assertNull(reply.withMemoryToolHistoryFor(files.memoryContext(false)))
        assertNull(reply.withMemoryToolHistoryFor(MEMORY_CONTEXT_OFF))
        assertEquals(reply, reply.withMemoryToolHistoryFor(entries.memoryContext(false)))
        assertEquals(files, files.forMemoryResume(reply.copy(parts = listOf(entryCall.copy(memoryContext = null))), false))
    }
}
