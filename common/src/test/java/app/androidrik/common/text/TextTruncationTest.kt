package app.androidrik.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全截断：按 UTF-16 单元数截断且不切断代理对。
 *
 * 抽到 common 供「消息引用快照」与「拼装预览段正文折叠」共用——两处都显示截断文本，
 * 切坏代理对会产出孤立高代理（显示成乱码、JSON 里变问号）。
 */
class TextTruncationTest {

    @Test
    fun `shorter than limit is returned as is`() {
        assertEquals("abc", "abc".truncateSafely(10))
        assertEquals("abc", "abc".truncateSafely(3))
    }

    @Test
    fun `longer than limit is cut at the limit`() {
        assertEquals("abcde", "abcdefghij".truncateSafely(5))
    }

    @Test
    fun `never splits a surrogate pair`() {
        // emoji 是代理对（占 2 个 UTF-16 单元）；上限落在对中间时必须回退一个单元
        val text = "a".repeat(7) + "\uD83D\uDE00" + "b".repeat(10)
        val cut = text.truncateSafely(8)
        assertTrue("不得以孤立高代理收尾", !cut.last().isHighSurrogate())
        assertEquals(7, cut.length)
        assertEquals("a".repeat(7), cut)
    }

    @Test
    fun `keeps the whole pair when it fits`() {
        val text = "a".repeat(7) + "\uD83D\uDE00"
        assertEquals(text, text.truncateSafely(9))
    }

    @Test
    fun `zero and negative limits yield empty`() {
        assertEquals("", "abc".truncateSafely(0))
        assertEquals("", "abc".truncateSafely(-3))
    }

    @Test
    fun `limit exactly at a non-surrogate boundary keeps full width`() {
        val text = "ab\uD83D\uDE00cd"
        // 前 2 个单元是普通字符 ⇒ 原样取 2
        assertEquals("ab", text.truncateSafely(2))
        // 第 3 个单元是低代理…实际是高代理，取 3 会切坏 ⇒ 退到 2
        assertEquals("ab", text.truncateSafely(3))
        // 取 4 = 完整 emoji 收尾，安全
        assertEquals("ab\uD83D\uDE00", text.truncateSafely(4))
    }

    @Test
    fun `all astral text yields empty when limit is one`() {
        // 唯一字符是代理对、上限 1 ⇒ 只能全丢，不能返回半个字符
        assertEquals("", "\uD83D\uDE00".truncateSafely(1))
    }
}
