package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSkinHero(
    val visible: Boolean = false,
    val radiusDp: Float = 28f,
    val paddingHorizontalDp: Float = 20f,
    val paddingVerticalDp: Float = 20f,
    val lightGradient: AppearanceGradient? = null,
    val darkGradient: AppearanceGradient? = null,
    val decorationOpacity: Float = 0.06f,
) {
    fun normalized() = copy(
        radiusDp = radiusDp.bound(0f, 48f, 28f),
        paddingHorizontalDp = paddingHorizontalDp.bound(0f, 32f, 20f),
        paddingVerticalDp = paddingVerticalDp.bound(0f, 24f, 20f),
        lightGradient = lightGradient?.normalizedOrNull(),
        darkGradient = darkGradient?.normalizedOrNull(),
        decorationOpacity = decorationOpacity.bound(0f, 0.2f, 0.06f),
    )

    /** Selects a background from the normalized token. A missing mode inherits the other gradient. */
    fun gradientFor(darkMode: Boolean): AppearanceGradient? = when {
        !visible -> null
        darkMode -> darkGradient ?: lightGradient
        else -> lightGradient ?: darkGradient
    }
}

private fun Float.bound(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback
