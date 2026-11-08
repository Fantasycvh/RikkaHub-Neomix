package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BubbleMaterialsTest {
    @Test fun `all implemented materials survive settings and theme snapshot roundtrips`() {
        val json = Json { encodeDefaults = true }
        BubbleMaterial.entries.forEach { material ->
            val style = ChatBubble(material = material, colorHex = "#112233", opacity = 0.7f,
                gradient = AppearanceGradient(listOf("#112233", "#778899", "#FFEEDD"), GradientDirection.VERTICAL),
                pattern = BubblePattern(BubblePatternKind.STRIPES, 23f, "#AABBCC", 0.4f),
                noise = BubbleNoise(42, 0.2f))
            val display = DisplaySetting().withChatAppearance(ChatAppearance(userBubble = style, assistantBubble = style))
            val restored = json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display))
            assertEquals(style, restored.toChatAppearance().userBubble)
            assertEquals(style, restored.toChatAppearance().assistantBubble)
            val snapshot = json.decodeFromString<ThemeAppearanceSnapshot>(
                json.encodeToString(ThemeAppearanceSnapshot.serializer(), display.toAppearanceSnapshot()))
            assertEquals(style, DisplaySetting().withAppearanceSnapshot(snapshot).toChatAppearance().userBubble)
        }
    }

    @Test fun `invalid material parameters are bounded and noise seed remains stable`() {
        val value = ChatBubble(
            pattern = BubblePattern(spacingDp = Float.NaN, opacity = 20f, colorHex = "bad"),
            noise = BubbleNoise(-123, Float.POSITIVE_INFINITY),
        ).normalized()
        assertEquals(16f, value.pattern.spacingDp)
        assertEquals(1f, value.pattern.opacity)
        assertNull(value.pattern.colorHex)
        assertEquals(BubbleNoise(-123, 0.12f), value.noise)
    }

    @Test fun `gradients reject malformed colors and cap resource use without changing stop order`() {
        assertEquals(AppearanceGradient().colors, AppearanceGradient(listOf("bad", "#123456")).normalized().colors)
        val stops = List(12) { "#${it.toString(16).padStart(6, '0')}" }
        assertEquals(stops.take(8), AppearanceGradient(stops).normalized().colors)
        assertEquals(listOf("#112233", "#445566"), AppearanceGradient(listOf("#112233", "bad", "#445566")).normalized().colors)
    }

    @Test fun `hiding a material preserves its parameters for reenable`() {
        val bubble = ChatBubble(material = BubbleMaterial.GRADIENT, visible = false)
        val display = DisplaySetting().withChatAppearance(ChatAppearance(userBubble = bubble))
        assertEquals(bubble, display.toChatAppearance().userBubble)
        assertEquals(bubble.copy(visible = true), display.toChatAppearance().userBubble.copy(visible = true))
    }
}
