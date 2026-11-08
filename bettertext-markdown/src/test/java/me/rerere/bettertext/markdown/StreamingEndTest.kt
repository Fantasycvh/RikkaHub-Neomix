package me.rerere.bettertext.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingEndTest {
    /** Asserts that [streamed], while streaming, renders like [expected] does when complete. */
    private fun assertStreamsAs(expected: String, streamed: String) {
        assertEquals(streamed, parseMarkdown(expected).blocks, parseMarkdown(streamed, streaming = true).blocks)
    }

    private fun assertUnchanged(streamed: String) = assertStreamsAs(streamed, streamed)

    @Test
    fun `emphasis and code are closed`() {
        assertStreamsAs("a **bold**", "a **bold")
        assertStreamsAs("a ~~gone~~", "a ~~gone")
        assertStreamsAs("**a *b***", "**a *b")
        assertStreamsAs("a *it and `code`*", "a *it and `code")
        // Delimiters in code that is still open aren't converted to math.
        assertStreamsAs("""Code: `\(x\)`""", """Code: `\(x\)""")
        assertStreamsAs("- item **b**", "- item **b")
        assertStreamsAs("# Head **b**", "# Head **b")
        assertStreamsAs("> quote **b**", "> quote **b")
    }

    @Test
    fun `delimiters without text after them are hidden`() {
        assertStreamsAs("a", "a **")
        assertStreamsAs("a", "a `")
        assertStreamsAs("see", "see [")
        assertStreamsAs("see", "see ![")
    }

    @Test
    fun `unfinished math is hidden`() {
        assertStreamsAs("x", "x \$-\\frac{")
        assertStreamsAs("", "\$\$\nx = ")
        assertStreamsAs("> quote", "> quote \$x")
        assertStreamsAs("x", "x \\(a + ")
        // An escaped backslash before ( is text.
        assertUnchanged("""a \\(literal)""")
        assertStreamsAs("x", "x \\[\na = ")
        val table = parseMarkdown("| a |\n|---|\n| \$1, ", streaming = true).blocks.single() as MarkdownBlock.Table
        assertEquals(emptyList<MarkdownInline>(), table.rows.flatten().flatMap { it.content })
        // A dollar sign followed by prose is a price.
        assertUnchanged("costs \$5 each")
    }

    @Test
    fun `unfinished links images and tags are hidden`() {
        assertStreamsAs("see text", "see [text](https://exa")
        assertStreamsAs("see", "see ![alt](https://i")
        assertStreamsAs("see", "see ![alt]")
        assertStreamsAs("see", "see ![alt][lo")
        // Reference images that resolve are complete.
        assertUnchanged("[logo]: https://example.com/image.png\n\n![logo]")
        assertUnchanged("[logo]: https://example.com/image.png\n\n![alt][logo]")
        assertStreamsAs("text", "text <b")
        assertStreamsAs("text", "text\n\n<")
        assertStreamsAs("", "<span style")
        assertStreamsAs("<details>\n<summary>More</summary>", "<details>\n<summary>More</summary></")
    }

    @Test
    fun `blocks that would change are hidden`() {
        assertStreamsAs("Intro", "Intro\n\n| a | b |")
        assertStreamsAs("Title", "Title\n=")
        assertStreamsAs("Title", "Title\n-")
        assertStreamsAs("Title", "Title\n- ")
        assertUnchanged("Title\n===\n")
    }

    @Test
    fun `text that only looks like syntax is left alone`() {
        assertUnchanged("a * b")
        assertUnchanged("snake_case_name")
        assertUnchanged("```\na **b")
        assertUnchanged("Code: `a **b`")
        // A finished paragraph stays as written, even with unmatched delimiters.
        assertUnchanged("a **b\n\nnext")
    }

    @Test
    fun `nothing changes when not streaming`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("a **bold")))),
            parseMarkdown("a **bold").blocks,
        )
    }
}
