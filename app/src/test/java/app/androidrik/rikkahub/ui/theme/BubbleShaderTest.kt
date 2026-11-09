package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.files.AppearanceArchive
import app.androidrik.rikkahub.data.files.BubbleMaterialDocument
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.BubbleShaderField
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BubbleShaderTest {
    @Test fun `shader params survive settings snapshots and portable material documents without assets`() {
        val json = Json { encodeDefaults = true }
        val style = BubbleShaderStyle(BubbleShaderTemplate.MARBLE, listOf("#123456", "#789ABC", "#DEF012"),
            scaleDp = 192f, angleDegrees = 47f, contrast = 1.3f, warp = 1.2f, phase = 0.4f)
        val bubble = ChatBubble(stack = BubbleStack(layers = listOf(BubbleLayer(id = "shader",
            kind = BubbleLayerKind.SHADER, opacity = 0.65f, blend = BubbleBlend.SCREEN, shader = style))))
        val display = DisplaySetting().withChatAppearance(ChatAppearance(userBubble = bubble))
        val saved = json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display))
        assertEquals(bubble, saved.toChatAppearance().userBubble)
        val snapshot = ThemeSnapshot(theme = CustomTheme(name = "Shader"), appearance = display.toAppearanceSnapshot())
        val imported = json.decodeFromString<ThemeSnapshot>(json.encodeToString(ThemeSnapshot.serializer(), snapshot))
        assertEquals(bubble, DisplaySetting().withAppearanceSnapshot(imported.appearance!!).toChatAppearance().userBubble)
        val material = BubbleMaterialDocument(bubble = bubble)
        val archive = AppearanceArchive(json.encodeToString(BubbleMaterialDocument.serializer(), material), emptyMap())
        assertEquals(material, json.decodeFromString<BubbleMaterialDocument>(AppearanceArchive.decode(archive.encode()).manifest))
        assertTrue(bubble.textureIds().isEmpty())
        // Pre-shader layers keep their defaults when read by the extended contract.
        assertEquals(BubbleShaderStyle(), json.decodeFromString<BubbleLayer>("""{"id":"old","kind":"SOLID"}""").shader)
    }

    @Test fun `untrusted shader params normalize to a fixed bounded contract`() {
        val style = BubbleShaderStyle(colors = listOf("#ABCDEF", "bad") + List(30) { "#112233" },
            scaleDp = Float.NaN, angleDegrees = 999f, contrast = Float.POSITIVE_INFINITY, warp = -2f, phase = 9f).normalized()
        assertEquals(listOf("#ABCDEF", "#8974C7", "#112233"), style.colors)
        assertEquals(128f, style.scaleDp); assertEquals(360f, style.angleDegrees)
        assertEquals(1f, style.contrast); assertEquals(0f, style.warp); assertEquals(1f, style.phase)
        assertEquals(style, style.normalized())
        assertEquals(3, BubbleShaderStyle(colors = emptyList()).normalized().colors.size)
    }

    @Test fun `procedural fields are periodic finite varied and deterministic for all templates`() {
        val signatures = mutableSetOf<List<Int>>()
        BubbleShaderTemplate.entries.forEach { template ->
            val style = BubbleShaderStyle(template = template, phase = 0.37f)
            val field = BubbleShaderField(style)
            val samples = List(36) { index ->
                val x = index % 6 / 6f + 0.03f; val y = index / 6 / 6f + 0.07f
                val color = field.color(x, y)
                assertEquals(255, color ushr 24)
                assertChannelsClose(color, field.color(x + 1f, y - 2f))
                assertEquals(color, BubbleShaderField(style).color(x, y))
                color
            }
            assertTrue("$template was flat", samples.toSet().size > 12)
            signatures.add(samples)
            assertNotEquals(samples, List(36) { i -> BubbleShaderField(style.copy(phase = 0.73f)).color(i % 6 / 6f + 0.03f, i / 6 / 6f + 0.07f) })
        }
        assertEquals(4, signatures.size)
    }

    @Test fun `zero contrast yields palette center and uniform palettes remain solid`() {
        BubbleShaderTemplate.entries.forEach { template ->
            assertEquals(0xFF8974C7.toInt(), BubbleShaderField(BubbleShaderStyle(template = template, contrast = 0f)).color(0.2f, 0.7f))
            val field = BubbleShaderField(BubbleShaderStyle(template = template, colors = List(3) { "#3579AC" }))
            assertEquals(0xFF3579AC.toInt(), field.color(0f, 0f))
            assertEquals(0xFF3579AC.toInt(), field.color(0.62f, -0.33f))
        }
    }

    private fun assertChannelsClose(a: Int, b: Int) {
        listOf(0, 8, 16).forEach { shift -> assertEquals((a shr shift and 255).toFloat(), (b shr shift and 255).toFloat(), 1f) }
    }
}
