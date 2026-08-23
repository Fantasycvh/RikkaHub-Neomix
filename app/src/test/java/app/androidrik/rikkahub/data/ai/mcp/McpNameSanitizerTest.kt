package app.androidrik.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNameSanitizerTest {

    private val validPattern = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test
    fun `sanitizeSegment keeps legal chars unchanged`() {
        assertEquals("my-server_1", McpNameSanitizer.sanitizeSegment("my-server_1"))
    }

    @Test
    fun `sanitizeSegment collapses illegal runs into single underscore`() {
        assertEquals("a_b", McpNameSanitizer.sanitizeSegment("a   b"))
        assertEquals("a_b", McpNameSanitizer.sanitizeSegment("a，、。b"))
    }

    @Test
    fun `sanitizeSegment does not produce leading underscore`() {
        assertEquals("b", McpNameSanitizer.sanitizeSegment("中文b"))
        assertEquals("tool", McpNameSanitizer.sanitizeSegment("  tool"))
    }

    @Test
    fun `sanitizeSegment falls back to stable hex for all-illegal names`() {
        val a = McpNameSanitizer.sanitizeSegment("高德地图")
        val b = McpNameSanitizer.sanitizeSegment("高德地图")
        val c = McpNameSanitizer.sanitizeSegment("百度地图")

        assertTrue("回退值必须非空", a.isNotEmpty())
        assertTrue("回退值必须合法: $a", validPattern.matches(a))
        assertEquals("同名必须稳定", a, b)
        assertNotEquals("异名必须不同", a, c)
    }

    @Test
    fun `sanitizeSegment returns empty for empty input`() {
        assertEquals("", McpNameSanitizer.sanitizeSegment(""))
    }

    @Test
    fun `buildFunctionName produces provider-valid names for chinese input`() {
        val name = McpNameSanitizer.buildFunctionName("高德地图", "查询天气")
        assertTrue("必须匹配 provider 约束: $name", validPattern.matches(name))
        assertTrue("应保留 mcp 前缀: $name", name.startsWith("mcp__"))
    }

    @Test
    fun `buildFunctionName keeps ascii names readable`() {
        assertEquals(
            "mcp__weather__get_forecast",
            McpNameSanitizer.buildFunctionName("weather", "get_forecast")
        )
    }

    @Test
    fun `buildFunctionName truncates to 64 chars`() {
        val name = McpNameSanitizer.buildFunctionName("s".repeat(80), "t".repeat(80))
        assertEquals(64, name.length)
        assertTrue(validPattern.matches(name))
    }

    @Test
    fun `buildFunctionName substitutes placeholder for empty segments`() {
        val name = McpNameSanitizer.buildFunctionName("", "")
        assertEquals("mcp__server__tool", name)
    }

    @Test
    fun `buildUniqueFunctionName disambiguates sanitize collisions`() {
        val used = mutableSetOf<String>()
        // 「工具 A」与「工具-A」净化后 server 段相同，必须产生不同 function name
        val first = McpNameSanitizer.buildUniqueFunctionName("srv", "tool A", used)
        val second = McpNameSanitizer.buildUniqueFunctionName("srv", "tool-A", used)

        assertNotEquals(first, second)
        assertTrue(validPattern.matches(second))
        assertEquals(2, used.size)
    }

    @Test
    fun `buildUniqueFunctionName respects length limit while disambiguating`() {
        val used = mutableSetOf<String>()
        repeat(3) { McpNameSanitizer.buildUniqueFunctionName("s".repeat(80), "t".repeat(80), used) }

        assertEquals(3, used.size)
        used.forEach { assertTrue("超长去重仍需合法: $it", validPattern.matches(it)) }
    }
}
