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
    }

    /**
     * 上游 Miffan 6efec385a：行内代码的**文本**必须保留（TTS 要念出 `kubectl` 这类关键词），
     * 只去掉反引号本身。旧实现把整段行内代码删掉，朗读直接丢信息。
     */
    @Test
    fun inlineCodeTextIsPreserved() {
        assertEquals("用 kubectl apply 部署", "用 `kubectl apply` 部署".stripMarkdown())
        // 多个行内代码片段都要保留，且反引号不残留
        val result = "先 `a` 再 `b`".stripMarkdown()
        assertEquals("先 a 再 b", result)
        assertFalse(result.contains("`"))
    }

    /** 围栏代码块仍整段删除（既有行为不回归）：长代码不该被朗读出来。 */
    @Test
    fun fencedCodeBlockIsStillRemoved() {
        val result = "前文\n```kotlin\nval x = 1\n```\n后文".stripMarkdown()
        assertEquals("前文\n\n后文", result)
        assertFalse(result.contains("val x"))
    }

    /** 混合场景：围栏块删、行内代码留，两条正则互不干扰。 */
    @Test
    fun mixedInlineAndFencedCode() {
        val result = "解释 `inline` 与\n```\nblock code\n```\n结尾".stripMarkdown()
        assertEquals("解释 inline 与\n\n结尾", result)
        assertFalse(result.contains("block code"))
    }

    /**
     * 行内代码里的 Markdown 字符不能被二次清洗（占位符保护）：
     * `my_var_name` 的 snake_case、`a*b` 的星号都要原样留给 TTS 念。
     */
    @Test
    fun inlineCodeIsProtectedFromOtherMarkdownRules() {
        assertEquals("变量 my_var_name 别被洗掉", "变量 `my_var_name` 别被洗掉".stripMarkdown())
        assertEquals("表达式 a*b 原样", "表达式 `a*b` 原样".stripMarkdown())
        assertEquals("路径 /tmp/__x__", "路径 `/tmp/__x__`".stripMarkdown())
    }
}
