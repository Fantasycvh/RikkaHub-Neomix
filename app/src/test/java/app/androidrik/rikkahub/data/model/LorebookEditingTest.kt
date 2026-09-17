package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LorebookEditingTest {
    private val first = PromptInjection.RegexInjection(
        name = "first", priority = 90, keywords = listOf("alpha"), secondaryKeywords = listOf("beta"),
        selectiveLogic = SelectiveLogic.NOT_ANY, useRegex = true, caseSensitive = true,
        scanDepth = 7, injectDepth = 3, position = InjectionPosition.AT_DEPTH, enabled = false,
        constantActive = true, matchAssistantPrompt = true, content = "retained content",
    )
    private val second = PromptInjection.RegexInjection(name = "second", priority = -20)
    private val third = PromptInjection.RegexInjection(name = "third", priority = 90)
    private val book = Lorebook(
        name = "book", description = "description", enabled = false, entries = listOf(second, first, third),
    )

    @Test
    fun `display is descending and stable without changing storage`() {
        assertEquals(listOf(first, third, second), book.entriesForDisplay())
        assertEquals(listOf(second, first, third), book.entries)
        assertEquals(listOf(-20, 90, 90), book.entries.map { it.priority })
    }

    @Test
    fun `drag resolves keys against display not storage or header indices`() {
        val moved = book.reorderEntries(first.id, second.id)
        assertEquals(listOf(third.id, second.id, first.id), moved.entries.map { it.id })
        assertEquals(listOf(2, 1, 0), moved.entries.map { it.priority })
        assertEquals(moved.entries, moved.entriesForDisplay())
        assertEquals(first.copy(priority = 0), moved.entries.last())
        assertEquals(book.copy(entries = moved.entries), moved)
    }

    @Test
    fun `last entry can move to first and back`() {
        val moved = book.reorderEntries(second.id, first.id)
        assertEquals(listOf(second.id, first.id, third.id), moved.entries.map { it.id })
        val back = moved.reorderEntries(second.id, third.id)
        assertEquals(listOf(first.id, third.id, second.id), back.entries.map { it.id })
        assertEquals(book.entries.map { it.id }.toSet(), back.entries.map { it.id }.toSet())
    }

    @Test
    fun `same key and non-entry keys do not normalize priorities`() {
        assertSame(book, book.reorderEntries(first.id, first.id))
        assertSame(book, book.reorderEntries("orderHint", first.id))
        assertSame(book, book.reorderEntries(first.id, "lorebookBudget"))
        assertSame(book, book.reorderEntries(PromptInjection.RegexInjection().id, first.id))
    }

    @Test
    fun `empty and single entry drags are no ops`() {
        val empty = Lorebook()
        assertSame(empty, empty.reorderEntries(first.id, second.id))
        val single = book.copy(entries = listOf(first))
        assertSame(single, single.reorderEntries(first.id, first.id))
    }

    @Test
    fun `update replaces identity and retains other entries and book fields`() {
        val edited = first.copy(name = "edited", enabled = true)
        val updated = book.updateEntry(edited)
        assertEquals(book.copy(entries = listOf(second, edited, third)), updated)
        assertEquals(listOf(-20, 90, 90), updated.entries.map { it.priority })
    }

    @Test
    fun `new entry appends without normalizing existing priorities`() {
        val added = PromptInjection.RegexInjection(priority = 500)
        assertEquals(book.copy(entries = book.entries + added), book.updateEntry(added))
    }

    @Test
    fun `updates compose with latest book rather than dropping prior edits`() {
        val added = PromptInjection.RegexInjection(name = "imported")
        val latest = book.copy(description = "latest", entries = book.entries + added)
        val updated = latest.updateEntry(first.copy(content = "changed"))
        assertEquals("latest", updated.description)
        assertEquals(added, updated.entries.last())
        assertEquals("changed", updated.entries.first { it.id == first.id }.content)
    }
}
