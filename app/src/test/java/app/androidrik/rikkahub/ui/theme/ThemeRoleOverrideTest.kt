package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.model.CustomTheme
import app.androidrik.rikkahub.data.model.ThemeModeRoleOverrides
import app.androidrik.rikkahub.data.model.ThemePaletteStyle
import app.androidrik.rikkahub.data.model.toMcuVariant
import app.androidrik.rikkahub.data.model.ThemeRoleOverrides

import androidx.compose.ui.graphics.Color
import dynamiccolor.Variant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThemeRoleOverrideTest {
    private val base = CustomTheme(primaryColorArgb = 0xFF6750A4).generateColorScheme(dark = false)
    private val baseDark = CustomTheme(primaryColorArgb = 0xFF6750A4).generateColorScheme(dark = true)

    @Test
    fun `null overrides keep scheme untouched`() {
        val o = ThemeModeRoleOverrides()
        assertEquals(base, base.withRoleOverrides(o, dark = false))
        // 全空覆写对象同理
        assertEquals(base, base.withRoleOverrides(ThemeRoleOverrides().forMode(darkMode = true), dark = true))
    }

    @Test
    fun `background overrides background and surface only`() {
        val s = base.withRoleOverrides(ThemeModeRoleOverrides(backgroundHex = "#112233"), dark = false)
        assertEquals(Color(0xFF112233), s.background)
        assertEquals(Color(0xFF112233), s.surface)
        assertEquals(base.onBackground, s.onBackground)
        assertEquals(base.primary, s.primary)
        assertEquals(base.surfaceContainer, s.surfaceContainer)
    }

    @Test
    fun `primary text overrides onBackground and onSurface`() {
        val s = base.withRoleOverrides(ThemeModeRoleOverrides(primaryTextHex = "#FEDCBA"), dark = false)
        assertEquals(Color(0xFFFEDCBA), s.onBackground)
        assertEquals(Color(0xFFFEDCBA), s.onSurface)
        assertEquals(base.onSurfaceVariant, s.onSurfaceVariant)
    }

    @Test
    fun `secondary text overrides onSurfaceVariant only`() {
        val s = base.withRoleOverrides(ThemeModeRoleOverrides(secondaryTextHex = "#AAAAAA"), dark = false)
        assertEquals(Color(0xFFAAAAAA), s.onSurfaceVariant)
        assertEquals(base.onBackground, s.onBackground)
    }

    @Test
    fun `control accent regenerates whole accent family as new seed`() {
        val accent = 0xFF2E7D32.toInt()
        val expected = CustomTheme(primaryColorArgb = accent.toLong() and 0xFFFFFFFFL)
            .generateColorScheme(dark = false)
        val s = base.withRoleOverrides(ThemeModeRoleOverrides(controlAccentHex = "#2E7D32"), dark = false)
        // accent 族 12 角色与新 seed 生成的方案一致（容器/on 色自动配套）
        assertEquals(expected.primary, s.primary)
        assertEquals(expected.onPrimary, s.onPrimary)
        assertEquals(expected.primaryContainer, s.primaryContainer)
        assertEquals(expected.onPrimaryContainer, s.onPrimaryContainer)
        assertEquals(expected.secondary, s.secondary)
        assertEquals(expected.onSecondary, s.onSecondary)
        assertEquals(expected.secondaryContainer, s.secondaryContainer)
        assertEquals(expected.onSecondaryContainer, s.onSecondaryContainer)
        assertEquals(expected.tertiary, s.tertiary)
        assertEquals(expected.onTertiary, s.onTertiary)
        assertEquals(expected.tertiaryContainer, s.tertiaryContainer)
        assertEquals(expected.onTertiaryContainer, s.onTertiaryContainer)
        // 非 accent 角色不动
        assertEquals(base.background, s.background)
        assertEquals(base.surface, s.surface)
    }

    @Test
    fun `accent family respects dark mode`() {
        val s = baseDark.withRoleOverrides(ThemeModeRoleOverrides(controlAccentHex = "#2E7D32"), dark = true)
        val expected = CustomTheme(primaryColorArgb = 0xFF2E7D32).generateColorScheme(dark = true)
        assertEquals(expected.primary, s.primary)
        assertEquals(expected.primaryContainer, s.primaryContainer)
    }

    @Test
    fun `invalid hex is treated as unset`() {
        assertEquals(base, base.withRoleOverrides(ThemeModeRoleOverrides(backgroundHex = "#XYZ123"), dark = false))
        assertEquals(base, base.withRoleOverrides(ThemeModeRoleOverrides(backgroundHex = "#12345"), dark = false))
    }

    @Test
    fun `hex parsing accepts optional hash and lowercase`() {
        assertEquals(Color(0xFF6750A4), "#6750a4".toComposeColorOrNull())
        assertEquals(Color(0xFF6750A4), "6750A4".toComposeColorOrNull())
        assertEquals(null, "#6750A".toComposeColorOrNull())
        assertEquals(null, "#6750A4X".toComposeColorOrNull())
    }

    @Test
    fun `hex code roundtrip`() {
        val color = Color(0xFF12ABEF)
        assertEquals(color, color.toHexCode().toComposeColorOrNull())
    }

    @Test
    fun `wcag contrast ratio known values`() {
        assertEquals(21.0, wcagContrastRatio(Color.Black, Color.White), 0.1)
        assertEquals(1.0, wcagContrastRatio(Color.White, Color.White), 0.001)
        // AA 正文阈值语义：黑白远超 4.5，同色低于
        assert(wcagContrastRatio(Color.Black, Color.White) >= WCAG_AA_CONTRAST)
        assert(wcagContrastRatio(Color.White, Color.White) < WCAG_AA_CONTRAST)
    }

    @Test
    fun `contrast level normalizes to nearest legal tier`() {
        assertEquals(0.0, CustomTheme.normalizeContrastLevel(null), 0.0)
        assertEquals(0.0, CustomTheme.normalizeContrastLevel(0.2), 0.0)
        assertEquals(0.5, CustomTheme.normalizeContrastLevel(0.3), 0.0)
        assertEquals(0.5, CustomTheme.normalizeContrastLevel(0.7), 0.0)
        assertEquals(1.0, CustomTheme.normalizeContrastLevel(0.8), 0.0)
        assertEquals(1.0, CustomTheme.normalizeContrastLevel(2.0), 0.0)
        assertEquals(0.0, CustomTheme.normalizeContrastLevel(-5.0), 0.0)
    }

    @Test
    fun `palette style maps to vendored variant by name`() {
        assertEquals(Variant.VIBRANT, ThemePaletteStyle.VIBRANT.toMcuVariant())
        assertEquals(Variant.FRUIT_SALAD, ThemePaletteStyle.FRUIT_SALAD.toMcuVariant())
        assertEquals(Variant.TONAL_SPOT, ThemePaletteStyle.TONAL_SPOT.toMcuVariant())
    }

    @Test
    fun `unknown palette style in json is rejected not silent`() {
        val json = Json { ignoreUnknownKeys = true }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<CustomTheme>("""{"paletteStyle":"nope"}""")
        }
    }
}
