package app.androidrik.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.androidrik.rikkahub.data.model.CustomTheme
import app.androidrik.rikkahub.data.model.ThemePaletteStyle
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinContrastAcceptanceTest {
    @Test fun builtInPalettesKeepBodyAndControlTextReadableInBothModes() {
        val failures = mutableListOf<String>()
        for (preset in PresetThemes) for (dark in listOf(false, true)) {
            audit("${preset.id}/$dark", preset.getColorScheme(dark), failures)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun generatedPalettesKeepTextReadableAcrossStylesSeedsAndContrastLevels() {
        val failures = mutableListOf<String>()
        for (style in ThemePaletteStyle.entries) for (seed in listOf(0xFF6750A4, 0xFF00FF00, 0xFFFF0000, 0xFF0000FF))
            for (level in listOf(0.0, .5, 1.0)) for (dark in listOf(false, true)) {
                audit("$style/$seed/$level/$dark", CustomTheme(primaryColorArgb = seed,
                    paletteStyle = style, contrastLevel = level).generateColorScheme(dark), failures)
            }
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }

    @Test fun adaptiveInkMeetsBodyContrastForSolidSrgbBackgrounds() {
        val failures = mutableListOf<String>()
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val background = Color(r, g, b)
            val ratio = wcagContrastRatio(adaptiveContentColor(background), background)
            if (ratio < WCAG_AA_CONTRAST) failures += "$r,$g,$b = $ratio"
        }
        assertTrue(failures.take(15).joinToString("\n"), failures.isEmpty())
    }

    private fun audit(name: String, c: ColorScheme, failures: MutableList<String>) {
        val pairs = listOf(
            "surface" to (c.onSurface to c.surface), "background" to (c.onBackground to c.background),
            "variant" to (c.onSurfaceVariant to c.surfaceContainerHighest),
            "formDescription" to (c.onSurface.copy(alpha = .75f).compositeOver(c.surfaceContainerHighest) to c.surfaceContainerHighest),
            "primary" to (c.onPrimary to c.primary), "primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
            "secondary" to (c.onSecondary to c.secondary), "secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
            "tertiary" to (c.onTertiary to c.tertiary), "tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
            "error" to (c.onError to c.error), "errorContainer" to (c.onErrorContainer to c.errorContainer),
            "inverse" to (c.inverseOnSurface to c.inverseSurface),
        )
        for ((role, colors) in pairs) {
            val ratio = wcagContrastRatio(colors.first, colors.second)
            if (ratio < WCAG_AA_CONTRAST) failures += "$name/$role = $ratio"
        }
    }
}
