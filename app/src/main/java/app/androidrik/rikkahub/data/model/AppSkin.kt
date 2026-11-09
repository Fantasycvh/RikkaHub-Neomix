package app.androidrik.rikkahub.data.model

import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import kotlinx.serialization.Serializable

/** Application-wide tokens. ChatAppearance remains a separate domain in ResolvedAppSkin. */
@Serializable
data class AppSkin(
    val typography: AppSkinTypography = AppSkinTypography(),
    val shapes: AppSkinShapes = AppSkinShapes(),
    val spacing: AppSkinSpacing = AppSkinSpacing(),
    val motion: AppSkinMotion = AppSkinMotion.EXPRESSIVE,
    /** null retains the existing dynamic/preset/custom palette selection. */
    val palette: CustomTheme? = null,
    val topBar: AppSkinTopBar = AppSkinTopBar(),
    val drawer: AppSkinDrawer = AppSkinDrawer(),
    val cards: AppSkinCards = AppSkinCards(),
) {
    fun normalized() = copy(typography = typography.normalized(), shapes = shapes.normalized(), spacing = spacing.normalized(),
        topBar = topBar.normalized(), drawer = drawer.normalized(), cards = cards.normalized())
}

@Serializable
data class AppSkinTypography(
    val family: ChatFontFamily = ChatFontFamily.DEFAULT,
    val customFontPath: String = "",
    val customFontName: String = "",
    val sizeRatio: Float = 1f,
) {
    fun normalized() = copy(sizeRatio = sizeRatio.bound(0.75f, 1.5f, 1f),
        customFontPath = customFontPath.takeIf { it.length <= 1024 }.orEmpty(),
        customFontName = customFontName.take(256))
}

@Serializable
data class AppSkinShapes(
    val extraSmallDp: Float = 4f,
    val smallDp: Float = 8f,
    val mediumDp: Float = 12f,
    val largeDp: Float = 16f,
    val extraLargeDp: Float = 28f,
    val cardGroupOuterDp: Float = 20f,
    val cardGroupInnerDp: Float = 4f,
    /** null keeps Material's full capsule button. Explicit caller shapes still take precedence. */
    val buttonDp: Float? = null,
    val inputDp: Float = 4f,
) {
    /** Preserve host radii between the five Material reference sizes while following skin tokens. */
    fun scaledCornerRadius(defaultDp: Float): Float {
        if (!defaultDp.isFinite() || defaultDp <= 0f) return 0f
        fun between(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
        val xs = extraSmallDp.bound(0f, 48f, 4f)
        val sm = smallDp.bound(0f, 48f, 8f)
        val md = mediumDp.bound(0f, 48f, 12f)
        val lg = largeDp.bound(0f, 48f, 16f)
        val xl = extraLargeDp.bound(0f, 64f, 28f)
        return when {
            defaultDp <= 4f -> xs * defaultDp / 4f
            defaultDp <= 8f -> between(xs, sm, (defaultDp - 4f) / 4f)
            defaultDp <= 12f -> between(sm, md, (defaultDp - 8f) / 4f)
            defaultDp <= 16f -> between(md, lg, (defaultDp - 12f) / 4f)
            defaultDp <= 28f -> between(lg, xl, (defaultDp - 16f) / 12f)
            else -> (xl * defaultDp / 28f).coerceIn(0f, 64f)
        }
    }

    fun normalized() = copy(extraSmallDp = extraSmallDp.bound(0f, 48f, 4f), smallDp = smallDp.bound(0f, 48f, 8f),
        mediumDp = mediumDp.bound(0f, 48f, 12f), largeDp = largeDp.bound(0f, 48f, 16f),
        extraLargeDp = extraLargeDp.bound(0f, 64f, 28f), cardGroupOuterDp = cardGroupOuterDp.bound(0f, 48f, 20f),
        cardGroupInnerDp = cardGroupInnerDp.bound(0f, 24f, 4f), buttonDp = buttonDp?.bound(0f, 48f, 20f),
        inputDp = inputDp.bound(0f, 48f, 4f))
}

@Serializable
data class AppSkinSpacing(
    val pageDp: Float = 8f,
    val sectionDp: Float = 16f,
    val groupItemDp: Float = 2f,
    val formHorizontalDp: Float = 16f,
    val formVerticalDp: Float = 12f,
    val formGapDp: Float = 12f,
) {
    fun normalized() = copy(pageDp = pageDp.bound(0f, 32f, 8f), sectionDp = sectionDp.bound(0f, 40f, 16f),
        groupItemDp = groupItemDp.bound(0f, 16f, 2f), formHorizontalDp = formHorizontalDp.bound(8f, 32f, 16f),
        formVerticalDp = formVerticalDp.bound(4f, 24f, 12f), formGapDp = formGapDp.bound(4f, 24f, 12f))
}

@Serializable
enum class AppSkinMotion { EXPRESSIVE, STANDARD }

/** Group-wise overrides. null inherits the selected skin; resetting a group resumes inheritance. */
@Serializable
data class AppSkinOverrides(
    val typography: AppSkinTypography? = null,
    val shapes: AppSkinShapes? = null,
    val spacing: AppSkinSpacing? = null,
    val motion: AppSkinMotion? = null,
    val palette: CustomTheme? = null,
    val topBar: AppSkinTopBar? = null,
    val drawer: AppSkinDrawer? = null,
    val cards: AppSkinCards? = null,
) {
    fun applyTo(base: AppSkin) = AppSkin(typography ?: base.typography, shapes ?: base.shapes,
        spacing ?: base.spacing, motion ?: base.motion, palette ?: base.palette,
        topBar ?: base.topBar, drawer ?: base.drawer, cards ?: base.cards).normalized()

    /** Only edited groups become overrides; untouched groups continue following the selected skin. */
    fun withEdits(before: AppSkin, after: AppSkin): AppSkinOverrides = after.normalized().let { value -> copy(
        typography = if (before.typography != value.typography) value.typography else typography,
        shapes = if (before.shapes != value.shapes) value.shapes else shapes,
        spacing = if (before.spacing != value.spacing) value.spacing else spacing,
        motion = if (before.motion != value.motion) value.motion else motion,
        palette = if (before.palette != value.palette) value.palette else palette,
        topBar = if (before.topBar != value.topBar) value.topBar else topBar,
        drawer = if (before.drawer != value.drawer) value.drawer else drawer,
        cards = if (before.cards != value.cards) value.cards else cards,
    ) }

    companion object {
        fun from(skin: AppSkin) = AppSkinOverrides(skin.typography, skin.shapes, skin.spacing, skin.motion, skin.palette,
            skin.topBar, skin.drawer, skin.cards)
    }
}

private fun Float.bound(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback
