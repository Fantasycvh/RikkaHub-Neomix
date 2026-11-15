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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
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
    val typography = remember(tokens.typography, family) {
        val base = if (tokens.typography.expressive) expressiveTypography else Typography
        appSkinTypography(base, tokens.typography, family)
    }
    val shapes = remember(tokens.shapes) { appSkinShapes(tokens.shapes) }
    val motionTokens = rememberResolvedMotionTokens(tokens)
    CompositionLocalProvider(LocalAppSkin provides resolved.copy(skin = tokens), LocalMotionTokens provides motionTokens) {
        MaterialExpressiveTheme(colorScheme = colorScheme, typography = typography, shapes = shapes,
            motionScheme = if (tokens.motion == AppSkinMotion.EXPRESSIVE) MotionScheme.expressive() else MotionScheme.standard(),
            content = content)
    }
}

internal fun appSkinShapes(value: AppSkinShapes): Shapes = value.normalized().run {
    val s: (Float) -> androidx.compose.foundation.shape.CornerBasedShape = {
        if (continuous) SmoothCornerShape(it.dp) else RoundedCornerShape(it.dp)
    }
    Shapes(extraSmall = s(extraSmallDp), small = s(smallDp),
        medium = s(mediumDp), large = s(largeDp), extraLarge = s(extraLargeDp),
        largeIncreased = s(scaledCornerRadius(20f)),
        extraLargeIncreased = s(scaledCornerRadius(32f)),
        extraExtraLarge = s(scaledCornerRadius(48f)))
}

internal fun appSkinTypography(base: Typography, tokens: AppSkinTypography, family: FontFamily?): Typography {
    val t = tokens.normalized()
    val ratio = t.sizeRatio
    if (ratio == 1f && family == null && t.weightDelta == 0 && t.letterSpacingSp == 0f &&
        !t.tabularNumerals && !t.monoLabels) return base
    val features = if (t.tabularNumerals) "tnum" else null
    fun style(value: TextStyle, label: Boolean = false) = value.copy(
        fontFamily = when {
            family != null -> family
            t.monoLabels && label -> FontFamily.Monospace
            else -> value.fontFamily
        },
        fontWeight = value.fontWeight?.let { weight ->
            if (t.weightDelta == 0) weight
            else FontWeight((weight.weight + t.weightDelta).coerceIn(100, 900))
        },
        fontSize = if (value.fontSize.isSpecified) value.fontSize * ratio else value.fontSize,
        lineHeight = if (value.lineHeight.isSpecified) value.lineHeight * ratio else value.lineHeight,
        // 未指定且无偏移时保持 Unspecified，避免给全仓文字加一层 0sp 的隐式覆盖。
        letterSpacing = when {
            !value.letterSpacing.isSpecified || t.letterSpacingSp == 0f -> value.letterSpacing
            value.letterSpacing.type == TextUnitType.Sp -> (value.letterSpacing.value + t.letterSpacingSp).sp
            else -> value.letterSpacing
        },
        fontFeatureSettings = features ?: value.fontFeatureSettings,
    )
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
        labelLarge = style(base.labelLarge, label = true),
        labelMedium = style(base.labelMedium, label = true),
        labelSmall = style(base.labelSmall, label = true),
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
        labelLargeEmphasized = style(base.labelLargeEmphasized, label = true),
        labelMediumEmphasized = style(base.labelMediumEmphasized, label = true),
        labelSmallEmphasized = style(base.labelSmallEmphasized, label = true),
    )
}
