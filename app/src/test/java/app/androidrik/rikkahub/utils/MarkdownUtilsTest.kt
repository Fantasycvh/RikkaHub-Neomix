package app.androidrik.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 朗读用的纯文本化：先剥 HTML 再走 Markdown 清洗。
 *
 * 回归点：上游 issue #2222 —— `<details>/<summary>` 被 TTS 逐字读出。
 */
class MarkdownUtilsTest {

    @Test
    fun htmlTagsAreRemovedButInnerTextKept() {
        val result = "<details><summary>展开标题</summary>正文内容</details>".stripMarkdown()
        // 块级标签转换行，文字保留
        assertEquals("展开标题\n正文内容", result)
        assertFalse(result.contains("<"))
        assertFalse(result.contains("details"))
        assertFalse(result.contains("summary"))
    }

    @Test
    fun lineBreakTagsBecomeNewlines() {
        val result = "第一行<br>第二行".stripMarkdown()
        assertEquals("第一行\n第二行", result)
    }

    @Test
    fun inlineHtmlIsRemoved() {
        assertEquals("加粗内容", "<b>加粗</b><span>内容</span>".stripMarkdown())
        assertEquals("前文后文", "前文<!-- 注释 -->后文".stripMarkdown())
    }

    @Test
    fun plainComparisonsArePreserved() {
        // 正文里的比较式不是标签：TTS 不应把 "1 < 2" 吃掉
        assertEquals("1 < 2 且 3 > 2", "1 < 2 且 3 > 2".stripMarkdown())
    }

    @Test
    fun commonEntitiesAreDecoded() {
        assertEquals("a&b c", "a&amp;b&nbsp;c".stripMarkdown())
    }

    @Test
    fun existingMarkdownCleanupStillWorks() {
        assertEquals("粗体 与 链接", "**粗体** 与 [链接](https://example.com)".stripMarkdown())
        // 沿既有行为：代码内容整体不朗读
        assertEquals("文本", "`行内代码` 文本".stripMarkdown())
    }
}
