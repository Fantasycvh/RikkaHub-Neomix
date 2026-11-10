package app.androidrik.rikkahub.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.webview.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppSkinEmbeddedTest {
    @Test fun oldSkinsAndSettingsKeepEmbeddedDefaults() {
        assertEquals(AppSkinEmbedded(), Json.decodeFromString<AppSkin>("{}").embedded)
        assertEquals(AppSkinEmbedded(), resolveAppSkin(DisplaySetting()).skin.embedded)
        val palette = lightColorScheme(surface = Color.Red)
        assertEquals(Color.Black.toArgb(), AppSkinTerminal().resolveColors(palette).background)
        assertEquals(Color.Red.toArgb(), AppSkinTerminal(followAppColors = true).resolveColors(palette).background)
        assertEquals(Color.Black.toArgb(), AppSkinTerminal(backgroundHex = "#ffffff").resolveColors(palette).foreground)
        assertEquals(Color.White.toArgb(), AppSkinTerminal(backgroundHex = "#111111").resolveColors(palette).foreground)
    }

    @Test fun editorOverridesRegistryFallbackAndSharingCarryTheSameEmbeddedGroup() {
        val embedded = AppSkinEmbedded(terminal = AppSkinTerminal(backgroundHex = "#123456"),
            canvas = AppSkinCanvas(newPaperHex = "#654321"))
        val original = AppSkin()
        val edited = original.copy(embedded = embedded)
        val overrides = AppSkinOverrides().withEdits(original, edited)
        assertEquals(embedded, overrides.embedded); assertNull(overrides.shapes)
        val display = DisplaySetting(appSkinId = "missing:plugin", appSkinOverrides = overrides)
        assertEquals(embedded, resolveAppSkin(display, emptyList(), emptyList()).skin.embedded)
        val snapshot = display.toAppearanceSnapshot()
        assertEquals(embedded, snapshot.appSkin!!.embedded)
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<ThemeAppearanceSnapshot>(json.encodeToString(ThemeAppearanceSnapshot.serializer(), snapshot))
        assertEquals(embedded, resolveAppSkin(DisplaySetting().withAppearanceSnapshot(decoded)).skin.embedded)
    }

    @Test fun invalidColorsAndFontSizeAreBoundedBeforeReachingRenderers() {
        val skin = AppSkinEmbedded(web = AppSkinWeb(backgroundHex = "red;script", foregroundHex = "#123456"),
            terminal = AppSkinTerminal(cursorHex = "#abcd", fontSizeSp = Float.NaN),
            canvas = AppSkinCanvas(newPaperHex = "#11223344")).normalized()
        assertNull(skin.web.backgroundHex); assertEquals("#123456", skin.web.foregroundHex)
        assertNull(skin.terminal.cursorHex); assertEquals(12f, skin.terminal.fontSizeSp)
        assertNull(skin.canvas.newPaperHex)
        assertEquals(24f, AppSkinTerminal(fontSizeSp = 99f).normalized().fontSizeSp)
        assertEquals(8f, AppSkinTerminal(fontSizeSp = -99f).normalized().fontSizeSp)
    }

    @Test fun webThemeOnlyAppliesToTheExactOptedInHtmlDocument() {
        val html = WebContent.Data("<p>content</p>", baseUrl = "https://rikkahub.local/", mimeType = "text/html", appOwned = true)
        assertTrue(html.allowsSkinAt("https://rikkahub.local/#anchor"))
        assertFalse(html.allowsSkinAt("https://rikkahub.local/other"))
        assertFalse(html.allowsSkinAt("https://example.com/"))
        assertFalse(html.copy(appOwned = false).allowsSkinAt(html.baseUrl))
        assertFalse(html.copy(mimeType = "image/svg+xml").allowsSkinAt(html.baseUrl))
        assertFalse(WebContent.Url("https://example.com/").allowsSkinAt("https://example.com/"))
        assertTrue(html.copy(baseUrl = "https://rikkahub.local").allowsSkinAt("https://rikkahub.local/"))
        assertTrue(html.copy(baseUrl = "https://rikkahub.local?preview=1").allowsSkinAt("https://rikkahub.local/?preview=1"))
        assertFalse(html.allowsSkinAt("https://rikkahub.local/skin-test/"))
    }
}
