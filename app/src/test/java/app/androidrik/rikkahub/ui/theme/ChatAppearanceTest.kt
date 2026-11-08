package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatAppearanceDetails
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.ChatBubbleGeometry
import app.androidrik.rikkahub.data.model.ChatLayout
import app.androidrik.rikkahub.data.model.ChatTypography
import app.androidrik.rikkahub.data.model.toChatAppearance
import app.androidrik.rikkahub.data.model.withChatAppearance
import kotlinx.serialization.json.Json
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginState
import app.androidrik.plugin.manager.PluginStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatAppearanceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val custom = ChatAppearance(
        typography = ChatTypography(ChatFontFamily.SERIF, sizeRatio = 1.3f),
        layout = ChatLayout(userAlignEnd = false, messageGapDp = 12f),
        userBubble = ChatBubble(visible = false, opacity = 0.4f, geometry = ChatBubbleGeometry(4f, 12f)),
        assistantBubble = ChatBubble(visible = true, opacity = 0.8f, colorHex = "#123456"),
    )

    @Test fun `old display JSON retains the old chat defaults`() {
        val old = json.decodeFromString<DisplaySetting>("""{"bubbleOpacity":0.6,"showAssistantBubble":true,"chatFontFamily":"serif"}""")
        val appearance = old.toChatAppearance()
        assertEquals(0.6f, appearance.userBubble.opacity)
        assertEquals(0.6f, appearance.assistantBubble.opacity)
        assertTrue(appearance.assistantBubble.visible)
        assertEquals(ChatFontFamily.SERIF, appearance.typography.family)
        assertEquals(ChatLayout(), appearance.layout)
        assertEquals(ChatBubbleGeometry(), appearance.userBubble.geometry)
    }

    @Test fun `shared write entry persists both roles and preserves unrelated settings`() {
        val display = DisplaySetting(userNickname = "keep", enableGlassTheme = true, chatAppearancePresetId = "plugin:preset")
            .withChatAppearance(custom)
        val restored = json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display))
        assertEquals(custom, restored.toChatAppearance())
        assertEquals("keep", restored.userNickname)
        assertTrue(restored.enableGlassTheme)
        assertNull(restored.chatAppearancePresetId)
    }

    @Test fun `plugin disable falls back without overwriting user settings and reenable restores selection`() {
        val registry = ChatAppearanceRegistry()
        val preset = ChatAppearancePreset("compact", "Compact", custom)
        val display = DisplaySetting(chatAppearancePresetId = "example:compact", fontSizeRatio = 1.1f)
        val handle = registry.register("example", listOf(preset))
        assertEquals(custom, resolveChatAppearance(display, registry.presets.value))
        handle.close()
        assertEquals(display.toChatAppearance(), resolveChatAppearance(display, registry.presets.value))
        registry.register("example", listOf(preset)).use {
            assertEquals(custom, resolveChatAppearance(display, registry.presets.value))
        }
    }

    @Test fun `plugin manager activation and removal own the appearance contribution`() = runTest {
        val registry = ChatAppearanceRegistry()
        val manager = PluginManager(this, object : PluginStateStore {
            override suspend fun read(): Map<String, PluginState> = emptyMap()
            override suspend fun write(states: Map<String, PluginState>) {}
        })
        installPluginChatAppearanceBridge(manager, registry)
        val handle = manager.register(BuiltinChatAppearancePlugin)
        assertTrue(registry.presets.value.isEmpty())
        manager.setEnabled(BuiltinChatAppearancePlugin.id, true)
        assertEquals(2, registry.presets.value.size)
        val display = DisplaySetting(chatAppearancePresetId = "builtin.chat_appearance:reading")
        assertFalse(resolveChatAppearance(display, registry.presets.value).userBubble.visible)
        manager.setEnabled(BuiltinChatAppearancePlugin.id, false)
        assertTrue(resolveChatAppearance(display, registry.presets.value).userBubble.visible)
        assertTrue(registry.presets.value.isEmpty())
        manager.setEnabled(BuiltinChatAppearancePlugin.id, true)
        assertEquals(2, registry.presets.value.size)
        handle.unregister()
        assertTrue(registry.presets.value.isEmpty())
    }

    @Test fun `stale handle cannot unregister replacement or another plugin`() {
        val registry = ChatAppearanceRegistry()
        val stale = registry.register("one", listOf(ChatAppearancePreset("p", "Old", custom)))
        val replacement = registry.register("one", listOf(ChatAppearancePreset("p", "New", ChatAppearance())))
        registry.register("two", listOf(ChatAppearancePreset("p", "Other", custom))).use {
            stale.close()
            assertEquals(listOf("one:p", "two:p"), registry.presets.value.map { it.id })
            replacement.close()
            assertEquals(listOf("two:p"), registry.presets.value.map { it.id })
        }
        assertTrue(registry.presets.value.isEmpty())
    }

    @Test fun `invalid numeric tokens fall back or clamp before reaching Compose`() {
        val broken = custom.copy(
            typography = custom.typography.copy(sizeRatio = Float.NaN),
            layout = ChatLayout(messageGapDp = Float.POSITIVE_INFINITY),
            userBubble = ChatBubble(opacity = -1f, colorHex = "invalid", geometry = ChatBubbleGeometry(-5f, 500f)),
        ).normalized()
        assertEquals(1f, broken.typography.sizeRatio)
        assertEquals(4f, broken.layout.messageGapDp)
        assertEquals(0f, broken.userBubble.opacity)
        assertNull(broken.userBubble.colorHex)
        assertEquals(ChatBubbleGeometry(0f, 32f), broken.userBubble.geometry)
    }

    @Test fun `new snapshots preserve extra tokens and old snapshots leave them untouched`() {
        val display = DisplaySetting().withChatAppearance(custom)
        val snapshot = display.toAppearanceSnapshot()
        assertEquals(custom, DisplaySetting().withAppearanceSnapshot(snapshot).toChatAppearance())
        val old = json.decodeFromString<ThemeAppearanceSnapshot>("""{"bubbleOpacity":0.7}""")
        assertEquals(display.chatAppearanceDetails, display.withAppearanceSnapshot(old).chatAppearanceDetails)
        assertEquals(ChatAppearanceDetails(), DisplaySetting().withAppearanceSnapshot(old).chatAppearanceDetails)
    }

    @Test fun `snapshot exports resolved plugin values without serializing plugin selection or font paths`() {
        val registry = ChatAppearanceRegistry.shared
        val preset = ChatAppearancePreset("portable", "Portable", custom.copy(typography = ChatTypography(ChatFontFamily.CUSTOM, "fonts/local.ttf", "Local")))
        registry.register("test.snapshot", listOf(preset)).use {
            val snapshot = DisplaySetting(chatAppearancePresetId = "test.snapshot:portable").toAppearanceSnapshot()
            val encoded = json.encodeToString(ThemeAppearanceSnapshot.serializer(), snapshot)
            assertFalse(encoded.contains("local.ttf"))
            assertFalse(encoded.contains("test.snapshot"))
            assertEquals(custom.layout, snapshot.chatAppearanceDetails!!.layout)
        }
    }
}
