package app.androidrik.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import app.androidrik.rikkahub.data.datastore.DisplaySetting

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesStoreTest {

    @Test
    fun `sanitize search service selected - empty services returns 0 without crash`() {
        // 回归：删光搜索服务后 size-1 = -1，coerceIn(0, -1) 曾抛 IllegalArgumentException
        assertEquals(0, SettingsStore.sanitizeSearchServiceSelected(0, 0))
        assertEquals(0, SettingsStore.sanitizeSearchServiceSelected(5, 0))
    }

    @Test
    fun `sanitize search service selected - clamps negative index to 0`() {
        assertEquals(0, SettingsStore.sanitizeSearchServiceSelected(-1, 3))
        assertEquals(0, SettingsStore.sanitizeSearchServiceSelected(-100, 3))
    }

    @Test
    fun `sanitize search service selected - clamps oversized index to last`() {
        assertEquals(2, SettingsStore.sanitizeSearchServiceSelected(99, 3))
        assertEquals(2, SettingsStore.sanitizeSearchServiceSelected(2, 3))
    }

    @Test
    fun `sanitize search service selected - keeps valid index unchanged`() {
        assertEquals(1, SettingsStore.sanitizeSearchServiceSelected(1, 3))
        assertEquals(0, SettingsStore.sanitizeSearchServiceSelected(0, 1))
    }

    @Test
    fun `display setting glass theme defaults to false`() {
        val setting = DisplaySetting()
        org.junit.Assert.assertFalse(setting.enableGlassTheme)
        org.junit.Assert.assertFalse(setting.enableBlurEffect)
    }

    @Test
    fun `display setting old json without glass theme deserializes with default`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            DisplaySetting.serializer(),
            """{"enableBlurEffect":true}"""
        )
        org.junit.Assert.assertTrue(decoded.enableBlurEffect)
        org.junit.Assert.assertFalse(decoded.enableGlassTheme)
    }
}
