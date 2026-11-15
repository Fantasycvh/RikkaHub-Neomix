package app.androidrik.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [replaceRegexesGuarded]：#2317 治本（A）守卫——非视觉正则的写回会落库，
 * 把非空内容整体清成空白的规则必须保留原文，否则留下空候选。
 */
class RegexOutputTransformerGuardTest {

    @Test fun `replacement that blanks out non blank content keeps original`() {
        val result = replaceRegexesGuarded("完整回答内容") { text ->
            text.replace(Regex("(?s).*"), "")
        }
        assertEquals("完整回答内容", result)
    }

    @Test fun `replacement that blanks already blank content is accepted`() {
        assertEquals("", replaceRegexesGuarded("   ") { "" })
        assertEquals("", replaceRegexesGuarded("") { "" })
    }

    @Test fun `normal replacement and partial blanking pass through`() {
        // 正常替换
        assertEquals("b", replaceRegexesGuarded("a") { "b" })
        // 局部清空（消息里还有其他非空 part）不拦
        assertEquals("\n原文B", replaceRegexesGuarded("原文A\n原文B") { it.replace("原文A", "") })
    }

    @Test fun `whitespace only replacement counts as blanking`() {
        val result = replaceRegexesGuarded("有内容") { _ -> "   " }
        assertEquals("有内容", result)
    }
}
