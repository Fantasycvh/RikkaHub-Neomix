package me.rerere.bettertext.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownStreamParserTest {
    private val document = $$"""
        # Title

        [ref]: https://example.com/ref "Reference"

        A paragraph with **bold**, *italic*, `code`, a [link](https://example.com) and $E = mc^2$.
        Second line \(a + b\) of the paragraph.

        Setext heading
        ==============

        - item one
        - item two

          continued in the item
        - [x] done

        1. first
        2. second

        > quote with @@x@@ math
        lazy continuation

        ```kotlin
        fun main() = println("<div>")
        ```

        ~~~latex
        \(x\) stays as written
        ~~~

            \(y\) in indented code

        > ```
        > \(z\) quoted code

        Math after the quote: \(z\).

        | a | b |
        |:--|--:|
        | 1 | 2 |

        <details open>
        <summary>More</summary>

        Hidden *text*.

        </details>

        <span style="color: red">loose</span>

        <b>also loose</b>

        \[
        \int_0^1 x\,dx
        \]

        Escaped \\(literal), code `\(x\)` and an image ![logo][ref].

        Last paragraph with a [reference][ref].
    """.trimIndent().replace("@@", "\$\$")

    @Test
    fun everyPrefixMatchesFullParse() {
        val parser = MarkdownStreamParser()
        for (end in 1..document.length) {
            val prefix = document.substring(0, end)
            assertEquals("prefix of length $end", parseMarkdown(prefix).blocks, parser.parse(prefix).blocks)
        }
    }

    @Test
    fun everyPrefixMatchesFullParseWhileStreaming() {
        val parser = MarkdownStreamParser()
        for (end in 1..document.length) {
            val prefix = document.substring(0, end)
            assertEquals(
                "prefix of length $end",
                parseMarkdown(prefix, streaming = true).blocks,
                parser.parse(prefix, streaming = true).blocks,
            )
        }
        // The final parse, once streaming ends, drops the completions.
        assertEquals(parseMarkdown(document).blocks, parser.parse(document).blocks)
    }

    @Test
    fun streamingInTokensMatchesFullParse() {
        val parser = MarkdownStreamParser()
        var end = 0
        while (end < document.length) {
            end = minOf(document.length, end + 7)
            val prefix = document.substring(0, end)
            assertEquals("prefix of length $end", parseMarkdown(prefix).blocks, parser.parse(prefix).blocks)
        }
    }

    @Test
    fun editsInTheMiddleMatchFullParse() {
        val parser = MarkdownStreamParser()
        parser.parse(document)
        val edited = document.replace("item two", "item 2 with **more**")
        assertEquals(parseMarkdown(edited).blocks, parser.parse(edited).blocks)
        val shortened = document.substring(0, document.length / 2)
        assertEquals(parseMarkdown(shortened).blocks, parser.parse(shortened).blocks)
    }

    @Test
    fun definitionsInKeptChunksStillApply() {
        val parser = MarkdownStreamParser()
        val text = "[ref]: https://example.com\n\nOne\n\nTwo\n\nThree\n\n[label][ref]"
        parser.parse(text)
        val appended = "$text!"
        assertEquals(parseMarkdown(appended).blocks, parser.parse(appended).blocks)
    }

    @Test
    fun keepsBlocksBeforeTheTail() {
        val parser = MarkdownStreamParser()
        val first = parser.parse(document).blocks
        val second = parser.parse("$document\n\nMore text").blocks
        // Blocks before the reparsed chunks are the same instances, so Compose skips them cheaply.
        assertSame(first[0], second[0])
        assertSame(first[3], second[3])
    }
}
