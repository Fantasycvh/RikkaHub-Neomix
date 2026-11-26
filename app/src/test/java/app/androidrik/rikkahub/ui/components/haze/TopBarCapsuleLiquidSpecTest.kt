package app.androidrik.rikkahub.ui.components.haze

import app.androidrik.rikkahub.data.datastore.BackgroundEffectType
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarCapsuleLiquidSpecTest {

    @Test
    fun `top bar capsule effect follows top bar toggle rather than input bar toggle`() {
        val topBarGlassOnly = DisplaySetting(
            enableBlurEffect = false,
            inputEffectType = BackgroundEffectType.BLUR,
            enableTopBarBlur = true,
            topBarEffectType = BackgroundEffectType.GLASS,
        )
        assertEquals(BackgroundEffectType.GLASS, resolveTopBarCapsuleEffectType(topBarGlassOnly))

        val topBarBlurWithInputGlass = DisplaySetting(
            enableBlurEffect = true,
            inputEffectType = BackgroundEffectType.GLASS,
            enableTopBarBlur = true,
            topBarEffectType = BackgroundEffectType.BLUR,
        )
        assertEquals(BackgroundEffectType.BLUR, resolveTopBarCapsuleEffectType(topBarBlurWithInputGlass))

        val topBarDisabledWithInputGlass = DisplaySetting(
            enableBlurEffect = true,
            inputEffectType = BackgroundEffectType.GLASS,
            enableTopBarBlur = false,
            topBarEffectType = BackgroundEffectType.GLASS,
        )
        assertNull(resolveTopBarCapsuleEffectType(topBarDisabledWithInputGlass))
    }

    @Test
    fun `crystal AUTO optics are more translucent and refractive than STABLE_CONTENT`() {
        val autoLight = resolveLiquidCapsuleOptics(LiquidGlassReadabilityMode.AUTO, darkMode = false)
        val autoDark = resolveLiquidCapsuleOptics(LiquidGlassReadabilityMode.AUTO, darkMode = true)
        val stableLight = resolveLiquidCapsuleOptics(LiquidGlassReadabilityMode.STABLE_CONTENT, darkMode = false)
        val stableDark = resolveLiquidCapsuleOptics(LiquidGlassReadabilityMode.STABLE_CONTENT, darkMode = true)

        assertTrue(autoLight.blurRadiusDp < stableLight.blurRadiusDp)
        assertTrue(autoLight.depth > stableLight.depth)
        assertTrue(autoLight.refractionStrength > stableLight.refractionStrength)
        assertTrue(autoLight.tintAlpha < 0.25f)
        assertTrue(autoDark.tintAlpha > autoLight.tintAlpha)
        assertTrue(autoLight.chromaticAberrationStrength > 0f)
        assertTrue(autoDark.chromaticAberrationStrength > 0f)
        assertTrue(autoLight.useSquircleProfile)

        assertEquals(0f, stableLight.chromaticAberrationStrength, 0.0001f)
        assertEquals(0f, stableDark.chromaticAberrationStrength, 0.0001f)
        assertTrue(stableLight.tintAlpha >= 0.5f)
        assertTrue(stableDark.tintAlpha >= 0.5f)
    }

    @Test
    fun `capsule chrome spec enables gradient rim on glass and respects skin overrides`() {
        val glassLight = resolveTopBarCapsuleChromeSpec(
            effectType = BackgroundEffectType.GLASS,
            darkMode = false,
            skinBorderWidthDp = null,
            hasSkinBorderColor = false,
            hasSkinBackground = false,
        )
        assertTrue(glassLight.useGradientRim)
        assertEquals(0.75f, glassLight.borderWidthDp, 0.001f)
        assertTrue(glassLight.shadowElevationDp > 2f)

        val glassDark = resolveTopBarCapsuleChromeSpec(
            effectType = BackgroundEffectType.GLASS,
            darkMode = true,
            skinBorderWidthDp = 0.5f,
            hasSkinBorderColor = false,
            hasSkinBackground = false,
        )
        assertTrue(glassDark.useGradientRim)
        assertEquals(0.5f, glassDark.borderWidthDp, 0.001f)
        assertTrue(glassDark.spotShadowAlpha > glassLight.spotShadowAlpha)

        val customColorSkin = resolveTopBarCapsuleChromeSpec(
            effectType = BackgroundEffectType.GLASS,
            darkMode = false,
            skinBorderWidthDp = 1f,
            hasSkinBorderColor = true,
            hasSkinBackground = false,
        )
        assertFalse(customColorSkin.useGradientRim)

        val zeroBorderSkin = resolveTopBarCapsuleChromeSpec(
            effectType = BackgroundEffectType.GLASS,
            darkMode = false,
            skinBorderWidthDp = 0f,
            hasSkinBorderColor = false,
            hasSkinBackground = false,
        )
        assertFalse(zeroBorderSkin.useGradientRim)
        assertEquals(0f, zeroBorderSkin.borderWidthDp, 0.001f)

        val solidDefault = resolveTopBarCapsuleChromeSpec(
            effectType = null,
            darkMode = false,
            skinBorderWidthDp = null,
            hasSkinBorderColor = false,
            hasSkinBackground = false,
        )
        assertFalse(solidDefault.useGradientRim)
        assertEquals(1f, solidDefault.borderWidthDp, 0.001f)
        assertEquals(2f, solidDefault.shadowElevationDp, 0.001f)
    }
}
