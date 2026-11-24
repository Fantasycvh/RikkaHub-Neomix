package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.AppSkin
import app.androidrik.rikkahub.data.model.AppSkinHero
import app.androidrik.rikkahub.data.model.AppSkinIconTile
import app.androidrik.rikkahub.data.model.AppSkinOverrides
import app.androidrik.rikkahub.data.model.AppSkinShapes
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.GradientDirection
import app.androidrik.rikkahub.data.model.panels
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSkinHeroTest {
    private val lightGradient = AppearanceGradient(listOf("#D6ECFF", "#D9F0F5"))
    private val darkGradient = AppearanceGradient(listOf("#16344F", "#356985"))

    @Test fun `legacy JSON and existing builtins leave icon tiles and the hero opt in`() {
        val legacy = """{"shapes":{"continuous":true,"inputDp":12.0}}"""
        val shapes = AppSkinShapes(continuous = true, inputDp = 12f)
        val skin = Json.decodeFromString<AppSkin>(legacy).normalized()
        val overrides = Json.decodeFromString<AppSkinOverrides>(legacy)

        assertEquals(AppSkin(shapes = shapes), skin)
        assertEquals(AppSkinOverrides(shapes = shapes), overrides)
        for (id in listOf("default", "compact", "reading", "squircle", "paper", "engineering", "ios")) {
            val builtin = builtInAppSkins.first { it.id == "builtin.$id" }.skin
            assertNull(builtin.shapes.iconTile)
            assertEquals(AppSkinHero(), builtin.hero)
        }
    }

    @Test fun `skin normalization bounds hero and icon tile geometry`() {
        val skin = AppSkin(
            shapes = AppSkinShapes(iconTile = AppSkinIconTile(sizeDp = -1f, radiusDp = 99f, iconSizeDp = 99f)),
            hero = AppSkinHero(
                radiusDp = 99f,
                paddingHorizontalDp = Float.NaN,
                paddingVerticalDp = -1f,
                decorationOpacity = 1f,
            ),
        ).normalized()

        assertEquals(AppSkinIconTile(32f, 16f, 28f), skin.shapes.iconTile)
        assertEquals(AppSkinHero(radiusDp = 48f, paddingVerticalDp = 0f, decorationOpacity = 0.2f), skin.hero)
        val invalidTile = AppSkinIconTile(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        assertEquals(AppSkinIconTile(), invalidTile.normalized())
        assertEquals(AppSkinIconTile(56f, 0f, 16f), AppSkinIconTile(99f, -1f, -1f).normalized())
        assertEquals(AppSkinHero(), AppSkinHero(radiusDp = Float.POSITIVE_INFINITY, decorationOpacity = Float.NaN).normalized())
        assertEquals(skin, skin.normalized())
    }

    @Test fun `hero uses the remaining valid mode and preserves chat gradient fallback`() {
        val invalid = AppearanceGradient(listOf("#123", "#16344F", "#FF16344F"), GradientDirection.VERTICAL)
        val lightOnly = AppSkinHero(visible = true, lightGradient = lightGradient, darkGradient = invalid).normalized()
        val darkOnly = AppSkinHero(visible = true, lightGradient = invalid, darkGradient = darkGradient).normalized()

        assertNull(lightOnly.darkGradient)
        assertEquals(lightGradient, lightOnly.gradientFor(darkMode = false))
        assertEquals(lightGradient, lightOnly.gradientFor(darkMode = true))
        assertNull(darkOnly.lightGradient)
        assertEquals(darkGradient, darkOnly.gradientFor(darkMode = false))
        assertEquals(darkGradient, darkOnly.gradientFor(darkMode = true))
        assertNull(darkOnly.copy(visible = false).gradientFor(darkMode = true))
        val noValidGradient = AppSkinHero(visible = true, lightGradient = invalid, darkGradient = invalid).normalized()
        assertNull(noValidGradient.gradientFor(darkMode = true))
        assertEquals(AppearanceGradient(direction = GradientDirection.VERTICAL), invalid.normalized())
    }

    @Test fun `hero keeps the first eight valid color stops in their original direction`() {
        val validColors = List(10) { "#00000$it" }
        val gradient = AppearanceGradient(listOf("invalid") + validColors, GradientDirection.HORIZONTAL)
        val hero = AppSkinHero(visible = true, lightGradient = gradient, darkGradient = darkGradient).normalized()

        assertEquals(gradient.copy(colors = validColors.take(8)), hero.gradientFor(darkMode = false))
        assertEquals(darkGradient, hero.gradientFor(darkMode = true))
    }

    @Test fun `hero and shapes edits override independently and can return to inheritance`() {
        val originalHero = AppSkinHero(visible = true, lightGradient = lightGradient)
        val base = AppSkin(hero = originalHero)
        val localShapes = base.shapes.copy(iconTile = AppSkinIconTile())
        val heroOverride = AppSkinOverrides().withEdits(base, base.copy(hero = AppSkinHero()))
        val shapesOverride = AppSkinOverrides().withEdits(base, base.copy(shapes = localShapes))
        val updatedBase = base.copy(
            hero = originalHero.copy(darkGradient = darkGradient),
            shapes = base.shapes.copy(inputDp = 10f),
        )

        assertEquals(AppSkinOverrides(hero = AppSkinHero()), heroOverride)
        assertEquals(updatedBase.copy(hero = AppSkinHero()), heroOverride.applyTo(updatedBase))
        assertEquals(updatedBase, heroOverride.copy(hero = null).applyTo(updatedBase))
        assertEquals(AppSkinOverrides(shapes = localShapes), shapesOverride)
        assertEquals(updatedBase.copy(shapes = localShapes), shapesOverride.applyTo(updatedBase))
        assertEquals(updatedBase, shapesOverride.copy(shapes = null).applyTo(updatedBase))
    }

    @Test fun `settings and appearance snapshots round trip both tokens`() {
        val skin = AppSkin(
            shapes = AppSkinShapes(iconTile = AppSkinIconTile(sizeDp = 44f)),
            hero = AppSkinHero(visible = true, lightGradient = lightGradient, darkGradient = darkGradient),
        )
        val display = DisplaySetting(appSkinOverrides = AppSkinOverrides.from(skin))
        val saved = Json.encodeToString(DisplaySetting.serializer(), display)
        val snapshot = display.toAppearanceSnapshot()
        val exported = Json.encodeToString(ThemeAppearanceSnapshot.serializer(), snapshot)
        val restored = DisplaySetting().withAppearanceSnapshot(Json.decodeFromString<ThemeAppearanceSnapshot>(exported))

        assertEquals(skin, Json.decodeFromString<DisplaySetting>(saved).appSkinOverrides.applyTo(AppSkin()))
        assertEquals(skin, restored.appSkinOverrides.applyTo(AppSkin()))
        assertEquals(AppSkin().panels(), skin.panels())
    }
}
