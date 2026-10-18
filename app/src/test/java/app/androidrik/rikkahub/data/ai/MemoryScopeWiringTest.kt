package app.androidrik.rikkahub.data.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring guard only; repository behavior is covered by policy and real Room tests. */
class MemoryScopeWiringTest {
    @Test
    fun `approval preview receives trusted scope and never performs unscoped reads or deletes`() {
        val context = source("ui/components/message/tools/ToolUI.kt")
        val step = source("ui/components/message/ChatMessageTools.kt")
        val chat = source("ui/components/message/ChatMessage.kt")
        val renderer = source("ui/components/message/tools/BuiltinToolUIs.kt")
            .substringBefore("object SearchToolUI")
        assertTrue(context.contains("val memoryStoreId: String? = null"))
        assertTrue(chat.contains("memoryStoreId = assistant?.memoryId()"))
        assertTrue(step.contains("remember(tool, loading, workspaceId, memoryStoreId)"))
        assertTrue(step.contains("memoryStoreId = memoryStoreId"))
        assertTrue(renderer.contains("key(memoryStoreId, memoryId)"))
        assertTrue(renderer.contains("memoryRepo.getMemoryInStore(memoryStoreId, memoryId)"))
        assertTrue(renderer.contains("memoryRepo.deleteMemoryInStore(memoryStoreId, memoryId)"))
        assertFalse(renderer.contains("memoryRepo.getMemoryById("))
        assertFalse(renderer.contains("memoryRepo.deleteMemory("))
    }

    private fun source(relative: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
        return root.resolve("app/androidrik/rikkahub/$relative").readText()
    }
}
