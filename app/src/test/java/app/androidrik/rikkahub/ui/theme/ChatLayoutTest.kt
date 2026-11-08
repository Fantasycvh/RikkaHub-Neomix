package app.androidrik.rikkahub.ui.theme

import androidx.compose.runtime.Composable
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginState
import app.androidrik.plugin.manager.PluginStateStore
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatLayoutTest {
    private fun renderer(localId: String, label: String = localId) = object : ChatLayoutRenderer {
        override val id = localId
        override val name = label
        @Composable override fun Render(context: ChatLayoutContext, slots: ChatLayoutSlots) {}
    }

    @Test fun `layout tokens roundtrip through settings and portable snapshots`() {
        val layout = ChatLayout(false, 12f, "sample:columns", false, 0.7f, 20f, 8f)
        val display = DisplaySetting().withChatAppearance(ChatAppearance(layout = layout))
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display))
        assertEquals(layout, restored.toChatAppearance().layout)
        assertEquals(layout, DisplaySetting().withAppearanceSnapshot(display.toAppearanceSnapshot()).toChatAppearance().layout)
        assertEquals(ChatLayout(), Json.decodeFromString<DisplaySetting>("{}").toChatAppearance().layout)
    }

    @Test fun `invalid layout tokens cannot produce invalid constraints`() {
        val layout = ChatAppearance(layout = ChatLayout(rendererId = "", contentWidthFraction = Float.NaN,
            listSpacingDp = -1f, listPaddingDp = Float.POSITIVE_INFINITY)).normalized().layout
        assertEquals("builtin.standard", layout.rendererId)
        assertEquals(1f, layout.contentWidthFraction)
        assertEquals(0f, layout.listSpacingDp)
        assertEquals(16f, layout.listPaddingDp)
        assertEquals(0.35f, ChatAppearance(layout = layout.copy(contentWidthFraction = -20f)).normalized().layout.contentWidthFraction)
    }

    @Test fun `missing and removed plugin layouts resolve to standard without changing stored selection`() {
        val registry = ChatLayoutRegistry()
        val selected = renderer("columns")
        val layoutId = "plugin:columns"
        val standard = resolveChatLayout("builtin.standard", registry.layouts.value)
        val handle = registry.register("plugin", listOf(selected))
        assertSame(selected, resolveChatLayout(layoutId, registry.layouts.value))
        handle.close()
        assertSame(standard, resolveChatLayout(layoutId, registry.layouts.value))
        registry.register("plugin", listOf(selected)).use {
            assertSame(selected, resolveChatLayout(layoutId, registry.layouts.value))
        }
        assertEquals(builtInChatLayouts, registry.layouts.value)
    }

    @Test fun `same local ids are namespaced and stale handles cannot remove replacements`() {
        val registry = ChatLayoutRegistry()
        val old = registry.register("one", listOf(renderer("same", "old")))
        val replacement = renderer("same", "replacement")
        val current = registry.register("one", listOf(replacement))
        registry.register("two", listOf(renderer("same", "other"))).use {
            old.close()
            assertSame(replacement, resolveChatLayout("one:same", registry.layouts.value))
            current.close()
            assertTrue(registry.layouts.value.any { it.id == "two:same" })
        }
        assertEquals(builtInChatLayouts, registry.layouts.value)
    }

    @Test fun `duplicate local ids are rejected atomically`() {
        val registry = ChatLayoutRegistry()
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("invalid", listOf(renderer("same"), renderer("same")))
        }
        assertEquals(builtInChatLayouts, registry.layouts.value)
    }

    @Test fun `plugin lifecycle owns both presets and executable layouts`() = runTest {
        val appearances = ChatAppearanceRegistry()
        val layouts = ChatLayoutRegistry()
        val manager = PluginManager(this, object : PluginStateStore {
            override suspend fun read(): Map<String, PluginState> = emptyMap()
            override suspend fun write(states: Map<String, PluginState>) {}
        })
        installPluginChatAppearanceBridge(manager, appearances, layouts)
        val handle = manager.register(BuiltinChatAppearancePlugin)
        assertEquals(builtInChatLayouts, layouts.layouts.value)
        manager.setEnabled(BuiltinChatAppearancePlugin.id, true)
        val preset = appearances.presets.value.first { it.id.endsWith(":centered") }
        assertEquals("centered", resolveChatLayout(preset.appearance.layout.rendererId, layouts.layouts.value).id)
        manager.setEnabled(BuiltinChatAppearancePlugin.id, false)
        assertTrue(appearances.presets.value.isEmpty())
        assertEquals(builtInChatLayouts, layouts.layouts.value)
        manager.setEnabled(BuiltinChatAppearancePlugin.id, true)
        handle.unregister()
        assertTrue(appearances.presets.value.isEmpty())
        assertEquals(builtInChatLayouts, layouts.layouts.value)
    }
}
