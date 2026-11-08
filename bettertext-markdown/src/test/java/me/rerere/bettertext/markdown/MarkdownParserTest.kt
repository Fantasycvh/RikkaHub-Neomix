package me.rerere.bettertext.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import me.rerere.bettertext.markdown.MarkdownBlock.CodeBlock
import me.rerere.bettertext.markdown.MarkdownBlock.Container
import me.rerere.bettertext.markdown.MarkdownBlock.Details
import me.rerere.bettertext.markdown.MarkdownBlock.Heading
import me.rerere.bettertext.markdown.MarkdownBlock.ListBlock
import me.rerere.bettertext.markdown.MarkdownBlock.Paragraph
import me.rerere.bettertext.markdown.MarkdownBlock.Quote
import me.rerere.bettertext.markdown.MarkdownBlock.Table
import me.rerere.bettertext.markdown.MarkdownInline.Code
import me.rerere.bettertext.markdown.MarkdownInline.Image
import me.rerere.bettertext.markdown.MarkdownInline.Link
import me.rerere.bettertext.markdown.MarkdownInline.Math
import me.rerere.bettertext.markdown.MarkdownInline.Styled
import me.rerere.bettertext.markdown.MarkdownInline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun blocks(markdown: String, softBreakAsLineBreak: Boolean = true) =
        parseMarkdown(markdown.trimIndent(), softBreakAsLineBreak).blocks

    private inline fun <reified T : MarkdownBlock> single(markdown: String): T {
        val blocks = blocks(markdown)
        assertEquals("expected a single block but got $blocks", 1, blocks.size)
        return blocks.single() as T
    }

    @Test
    fun `paragraph with inline styles`() {
        val paragraph = single<Paragraph>("Hello **bold** and *italic* and ~~gone~~ `code`")
        assertEquals("Hello bold and italic and gone code", paragraph.content.plainText())

        val (bold, italic, strike) = paragraph.content.filterIsInstance<Styled>()
        assertEquals(FontWeight.Bold, bold.style.fontWeight)
        assertEquals(listOf(Text("italic")), italic.children)
        assertEquals(TextDecoration.LineThrough, strike.style.textDecoration)
        assertEquals(Code("code"), paragraph.content.last())
    }

    @Test
    fun `single tildes strike through even as the first delimiter`() {
        for (source in listOf("~abc~", "abc~abc~", "~abc~**", "*abc~abc~")) {
            val strike = single<Paragraph>(source).content.filterIsInstance<Styled>().single()
            assertEquals(source, TextDecoration.LineThrough, strike.style.textDecoration)
            assertEquals(source, listOf(Text("abc")), strike.children)
        }
    }

    @Test
    fun `inline math touching punctuation or CJK text`() {
        val bold = single<Paragraph>("选 **B：\\(\\eta\\)**。").content.filterIsInstance<Styled>().single()
        assertEquals(listOf(Text("B："), Math("\\eta", display = false)), bold.children)
        for (source in listOf("(\$x\$)", "设\$x\$为", "：\$x\$")) {
            assertEquals(source, Math("x", display = false), single<Paragraph>(source).content.filterIsInstance<Math>().single())
        }
    }

    @Test
    fun `soft break follows the option`() {
        val source = """
            first
            second
        """
        assertEquals(listOf(Paragraph(listOf(Text("first\nsecond")))), blocks(source))
        assertEquals(listOf(Paragraph(listOf(Text("first second")))), blocks(source, softBreakAsLineBreak = false))
    }

    @Test
    fun `line break does not produce an empty line`() {
        assertEquals(listOf(Text("first\nsecond")), single<Paragraph>("first  \nsecond").content)
        assertEquals(listOf(Text("ha\nha")), single<Paragraph>("ha<br/>ha").content)
    }

    @Test
    fun `headings keep their level`() {
        val blocks = blocks(
            """
            # One
            ### Three
            """
        )
        assertEquals(listOf(Heading(1, listOf(Text("One"))), Heading(3, listOf(Text("Three")))), blocks)
    }

    @Test
    fun `code fence reports language and whether it is closed`() {
        val closed = single<CodeBlock>(
            """
            ```kotlin
            val a = 1 < 2
            ```
            """
        )
        assertEquals(CodeBlock(code = "val a = 1 < 2", language = "kotlin", closed = true), closed)

        val streaming = single<CodeBlock>(
            """
            ```html
            <div>
            """
        )
        assertEquals(CodeBlock(code = "<div>", language = "html", closed = false), streaming)
    }

    @Test
    fun `code fences close with CRLF line endings`() {
        val source = "````\n文字 A\n\n````\n文字 B\n```\n\n文字 C\n```".replace("\n", "\r\n")
        assertEquals(
            listOf(
                CodeBlock(code = "文字 A", language = null, closed = true),
                Paragraph(listOf(Text("文字 B"))),
                CodeBlock(code = "\n文字 C", language = null, closed = true),
            ),
            parseMarkdown(source).blocks,
        )
    }

    @Test
    fun `lists keep start number nesting and task state`() {
        val list = single<ListBlock>(
            """
            3. three
            4. four
               - [x] done
               - [ ] todo
            """
        )
        assertTrue(list.ordered)
        assertTrue(list.tight)
        assertEquals(3, list.start)
        assertEquals(listOf(Paragraph(listOf(Text("three")))), list.items[0].children)
        assertNull(list.items[0].checked)

        val (text, nested) = list.items[1].children
        assertEquals(Paragraph(listOf(Text("four"))), text)
        nested as ListBlock
        assertFalse(nested.ordered)
        assertEquals(listOf(true, false), nested.items.map { it.checked })
        assertEquals(listOf(Paragraph(listOf(Text("done")))), nested.items[0].children)
    }

    @Test
    fun `list separated by blank lines is loose`() {
        val list = single<ListBlock>(
            """
            - one

            - two
            """
        )
        assertFalse(list.tight)
        assertEquals(listOf(Paragraph(listOf(Text("two")))), list.items[1].children)
    }

    @Test
    fun `table keeps cells alignment and source`() {
        val source = """
            | a | b |
            |:--|:-:|
            | 1 | **2** |
        """.trimIndent()
        val table = single<Table>(source)
        assertEquals(listOf("a", "b"), table.header.map { it.content.plainText() })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.content.plainText() } })
        assertEquals(listOf(null, TextAlign.Center), table.alignments)
        assertEquals(source, table.source)
    }

    @Test
    fun `html table cells keep their own style`() {
        val table = single<Table>(
            """
            <table style="font-size: 12px">
              <tr><th align="center">状态</th><th>数量</th></tr>
              <tr align="right"><td style="color: red; font-weight: bold">异常</td><td style="text-align: left">3</td></tr>
              <tr><td>正常</td><td>5</td></tr>
            </table>
            """
        )
        assertEquals(listOf(TextAlign.Center, null), table.alignments)
        assertNull(table.source)

        val (error, count) = table.rows[0]
        assertEquals("异常", error.content.plainText())
        assertEquals(Color.Red, error.style!!.color)
        assertEquals(FontWeight.Bold, error.style!!.fontWeight)
        // Cells inherit the row's alignment, but a cell's own alignment wins.
        assertEquals(TextAlign.End, error.style!!.textAlign)
        assertEquals(TextAlign.Start, count.style!!.textAlign)
        // The table style reaches cells that have no style of their own.
        assertEquals(12f, table.rows[1][0].style!!.fontSize.value)
        assertEquals(TextAlign.Unspecified, table.rows[1][0].style!!.textAlign)
    }

    @Test
    fun `markdown table cells without alignment have no style`() {
        val table = single<Table>(
            """
            | a | b |
            |---|--:|
            | 1 | 2 |
            """
        )
        assertEquals(listOf(null, TextAlign.End), table.alignments)
        assertNull(table.header[0].style)
        assertNull(table.rows[0][0].style)
    }

    @Test
    fun `quote contains blocks`() {
        val quote = single<Quote>(
            """
            > quoted
            > text
            """
        )
        assertEquals(listOf(Paragraph(listOf(Text("quoted\ntext")))), quote.children)
    }

    @Test
    fun `links images and math`() {
        val paragraph = single<Paragraph>("""[text](https://a.com "title") ![alt](https://i.png) ${'$'}x^2$""")
        assertEquals(
            listOf(
                Link("https://a.com", listOf(Text("text")), "title"),
                Text(" "),
                Image("https://i.png", alt = "alt"),
                Text(" "),
                Math("x^2", display = false),
            ),
            paragraph.content,
        )
    }

    @Test
    fun `latex delimiters are normalized outside of code`() {
        assertEquals(listOf(Math("a+b")), single<Paragraph>("""\(a+b\)""").content)
        assertEquals(listOf(Math("a + b")), single<Paragraph>("""\( a + b \)""").content)
        assertEquals(
            listOf(Math("a = b", display = true)),
            single<Paragraph>(
                """
                \[
                a = b
                \]
                """
            ).content,
        )
        assertEquals(listOf(Code("""\(a\)""")), single<Paragraph>("""`\(a\)`""").content)
        assertEquals(listOf(Code("""\(a\)""")), single<Paragraph>("""`` \(a\) ``""").content)
        // No replacement inside an unclosed code fence either.
        assertEquals("""\[a\]""", single<CodeBlock>("```\n\\[a\\]").code)
        // Tilde fences, and fences that only close with a run at least as long.
        assertEquals("""\(x\)""", single<CodeBlock>("~~~latex\n\\(x\\)\n~~~").code)
        assertEquals("```\n\\(x\\)\n```", single<CodeBlock>("````\n```\n\\(x\\)\n```\n````").code)
        // A fence of the other character doesn't close the block, and math after the block is converted.
        val fenced = blocks("~~~\n```\n\\(x\\)\n~~~\n\n\\(y\\)")
        assertEquals("```\n\\(x\\)", (fenced[0] as CodeBlock).code)
        assertEquals(listOf(Math("y")), (fenced[1] as Paragraph).content)
        // Indented code blocks (after a paragraph, since the helper trims indentation).
        assertEquals("""\(x\)""", (blocks("Code:\n\n    \\(x\\)")[1] as CodeBlock).code)
        // A fence in a quote ends with the quote.
        val quoted = blocks("> ```\n> code\n\n\\(x\\)")
        assertEquals(listOf(Math("x")), (quoted[1] as Paragraph).content)
        // An escaped backslash before a backtick leaves the backtick free to open code.
        assertEquals(
            listOf(Text("\\"), Code("""\(x\)""")),
            single<Paragraph>("""\\`\(x\)`""").content,
        )
        // \\[4pt] is a line break with extra spacing, not the start of a display formula.
        assertEquals(
            listOf(Math("""a\\[4pt]b""", display = true), Text(" and "), Math("c", display = true)),
            single<Paragraph>("""${'$'}${'$'}a\\[4pt]b${'$'}${'$'} and \[c\]""").content,
        )
    }

    @Test
    fun `math keeps less than signs`() {
        val cases = """\begin{cases}1/2, & 0<x<1\\ \infty, & 1\le x<2\end{cases}"""
        assertEquals(
            listOf(Math(cases, display = true)),
            single<Paragraph>("${'$'}${'$'}\n$cases\n${'$'}${'$'}").content,
        )
        assertEquals(
            listOf(Text("if "), Math("a<b"), Text(" and "), Math("x<y>z"), Text(" or "), Math("a&b")),
            single<Paragraph>("""if ${'$'}a<b$ and ${'$'}x<y>z$ or ${'$'}a&b$""").content,
        )
    }

    @Test
    fun `inline html wraps markdown content`() {
        val paragraph = single<Paragraph>("""<span style="color: #f00; font-size: 20px">**red**</span> text""")
        val span = paragraph.content.first() as Styled
        assertEquals(Color.Red, span.style.color)
        assertEquals(20f, span.style.fontSize.value)
        assertEquals(FontWeight.Bold, (span.children.single() as Styled).style.fontWeight)
        assertEquals(Text(" text"), paragraph.content.last())
    }

    @Test
    fun `html block split by blank lines is joined back`() {
        val details = single<Details>(
            """
            <details>
            <summary>Sum</summary>

            inner **md**

            </details>
            """
        )
        assertEquals("Sum", details.summary.plainText())
        assertFalse(details.open)
        assertEquals("inner md", (details.children.single() as Paragraph).content.plainText())
    }

    @Test
    fun `html container carries block style`() {
        val container = single<Container>(
            """
            <div align="center">
              <b>Title</b>
              <i>sub</i>
            </div>
            """
        )
        assertEquals(TextAlign.Center, container.style.textAlign)
        // Indentation and newlines in hand-written HTML collapse to spaces and don't produce line breaks.
        assertEquals("Title sub", (container.children.single() as Paragraph).content.plainText())
    }

    @Test
    fun `unknown inline tags stay as text`() {
        assertEquals(
            listOf(Text("a List<String> and <answer>42</answer>")),
            single<Paragraph>("a List<String> and <answer>42</answer>").content,
        )
    }

    @Test
    fun `script and style content is dropped`() {
        assertEquals(
            listOf(Paragraph(listOf(Text("visible")))),
            blocks(
                """
                <style>p { color: red }</style>

                visible
                """
            ),
        )
    }

    @Test
    fun `paragraph is split around images and display math`() {
        val content = single<Paragraph>("before [![badge](https://i.png)](https://a.com) after").content
        assertEquals(
            listOf(
                InlineSegment.Text(listOf(Text("before "))),
                InlineSegment.Image(Image("https://i.png", alt = "badge"), link = "https://a.com"),
                InlineSegment.Text(listOf(Text(" after"))),
            ),
            content.toSegments(),
        )

        val plain = listOf(Text("plain"))
        assertEquals(listOf(InlineSegment.Text(plain)), plain.toSegments())
    }

    @Test
    fun `css values`() {
        assertEquals(Color(0x80, 0x00, 0xFF, 0x80), parseCssColor("rgba(128, 0, 255, 0.5)"))
        assertEquals(Color(0x11, 0x22, 0x33, 0xFF), parseCssColor("#123"))
        assertEquals(Color(0xFF008000), parseCssColor("Green"))
        assertNull(parseCssColor("#12"))
        assertNull(parseSpanStyle("unknown: 1"))
        assertEquals(1.5f, parseSpanStyle("font-size: 150%")!!.fontSize.value)
    }
}
