package app.androidrik.rikkahub.ui.pages.setting.search

import app.androidrik.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingSearchRevealTest {

    @Test
    fun `request and consume returns section once and clears pending state`() {
        val channel = SettingSearchRevealChannel()
        channel.request(Screen.SettingPreferencesGeneral, SettingAdvancedSection.GENERAL_GLASS)

        assertEquals(
            SettingAdvancedSection.GENERAL_GLASS,
            channel.peekRoute(Screen.SettingPreferencesGeneral),
        )
        assertEquals(
            SettingAdvancedSection.GENERAL_GLASS,
            channel.consumeRoute(Screen.SettingPreferencesGeneral),
        )
        assertNull(channel.consumeRoute(Screen.SettingPreferencesGeneral))
        assertNull(channel.peekRoute(Screen.SettingPreferencesGeneral))
    }

    @Test
    fun `consume for another route does not clear pending target`() {
        val channel = SettingSearchRevealChannel()
        channel.request(Screen.SettingPreferencesUI, SettingAdvancedSection.UI_CODE_DISPLAY)

        assertNull(channel.consumeRoute(Screen.SettingModels))
        assertEquals(
            SettingAdvancedSection.UI_CODE_DISPLAY,
            channel.consumeRoute(Screen.SettingPreferencesUI),
        )
    }

    @Test
    fun `request with null or blank clears prior pending target`() {
        val channel = SettingSearchRevealChannel()
        channel.request(Screen.SettingModels, SettingAdvancedSection.MODEL_OCR_BACKEND)
        channel.request(Screen.SettingModels, null)
        assertNull(channel.consumeRoute(Screen.SettingModels))

        channel.request(Screen.SettingModels, SettingAdvancedSection.MODEL_CONTEXT_LENGTH_OVERRIDE)
        channel.request(Screen.SettingModels, "   ")
        assertNull(channel.consumeRoute(Screen.SettingModels))
    }

    @Test
    fun `search index entries have unique ids and valid advanced section mappings`() {
        val entries = SettingSearchIndex.entries
        assertEquals(
            "Duplicate entry IDs in SettingSearchIndex",
            entries.size,
            entries.map { it.id }.toSet().size,
        )

        val expectedRouteBySection = mapOf(
            SettingAdvancedSection.GENERAL_GLASS to Screen.SettingPreferencesGeneral,
            SettingAdvancedSection.UI_CODE_DISPLAY to Screen.SettingPreferencesUI,
            SettingAdvancedSection.MODEL_CONTEXT_LENGTH_OVERRIDE to Screen.SettingModels,
            SettingAdvancedSection.MODEL_OCR_BACKEND to Screen.SettingModels,
        )

        val tagged = entries.filter { it.advancedSection != null }
        assertTrue(tagged.isNotEmpty())
        tagged.forEach { entry ->
            val expectedRoute = expectedRouteBySection[entry.advancedSection]
            assertEquals(
                "Unexpected route for advancedSection=${entry.advancedSection}",
                expectedRoute,
                entry.route,
            )
        }
        assertEquals(
            expectedRouteBySection.keys,
            tagged.mapNotNull { it.advancedSection }.toSet(),
        )
    }
}
