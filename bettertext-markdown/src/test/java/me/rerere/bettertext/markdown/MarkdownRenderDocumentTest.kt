package me.rerere.bettertext.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownRenderDocumentTest {
    private fun assertProjection(source: String, document: MarkdownRenderDocument, streaming: Boolean = false) {
        assertEquals(source, document.document.source)
        assertEquals(parseMarkdown(source, streaming = streaming).blocks, document.document.blocks)
        assertEquals(document.document.blocks, document.units.flatMap { it.blocks })
        assertEquals(document.units.size, document.units.map { it.id }.distinct().size)
        var offset = 0
        var blockIndex = 0
        for (unit in document.units) {
            assertEquals(offset, unit.sourceRange.start)
            assertTrue(unit.syntaxRange.start >= unit.sourceRange.start)
            assertTrue(unit.syntaxRange.end <= unit.sourceRange.end)
            assertEquals(blockIndex, unit.firstBlockIndex)
            offset = unit.sourceRange.end
            blockIndex += unit.blocks.size
        }
        assertEquals(document.renderSource.length, offset)
        assertEquals(document.renderSource, document.units.joinToString("") {
            document.renderSource.substring(it.sourceRange.start, it.sourceRange.end)
        })
    }

    @Test fun `units cover whitespace definitions code HTML math and supplementary text`() {
        val samples = listOf("", " \r\n\r\n ", "[id]: https://example.com\n\n", "段😀落\r\n\r\n第二段",
            "before\n\n```kotlin\nval x = 1\n```\n\nafter", "before\n\n<div>\n\n**bold**\n\n</div>\n\nafter",
            "inline \\( x \\)\r\n\r\n\\[a\n+b\\]", "| A | B |\n|---|---|\n| 1 | 2 |\n\n- one\n- two")
        for (source in samples) assertProjection(source, MarkdownStreamParser().parseRenderDocument(source))
    }

    @Test fun `every streaming prefix and finalization preserve complete parse results`() {
        val source = "# 标题\n\n[ref]: https://example.com\n\nfirst\n\nsecond\n\nthird\n\n" +
            "```kotlin\nval x = \\\"ok\\\"\n```\n\n- one\n- two\n\n" +
            "<details>\n<summary>更多</summary>\n\ntext\n\n</details>\n\n[link][ref] **bold** \\( x \\)"
        val parser = MarkdownStreamParser()
        for (end in 1..source.length) {
            val prefix = source.take(end)
            assertProjection(prefix, parser.parseRenderDocument(prefix, streaming = true), streaming = true)
        }
        assertProjection(source, parser.parseRenderDocument(source))
    }

    @Test fun `growing tail and retained prefix keep identity without content hashes`() {
        val parser = MarkdownStreamParser()
        val original = "first\n\nsecond\n\nthird\n\n```kotlin\nval a = 1"
        val first = parser.parseRenderDocument(original, true)
        val second = parser.parseRenderDocument(original + "\nval b = 2", true)
        assertSame(first.units[0], second.units[0])
        assertEquals(first.units.last().id, second.units.last().id)
        assertEquals(first.generation, second.generation)
        assertTrue(second.revision > first.revision)
        val closed = parser.parseRenderDocument(original + "\nval b = 2\n```", false)
        assertEquals(first.units.last().id, closed.units.last().id)
        assertTrue((closed.units.last().blocks.single() as MarkdownBlock.CodeBlock).closed)
        assertSame(closed, parser.parseRenderDocument(closed.document.source))
    }

    @Test fun `tail changing node kind retains identity but middle edits start a new generation`() {
        val parser = MarkdownStreamParser()
        val paragraph = parser.parseRenderDocument("first\n\nHeading")
        val heading = parser.parseRenderDocument("first\n\nHeading\n---")
        assertTrue(heading.units.last().blocks.single() is MarkdownBlock.Heading)
        assertEquals(paragraph.units.last().id, heading.units.last().id)
        val edited = parser.parseRenderDocument("changed\n\nHeading\n---")
        assertTrue(edited.generation > heading.generation)
        assertTrue(edited.units.none { next -> heading.units.any { it.id == next.id } })
        assertEquals(0, edited.reparsedFrom)
        assertProjection(edited.document.source, edited)
    }

    @Test fun `late reference definitions invalidate earlier links and removals`() {
        val parser = MarkdownStreamParser()
        val text = "[early][ref]\n\none\n\ntwo\n\nthree\n\nfour"
        val initial = parser.parseRenderDocument(text)
        val appended = "$text\n\n[ref]: https://example.com"
        val defined = parser.parseRenderDocument(appended)
        assertProjection(appended, defined)
        assertEquals(0, defined.reparsedFrom)
        assertEquals(initial.units.first().id, defined.units.first().id)
        val removed = parser.parseRenderDocument(text)
        assertProjection(text, removed)
    }

    @Test fun `normalized and synthetic offsets are distinct from source offsets`() {
        val source = "a\r\n\r\n\\[x\n+y\\]\r\n\r\n**tail"
        val document = MarkdownStreamParser().parseRenderDocument(source, true)
        assertProjection(source, document, true)
        assertNotEquals(source, document.renderSource)
        assertFalse(document.renderSource.contains('\r'))
        assertTrue(document.renderSource.endsWith("**"))
    }

    @Test fun `repeated identical blocks have separate identities across long documents`() {
        for (size in listOf(10_000, 50_000, 100_000)) {
            val source = "重复段落与 **格式**。\n\n".repeat(size / 15 + 1).take(size)
            assertProjection(source, MarkdownStreamParser().parseRenderDocument(source))
        }
    }
}
