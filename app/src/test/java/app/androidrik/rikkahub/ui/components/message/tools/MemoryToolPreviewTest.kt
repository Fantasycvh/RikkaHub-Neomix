package app.androidrik.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryToolPreviewTest {

    @Test
    fun `falls back to arguments content when pending approval before execution`() {
        val args = buildJsonObject {
            put("action", "edit")
            put("content", "User prefers dark theme")
        }
        assertEquals(
            "User prefers dark theme",
            resolveMemoryToolProposedContent(action = "edit", arguments = args, content = null),
        )
        assertEquals(
            "User prefers dark theme",
            resolveMemoryToolProposedContent(action = "create", arguments = args, content = null),
        )
    }

    @Test
    fun `prefers executed result content over arguments when available`() {
        val args = buildJsonObject {
            put("action", "edit")
            put("content", "Draft content")
        }
        val result = buildJsonObject {
            put("content", "Persisted content")
        }
        assertEquals(
            "Persisted content",
            resolveMemoryToolProposedContent(action = "edit", arguments = args, content = result),
        )
    }

    @Test
    fun `returns null for delete or blank content`() {
        val args = buildJsonObject {
            put("action", "delete")
            put("content", "Ignored")
        }
        assertNull(resolveMemoryToolProposedContent(action = "delete", arguments = args, content = null))
        assertNull(
            resolveMemoryToolProposedContent(
                action = "create",
                arguments = buildJsonObject { put("content", "   ") },
                content = null,
            ),
        )
    }
}
