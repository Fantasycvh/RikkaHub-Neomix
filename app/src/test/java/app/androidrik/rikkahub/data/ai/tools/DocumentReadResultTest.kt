package app.androidrik.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档抽取结果拼装的单测。
 *
 * 钉两件事：
 * 1. **空文本必须带说明**——扫描版 PDF 很常见，返回空串而不解释是最糟的（模型会以为文件是空的）；
 * 2. `truncated` 的两种来源（单元没读完 / 字符被裁）都要报出来，模型据此决定要不要继续翻。
 */
class DocumentReadResultTest {

    @Test
    fun `extracted text is passed through with 1-based inclusive unit range`() {
        val result = buildDocumentResult(
            text = "Page 3:\nhello",
            firstUnit = 3,
            lastUnit = 5,
            totalUnits = 5,
        )

        assertEquals("Page 3:\nhello", result.text)
        assertEquals(3, result.firstUnit)
        assertEquals(5, result.lastUnit)
        assertEquals(5, result.totalUnits)
        assertFalse("读到最后一页 ⇒ 没有截断", result.truncated)
        assertNull("有内容就不该有说明", result.notice)
    }

    @Test
    fun `more units beyond the window mark the result truncated`() {
        val result = buildDocumentResult(
            text = "Page 1:\nx",
            firstUnit = 1,
            lastUnit = 1,
            totalUnits = 40,
        )

        assertTrue(result.truncated)
    }

    @Test
    fun `an unknown total (docx) never claims truncation`() {
        val result = buildDocumentResult(
            text = "para",
            firstUnit = 1,
            lastUnit = 1,
            totalUnits = -1,
        )

        assertFalse("总数未知时不能凭猜测说「还有更多」", result.truncated)
    }

    @Test
    fun `an empty extraction carries an explanation instead of a bare empty string`() {
        val result = buildDocumentResult(
            text = "   \n  ",
            firstUnit = 1,
            lastUnit = 3,
            totalUnits = 3,
        )

        assertEquals("", result.text.trim())
        val notice = result.notice
        assertNotNull("空抽取必须给模型一个解释", notice)
        assertTrue(notice!!.contains("No extractable text"))
        assertTrue("要提到扫描版这种最常见的原因", notice.contains("scanned"))
        assertFalse(
            "「抽不出文本」与「还有没给你的」是两件事：这次已经把 1..3 都读完了，不该报截断",
            result.truncated,
        )
    }

    @Test
    fun `an empty extraction of an early window still reports remaining units`() {
        // 扫描版 PDF 的典型情形：第 1 页抽不出文本，而后面还有 9 页
        val result = buildDocumentResult(
            text = "",
            firstUnit = 1,
            lastUnit = 1,
            totalUnits = 10,
        )

        assertNotNull(result.notice)
        assertTrue("还有 9 页没读 ⇒ 必须报截断（否则模型会以为整本都没文本）", result.truncated)
    }

    @Test
    fun `an empty slice reports a unit range that is empty by construction`() {
        // 空片段约定：lastUnit = firstUnit - 1（与文本窗口一致，不报一个不存在的单元号）
        val result = buildDocumentResult(
            text = "",
            firstUnit = 99,
            lastUnit = 98,
            totalUnits = 10,
        )

        assertEquals(99, result.firstUnit)
        assertEquals(98, result.lastUnit)
        assertFalse("第一页就超出范围 ⇒ 没有「更多单元」可承诺", result.truncated)
    }

    @Test
    fun `the character budget trims by whole lines and keeps at least one`() {
        val longLine = "x".repeat(500)
        val result = buildDocumentResult(
            text = List(10) { longLine }.joinToString("\n"),
            firstUnit = 1,
            lastUnit = 1,
            totalUnits = 1,
            maxBytes = 1_200,
        )

        assertTrue(result.truncated)
        assertEquals("按整行裁：500 字节的行只能留 2 行（500 + 1 + 500 = 1001 ≤ 1200）", 2, result.text.split('\n').size)

        val single = buildDocumentResult(
            text = longLine,
            firstUnit = 1,
            lastUnit = 1,
            totalUnits = 1,
            maxBytes = 100,
        )
        assertEquals("单行就超上限时保留它（空窗口比截断更难理解）", 500, single.text.length)
    }
}
