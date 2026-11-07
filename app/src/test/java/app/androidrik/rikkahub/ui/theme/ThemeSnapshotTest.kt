package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.model.CustomTheme
import app.androidrik.rikkahub.data.model.ThemeModeRoleOverrides
import app.androidrik.rikkahub.data.model.ThemePaletteStyle
import app.androidrik.rikkahub.data.model.ThemeRoleOverrides

import app.androidrik.rikkahub.data.datastore.BackgroundEffectType
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ThemeSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `snapshot roundtrip preserves theme and appearance`() {
        val snapshot = ThemeSnapshot(
            theme = CustomTheme(
                name = "我的主题",
                primaryColorArgb = 0xFF2E7D32,
                paletteStyle = ThemePaletteStyle.VIBRANT,
                contrastLevel = 0.5,
            ),
            appearance = ThemeAppearanceSnapshot(
                enableBlurEffect = true,
                inputEffectType = BackgroundEffectType.GLASS,
                chatFontFamily = ChatFontFamily.SERIF,
                bubbleOpacity = 0.8f,
                themeRoleOverrides = ThemeRoleOverrides(
                    enabled = true,
                    dark = ThemeModeRoleOverrides(backgroundHex = "#121212", userBubbleHex = "#334455"),
                ),
            ),
        )
        val decoded = json.decodeFromString<ThemeSnapshot>(json.encodeToString(ThemeSnapshot.serializer(), snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `legacy bare CustomTheme json still decodes as bare theme`() {
        val legacy = """{"id":"t1","name":"old","primaryColorArgb":7303014,"secondaryColorArgb":null,"tertiaryColorArgb":null}"""
        val theme = json.decodeFromString<CustomTheme>(legacy)
        assertEquals("old", theme.name)
        assertNull(theme.paletteStyle)
        assertNull(theme.contrastLevel)
        // 旧 JSON 缺 theme 键 → 快照解码必须失败（否则静默变成默认主题），由导入端回退裸主题
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ThemeSnapshot>(legacy)
        }
    }

    @Test
    fun `old theme json with no new fields gets default engine config`() {
        val theme = json.decodeFromString<CustomTheme>("""{"id":"t1","name":"x","primaryColorArgb":6750204}""")
        assertEquals(ThemePaletteStyle.TONAL_SPOT, theme.paletteStyle ?: ThemePaletteStyle.TONAL_SPOT)
        assertEquals(0.0, CustomTheme.normalizeContrastLevel(theme.contrastLevel), 0.0)
    }

    @Test
    fun `withAppearanceSnapshot merges only carried fields`() {
        val current = DisplaySetting(
            userNickname = "tester",           // 不在快照域内
            showUserAvatar = false,            // 不在快照域内
            fontSizeRatio = 1.2f,              // 在快照域内，应被覆盖
        )
        val snapshot = ThemeAppearanceSnapshot(
            enableBlurEffect = true,
            inputEffectType = BackgroundEffectType.GLASS,
            chatFontFamily = ChatFontFamily.SERIF,
            showAssistantBubble = true,
            bubbleOpacity = 0.6f,
            fontSizeRatio = 0.9f,
            themeRoleOverrides = ThemeRoleOverrides(enabled = true),
        )
        val merged = current.withAppearanceSnapshot(snapshot)
        // 携带域全部生效
        assertEquals(true, merged.enableBlurEffect)
        assertEquals(BackgroundEffectType.GLASS, merged.inputEffectType)
        assertEquals(ChatFontFamily.SERIF, merged.chatFontFamily)
        assertEquals(true, merged.showAssistantBubble)
        assertEquals(0.6f, merged.bubbleOpacity)
        assertEquals(0.9f, merged.fontSizeRatio)
        assertEquals(true, merged.themeRoleOverrides.enabled)
        // 非携带域保持原状
        assertEquals("tester", merged.userNickname)
        assertEquals(false, merged.showUserAvatar)
    }

    @Test
    fun `toAppearanceSnapshot mirrors display setting`() {
        val display = DisplaySetting(
            enableTopBarBlur = true,
            topBarEffectType = BackgroundEffectType.GLASS,
            chatFontFamily = ChatFontFamily.MONOSPACE,
        )
        val snapshot = display.toAppearanceSnapshot()
        assertEquals(true, snapshot.enableTopBarBlur)
        assertEquals(BackgroundEffectType.GLASS, snapshot.topBarEffectType)
        assertEquals(ChatFontFamily.MONOSPACE, snapshot.chatFontFamily)
        assertEquals(display.themeRoleOverrides, snapshot.themeRoleOverrides)
        // 回路：应用回 DisplaySetting 后快照域逐字段相等
        assertEquals(display, DisplaySetting().withAppearanceSnapshot(snapshot))
    }
}
