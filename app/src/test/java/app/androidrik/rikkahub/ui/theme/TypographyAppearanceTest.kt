package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.haze.BubbleGlassBackend
import app.androidrik.rikkahub.ui.components.haze.bubbleGlassBackend
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TypographyAppearanceTest {
    @Test fun `text gradients survive settings and theme sharing without local font paths`() {
        val gradient = AppearanceGradient(listOf("#224466", "#66AABB"), GradientDirection.HORIZONTAL)
        val display = DisplaySetting().withChatAppearance(ChatAppearance(typography = ChatTypography(
            customFontPath = "fonts/device.ttf", gradient = gradient)))
        val json = Json { encodeDefaults = true }
        val saved = json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display))
        assertEquals(gradient, saved.toChatAppearance().typography.gradient)
        val snapshot = json.encodeToString(ThemeAppearanceSnapshot.serializer(), display.toAppearanceSnapshot())
        assertFalse(snapshot.contains("device.ttf"))
        assertEquals(gradient, DisplaySetting().withAppearanceSnapshot(json.decodeFromString(snapshot)).toChatAppearance().typography.gradient)
    }

    @Test fun `old settings keep solid text and disabling gradient removes it`() {
        assertNull(Json.decodeFromString<DisplaySetting>("{}").toChatAppearance().typography.gradient)
        val display = DisplaySetting().withChatAppearance(ChatAppearance(typography = ChatTypography(gradient = AppearanceGradient())))
        assertNotNull(display.toChatAppearance().typography.gradient)
        assertNull(display.withChatAppearance(display.toChatAppearance().copy(typography = ChatTypography())).toChatAppearance().typography.gradient)
    }

    @Test fun `glass follows sdk and backdrop availability with explicit fallbacks`() {
        assertEquals(BubbleGlassBackend.STATIC, bubbleGlassBackend(30, true))
        assertEquals(BubbleGlassBackend.BLUR, bubbleGlassBackend(31, true))
        assertEquals(BubbleGlassBackend.BLUR, bubbleGlassBackend(32, true))
        assertEquals(BubbleGlassBackend.GLASS, bubbleGlassBackend(33, true))
        assertEquals(BubbleGlassBackend.GLASS, bubbleGlassBackend(36, true))
        (26..37).forEach { sdk -> assertEquals(BubbleGlassBackend.STATIC, bubbleGlassBackend(sdk, false)) }
    }
}
