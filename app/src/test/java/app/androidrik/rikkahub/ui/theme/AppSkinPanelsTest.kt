package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.TopBarChromeStyle
import app.androidrik.rikkahub.data.files.AppearanceArchive
import app.androidrik.rikkahub.data.files.textureDigest
import app.androidrik.rikkahub.data.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppSkinPanelsTest {
    @Test fun `old skins inherit all host defaults and the legacy top bar mode`() {
        val skin = Json.decodeFromString<AppSkin>("{}")
        assertEquals(AppSkin(), skin)
        assertTrue(skin.panels().all { it == AppSkinPanel() })
        val display = DisplaySetting(topBarStyle = TopBarChromeStyle.CAPSULES)
        assertEquals(TopBarChromeStyle.CAPSULES, skin.topBarMode(display))
        assertEquals(TopBarChromeStyle.FULL_BAR, skin.copy(topBar = AppSkinTopBar(TopBarChromeStyle.FULL_BAR)).topBarMode(display))
        assertNull(skin.topBar.panel.borderWidthDp)
    }

    @Test fun `panel normalization bounds geometry and retains explicit zero and empty materials`() {
        val panel = AppSkinPanel(background = ChatBubble(stack = BubbleStack()), radiusDp = -20f,
            borderWidthDp = Float.NaN, contentColorHex = "invalid", borderColorHex = "#123456",
            paddingHorizontalDp = Float.POSITIVE_INFINITY, paddingVerticalDp = 999f, gapDp = -100f).normalized()
        assertEquals(0f, panel.radiusDp!!); assertEquals(0f, panel.borderWidthDp!!)
        assertEquals(12f, panel.paddingHorizontalDp!!); assertEquals(24f, panel.paddingVerticalDp!!); assertEquals(0f, panel.gapDp!!)
        assertNull(panel.contentColorHex); assertEquals("#123456", panel.borderColorHex)
        assertEquals(BubbleStack(), panel.background!!.stack)
        assertEquals(ChatBubbleGeometry(0f, 0f), panel.background!!.geometry)
        assertEquals(panel, panel.normalized())
    }

    @Test fun `legacy top bar writes remain effective without dropping skin geometry or other groups`() {
        val legacy = DisplaySetting().withTopBarMode(TopBarChromeStyle.CAPSULES)
        assertEquals(AppSkinOverrides(), legacy.appSkinOverrides)
        assertEquals(TopBarChromeStyle.CAPSULES, resolveAppSkin(legacy).skin.topBarMode(legacy))
        val owner = "test.legacy.mode"
        val panel = AppSkinPanel(radiusDp = 24f)
        AppSkinRegistry.shared.register(owner, listOf(AppSkinPreset("one", "One", AppSkin(
            topBar = AppSkinTopBar(TopBarChromeStyle.CAPSULES, panel))))).use {
            val current = DisplaySetting(appSkinId = "$owner:one", appSkinOverrides = AppSkinOverrides(cards = AppSkinCards(tool = panel)))
            val updated = current.withTopBarMode(TopBarChromeStyle.FULL_BAR)
            assertEquals(TopBarChromeStyle.FULL_BAR, resolveAppSkin(updated).skin.topBarMode(updated))
            assertEquals(panel, updated.appSkinOverrides.topBar!!.panel)
            assertEquals(current.appSkinOverrides.cards, updated.appSkinOverrides.cards)
        }
    }

    @Test fun `editing one slot preserves others and only overrides its owning group`() {
        val base = AppSkin()
        val panel = AppSkinPanel(radiusDp = 24f, contentColorHex = "#12")
        val raw = base.withPanel(AppSkinPanelSlot.DRAWER_ITEM, panel)
        assertEquals("#12", raw.drawer.item.contentColorHex)
        val valid = raw.withPanel(AppSkinPanelSlot.DRAWER_ITEM, panel.copy(contentColorHex = "#123456"))
        val overrides = AppSkinOverrides().withEdits(base, valid)
        assertNotNull(overrides.drawer); assertNull(overrides.topBar); assertNull(overrides.cards)
        val updatedBase = base.copy(topBar = AppSkinTopBar(TopBarChromeStyle.CAPSULES))
        assertEquals(updatedBase.topBar, overrides.applyTo(updatedBase).topBar)
        AppSkinPanelSlot.entries.forEach { slot ->
            assertEquals(panel, base.withPanel(slot, panel).panel(slot))
        }
    }

    @Test fun `all panel materials and shared texture ids survive settings and theme snapshots`() {
        var skin = AppSkin(topBar = AppSkinTopBar(TopBarChromeStyle.CAPSULES))
        AppSkinPanelSlot.entries.forEachIndexed { index, slot ->
            skin = skin.withPanel(slot, AppSkinPanel(background = ChatBubble(stack = BubbleStack(layers = listOf(
                BubbleLayer(id = "texture", kind = BubbleLayerKind.TEXTURE, texture = BubbleTexture(index.toString().repeat(64))),
                BubbleLayer(id = "shader", kind = BubbleLayerKind.SHADER, shader = BubbleShaderStyle(phase = 0.3f))))),
                radiusDp = 18f, borderWidthDp = 1f, contentColorHex = "#FFFFFF", gapDp = 8f))
        }
        val display = DisplaySetting(appSkinOverrides = AppSkinOverrides.from(skin.normalized()))
        val json = Json { encodeDefaults = true }
        assertEquals(display, json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display)))
        val snapshot = ThemeSnapshot(theme = CustomTheme(id = "test"), appearance = display.toAppearanceSnapshot())
        assertEquals(skin.textureIds(), snapshot.textureIds())
        assertEquals(5, snapshot.textureIds().size)
        val imported = json.decodeFromString<ThemeSnapshot>(json.encodeToString(ThemeSnapshot.serializer(), snapshot))
        assertEquals(skin.normalized(), resolveAppSkin(DisplaySetting().withAppearanceSnapshot(imported.appearance!!)).skin)
    }

    @Test fun `portable archives allow panel textures but retain bounded asset count and total size`() {
        val textures = (0 until AppearanceArchive.MAX_ASSETS).associate { index ->
            val data = "texture-$index".toByteArray(); textureDigest(data) to data
        }
        val decoded = AppearanceArchive.decode(AppearanceArchive("{}", textures).encode())
        assertEquals(32, decoded.textures.size)
        val extra = "one-too-many".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceArchive("{}", textures + (textureDigest(extra) to extra)).encode()
        }
        assertEquals(32 * 1024 * 1024, AppearanceArchive.MAX_TOTAL)
    }

    @Test fun `plugin panel defaults fall back while local card overrides survive disable and restore`() {
        val registry = AppSkinRegistry()
        val panel = AppSkinPanel(background = ChatBubble(colorHex = "#112233"))
        val skin = AppSkin(topBar = AppSkinTopBar(TopBarChromeStyle.CAPSULES, panel), drawer = AppSkinDrawer(panel))
        val display = DisplaySetting(appSkinId = "skin:one", appSkinOverrides = AppSkinOverrides(cards = AppSkinCards(tool = panel)))
        val handle = registry.register("skin", listOf(AppSkinPreset("one", "One", skin)))
        assertEquals(skin.topBar.normalized(), resolveAppSkin(display, registry.skins.value).skin.topBar)
        handle.close()
        val fallback = resolveAppSkin(display, registry.skins.value).skin
        assertEquals(AppSkinTopBar(), fallback.topBar); assertEquals(panel.normalized(), fallback.cards.tool)
        registry.register("skin", listOf(AppSkinPreset("one", "One", skin))).use {
            assertEquals(skin.topBar.normalized(), resolveAppSkin(display, registry.skins.value).skin.topBar)
        }
    }
}
