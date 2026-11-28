package app.androidrik.rikkahub.ui.components.message

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolJsonParseTest {

    @Test
    fun `shouldParseToolJson skips incomplete streaming JSON`() {
        assertFalse(shouldParseToolJson("", streaming = false))
        assertFalse(shouldParseToolJson("   ", streaming = true))
        assertFalse(shouldParseToolJson("{\"path\":\"src/", streaming = true))
        assertFalse(shouldParseToolJson("[1, 2", streaming = true))
        assertTrue(shouldParseToolJson("{\"path\":\"a.kt\"}  ", streaming = true))
        assertTrue(shouldParseToolJson("[1, 2]\n", streaming = true))
    }

    @Test
    fun `parseToolJsonOrNull parses valid JSON and returns null on malformed`() {
        assertNull(parseToolJsonOrNull("{\"path\":\"unclosed", streaming = true))
        assertNull(parseToolJsonOrNull("{bad json}", streaming = false))

        val parsed = parseToolJsonOrNull("{\"ok\":true,\"name\":\"demo\"}", streaming = false)
        assertNotNull(parsed)
        assertEquals("demo", parsed!!.jsonObject["name"]?.jsonPrimitive?.content)
    }
}
