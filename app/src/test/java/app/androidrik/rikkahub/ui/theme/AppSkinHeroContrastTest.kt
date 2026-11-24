package app.androidrik.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.androidrik.rikkahub.ui.components.ui.appSkinHeroForeground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSkinHeroContrastTest {
    private val decoration = Color.White.copy(alpha = 0.06f)

    @Test fun `light blue keeps both material foreground roles`() {
        val title = Color(0xFF181C20)
        val description = Color(0xFF41474D)
        val stops = listOf(Color(0xFFD6ECFF), Color(0xFFC4E3F7), Color(0xFFD9F0F5))

        val foreground = appSkinHeroForeground(stops, decoration, title, description)

        assertEquals(title, foreground.title)
        assertEquals(description, foreground.description)
        assertEquals(Color.Transparent, foreground.scrim)
    }

    @Test fun `dark blue adjusts low contrast roles in either mode without a scrim`() {
        val end = Color(0xFF356985)
        val stops = listOf(Color(0xFF16344F), Color(0xFF245979), end)
        val decoratedEnd = decoration.compositeOver(decoration.compositeOver(end))
        val rolePairs = listOf(
            Color(0xFFDFE3E8) to Color(0xFFC1C7CE),
            Color(0xFF181C20) to Color(0xFF41474D),
        )

        for ((title, description) in rolePairs) {
            val foreground = appSkinHeroForeground(stops, decoration, title, description)
            assertEquals(Color.White, foreground.description)
            assertEquals(Color.Transparent, foreground.scrim)
            assertTrue(wcagContrastRatio(foreground.title, decoratedEnd) >= WCAG_AA_CONTRAST)
            assertTrue(wcagContrastRatio(foreground.description, decoratedEnd) >= WCAG_AA_CONTRAST)
        }
    }

    @Test fun `mixed gradients receive a local scrim that guarantees text contrast`() {
        val foreground = appSkinHeroForeground(
            listOf(Color.Black, Color.White), decoration, Color.Black, Color.White,
        )

        assertEquals(Color.White, foreground.title)
        assertEquals(Color.White, foreground.description)
        assertTrue(foreground.scrim.alpha > 0f)
        assertTrue(wcagContrastRatio(foreground.description, foreground.scrim.compositeOver(Color.White)) >= WCAG_AA_CONTRAST)
    }
}
