package app.androidrik.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `workspace_read_file` 行窗口的边界单测。
 *
 * 这里钉的是"差一错误"：1 基/0 基、`limit` 含义、`truncated` 的含义、字节裁剪不能切半行。
 * 这些错了不会抛异常，只会让模型**读到错位的行却看不出来**——真机上表现为"AI 说文件里没有那段"。
 */
class ReadFileWindowTest {

    // ---- 归一化 ----

    @Test
    fun `no window parameters means a whole file read`() {
        assertNull(normalizeReadWindow(offset = null, limit = null))
        assertNull("非正值视为未指定（模型有时会填 0）", normalizeReadWindow(offset = 0, limit = 0))
        assertNull(normalizeReadWindow(offset = -5, limit = -1))
    }

    @Test
    fun `limit alone reads from the first line`() {
        assertEquals(ReadWindow(startLine = 1L, limit = 50), normalizeReadWindow(offset = null, limit = 50))
    }

    @Test
    fun `offset alone reads to the end of the file`() {
        assertEquals(
            ReadWindow(startLine = 120L, limit = MAX_READ_LINES),
            normalizeReadWindow(offset = 120, limit = null),
        )
    }

    @Test
    fun `an oversized limit is clamped`() {
        assertEquals(
            MAX_READ_LINES,
            normalizeReadWindow(offset = null, limit = 10_000_000)?.limit,
        )
    }

    // ---- 窗口结果 ----

    private val window = ReadWindow(startLine = 100L, limit = 3)

    @Test
    fun `the returned range is 1-based and inclusive`() {
        val result = buildWindowResult(window, lines = listOf("a", "b", "c"), moreLines = false, maxBytes = 1_000)

        assertEquals("a\nb\nc", result.text)
        assertEquals(100L, result.startLine)
        assertEquals("三行从第 100 行开始 ⇒ 结束于第 102 行", 102L, result.endLine)
        assertFalse(result.truncated)
    }

    @Test
    fun `more lines beyond the window mark it truncated`() {
        val result = buildWindowResult(window, lines = listOf("a", "b", "c"), moreLines = true, maxBytes = 1_000)

        assertTrue("还有更多行时必须说清（模型据此决定要不要继续翻）", result.truncated)
        assertEquals(102L, result.endLine)
    }

    @Test
    fun `an empty window reports an empty range instead of a bogus line number`() {
        val result = buildWindowResult(window, lines = emptyList(), moreLines = false, maxBytes = 1_000)

        assertEquals("", result.text)
        assertEquals("空窗口的 endLine 必须小于 startLine（不能报成「读到了第 100 行」）", 99L, result.endLine)
        assertFalse(result.truncated)
    }

    @Test
    fun `byte limit trims by whole lines and keeps at least one line`() {
        val lines = List(10) { "x".repeat(40) }

        val result = buildWindowResult(window, lines = lines, moreLines = true, maxBytes = 100)

        assertEquals("按整行裁：40 字节的行最多留 2 行（40 + 1 + 40 = 81 ≤ 100）", 2, result.text.split('\n').size)
        assertTrue(result.truncated)

        val tiny = buildWindowResult(window, lines = listOf("y".repeat(500)), moreLines = false, maxBytes = 10)
        assertEquals(
            "单行就超上限时保留它（空窗口比截断更难理解）",
            500,
            tiny.text.length,
        )
        assertEquals(100L, tiny.endLine)
    }

    @Test
    fun `a window with the exact byte budget is not truncated by bytes`() {
        // "ab" + 换行 + "cd" = 5 字节
        val result = buildWindowResult(window, lines = listOf("ab", "cd"), moreLines = false, maxBytes = 5)

        assertFalse(result.truncated)
        assertEquals(5, result.bytes)
    }
}
