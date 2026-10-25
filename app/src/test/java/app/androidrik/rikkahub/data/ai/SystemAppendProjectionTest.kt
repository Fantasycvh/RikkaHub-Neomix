package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transformer 追加到 system 的文本（如工作区提醒）必须投影到预览，且不能把块/注入重复计入。
 */
class SystemAppendProjectionTest {
    private fun segment(actual: String, generated: String) = systemExtraSegment(
        actual = actual,
        generated = generated,
        role = "system",
        label = "系统追加（合成）",
        countTokens = { it.length },
    )

    @Test
    fun `identical system text produces no extra segment`() {
        assertNull(segment("BASE\nTIKZ", "BASE\nTIKZ"))
    }

    @Test
    fun `appended workspace prompt is projected without duplicating blocks`() {
        val generated = "BASE\nTIKZ"
        val actual = generated + "\n\n<workspace>remote</workspace>"

        val extra = segment(actual, generated) ?: error("extra segment missing")

        assertEquals(PromptSegmentKind.INJECTION, extra.kind)
        assertTrue(extra.isSynthetic)
        assertEquals("<workspace>remote</workspace>", extra.content)
        assertEquals(extra.content.length, extra.tokens)
    }

    @Test
    fun `prepended text is projected`() {
        val generated = "BASE"
        assertEquals("<before>", segment("<before>\nBASE", generated)?.content)
    }

    @Test
    fun `internal edit projects only the differing middle`() {
        assertEquals("X", segment("abcXef", "abcdef")?.content)
    }

    @Test
    fun `separator-only difference is ignored`() {
        assertNull(segment("BASE\n\n", "BASE"))
    }
}
