package app.androidrik.ai.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {

    private fun nestedObject(depth: Int): JsonElement {
        var element: JsonElement = buildJsonObject { put("message", "leaf") }
        repeat(depth) {
            element = buildJsonObject { put("error", element) }
        }
        return element
    }

    @Test
    fun `parse error detail with deeply nested json does not overflow stack`() {
        // 回归：深嵌套错误 JSON 曾 StackOverflowError（无 depth 上限）
        val deep = nestedObject(100)
        val exception = deep.parseErrorDetail()
        assertTrue(exception.message!!.contains("deeply nested"))
    }

    @Test
    fun `parse error detail with normal depth returns leaf message`() {
        val normal = buildJsonObject { put("error", buildJsonObject { put("message", "rate limited") }) }
        assertEquals("rate limited", normal.parseErrorDetail().message)
    }
}
