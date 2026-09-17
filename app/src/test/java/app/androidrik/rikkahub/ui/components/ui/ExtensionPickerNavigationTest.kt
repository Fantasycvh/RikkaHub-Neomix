package app.androidrik.rikkahub.ui.components.ui

import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.PromptPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the page data consumed by the chat picker, not rendered Compose interactions. */
class ExtensionPickerNavigationTest {
    @Test
    fun `preset root contains all containers including empty and disabled presets`() {
        val entries = listOf(PromptInjection.ModeInjection(name = "A"), PromptInjection.ModeInjection(name = "B"))
        val presets = listOf(
            PromptPreset(name = "First", entries = entries),
            PromptPreset(name = "Empty"),
            PromptPreset(name = "Disabled", enabled = false, entries = listOf(PromptInjection.ModeInjection())),
        )
        val navigation = ExtensionPickerNavigation()
        assertEquals(ExtensionPickerPage.Containers(presets), navigation.presets(presets))
        assertFalse(navigation.isDetail)
        navigation.openPreset(presets.first().id)
        assertEquals(ExtensionPickerPage.Detail(presets.first()), navigation.presets(presets))
        assertTrue(navigation.isDetail)
        navigation.back()
        assertEquals(ExtensionPickerPage.Containers(presets), navigation.presets(presets))
        assertFalse(navigation.isDetail)
    }

    @Test
    fun `worldbook root contains books and detail retains entry metadata without editing global status`() {
        val entry = PromptInjection.RegexInjection(name = "Entry", enabled = false, keywords = listOf("key"))
        val books = listOf(Lorebook(name = "Book", entries = listOf(entry)), Lorebook(name = "Empty"))
        val navigation = ExtensionPickerNavigation()
        assertEquals(ExtensionPickerPage.Containers(books), navigation.worldbooks(books))
        navigation.openWorldbook(books.first().id)
        val page = navigation.worldbooks(books) as ExtensionPickerPage.Detail
        assertEquals(listOf(entry), page.item.entries)
        assertFalse(page.item.entries.first().enabled)
        navigation.back()
        assertEquals(ExtensionPickerPage.Containers(books), navigation.worldbooks(books))
        assertFalse(navigation.isDetail)
    }

    @Test
    fun `opening another kind clears prior detail and deleted containers fall back to root`() {
        val preset = PromptPreset()
        val book = Lorebook()
        val navigation = ExtensionPickerNavigation()
        navigation.openPreset(preset.id)
        navigation.openWorldbook(book.id)
        assertEquals(ExtensionPickerPage.Containers(listOf(preset)), navigation.presets(listOf(preset)))
        assertEquals(ExtensionPickerPage.Detail(book), navigation.worldbooks(listOf(book)))
        assertEquals(ExtensionPickerPage.Containers(emptyList<Lorebook>()), navigation.worldbooks(emptyList()))
        navigation.openPreset(preset.id)
        assertEquals(ExtensionPickerPage.Containers(listOf(book)), navigation.worldbooks(listOf(book)))
        assertEquals(ExtensionPickerPage.Containers(emptyList<PromptPreset>()), navigation.presets(emptyList()))
    }
}
