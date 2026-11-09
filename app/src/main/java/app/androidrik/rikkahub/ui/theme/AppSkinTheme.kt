package app.androidrik.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.model.AppSkinMotion
import app.androidrik.rikkahub.data.model.AppSkinShapes
import app.androidrik.rikkahub.data.model.AppSkinTypography

/** Shared by the application root, previews and instrumented component hosts. */
@Composable
fun AppSkinTheme(resolved: ResolvedAppSkin, colorScheme: ColorScheme, content: @Composable () -> Unit) {
    val tokens = resolved.skin.normalized()
    val context = LocalContext.current
    val family = remember(context, tokens.typography.family, tokens.typography.customFontPath) {
        if (tokens.typography.family == ChatFontFamily.DEFAULT) null
        else resolveChatFontFamily(context, tokens.typography.family, tokens.typography.customFontPath)
    }
    val typography = remember(tokens.typography, family) { appSkinTypography(Typography, tokens.typography, family) }
    val shapes = remember(tokens.shapes) { appSkinShapes(tokens.shapes) }
    CompositionLocalProvider(LocalAppSkin provides resolved.copy(skin = tokens)) {
        MaterialExpressiveTheme(colorScheme = colorScheme, typography = typography, shapes = shapes,
            motionScheme = if (tokens.motion == AppSkinMotion.EXPRESSIVE) MotionScheme.expressive() else MotionScheme.standard(),
            content = content)
    }
}

internal fun appSkinShapes(value: AppSkinShapes): Shapes = value.normalized().run {
    Shapes(extraSmall = RoundedCornerShape(extraSmallDp.dp), small = RoundedCornerShape(smallDp.dp),
        medium = RoundedCornerShape(mediumDp.dp), large = RoundedCornerShape(largeDp.dp), extraLarge = RoundedCornerShape(extraLargeDp.dp))
}

internal fun appSkinTypography(base: Typography, tokens: AppSkinTypography, family: FontFamily?): Typography {
    val ratio = tokens.normalized().sizeRatio
    if (ratio == 1f && family == null) return base
    fun style(value: TextStyle) = value.copy(fontFamily = family ?: value.fontFamily,
        fontSize = if (value.fontSize.isSpecified) value.fontSize * ratio else value.fontSize,
        lineHeight = if (value.lineHeight.isSpecified) value.lineHeight * ratio else value.lineHeight)
    return base.copy(
        displayLarge = style(base.displayLarge),
        displayMedium = style(base.displayMedium),
        displaySmall = style(base.displaySmall),
        headlineLarge = style(base.headlineLarge),
        headlineMedium = style(base.headlineMedium),
        headlineSmall = style(base.headlineSmall),
        titleLarge = style(base.titleLarge),
        titleMedium = style(base.titleMedium),
        titleSmall = style(base.titleSmall),
        bodyLarge = style(base.bodyLarge),
        bodyMedium = style(base.bodyMedium),
        bodySmall = style(base.bodySmall),
        labelLarge = style(base.labelLarge),
        labelMedium = style(base.labelMedium),
        labelSmall = style(base.labelSmall),
        displayLargeEmphasized = style(base.displayLargeEmphasized),
        displayMediumEmphasized = style(base.displayMediumEmphasized),
        displaySmallEmphasized = style(base.displaySmallEmphasized),
        headlineLargeEmphasized = style(base.headlineLargeEmphasized),
        headlineMediumEmphasized = style(base.headlineMediumEmphasized),
        headlineSmallEmphasized = style(base.headlineSmallEmphasized),
        titleLargeEmphasized = style(base.titleLargeEmphasized),
        titleMediumEmphasized = style(base.titleMediumEmphasized),
        titleSmallEmphasized = style(base.titleSmallEmphasized),
        bodyLargeEmphasized = style(base.bodyLargeEmphasized),
        bodyMediumEmphasized = style(base.bodyMediumEmphasized),
        bodySmallEmphasized = style(base.bodySmallEmphasized),
        labelLargeEmphasized = style(base.labelLargeEmphasized),
        labelMediumEmphasized = style(base.labelMediumEmphasized),
        labelSmallEmphasized = style(base.labelSmallEmphasized),
    )
}
