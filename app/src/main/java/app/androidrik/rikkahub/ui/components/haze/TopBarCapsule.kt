package app.androidrik.rikkahub.ui.components.haze

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.BackgroundEffectType
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.LocalMotionTokens
import app.androidrik.rikkahub.ui.theme.appSkinPanelBackground
import app.androidrik.rikkahub.ui.theme.border
import app.androidrik.rikkahub.ui.theme.ink
import dev.chrisbanes.haze.HazeState

internal data class TopBarCapsuleChromeSpec(
    val borderWidthDp: Float,
    val shadowElevationDp: Float,
    val spotShadowAlpha: Float,
    val ambientShadowAlpha: Float,
    val useGradientRim: Boolean,
)

/**
 * 顶栏胶囊外框与浮空投影规格（纯函数，便于单测验证玻璃态/纯色态与皮肤覆写优先级）。
 */
internal fun resolveTopBarCapsuleChromeSpec(
    effectType: BackgroundEffectType?,
    darkMode: Boolean,
    skinBorderWidthDp: Float?,
    hasSkinBorderColor: Boolean,
    hasSkinBackground: Boolean,
): TopBarCapsuleChromeSpec {
    val effectActive = effectType != null && !hasSkinBackground
    val useGradientRim = effectActive && !hasSkinBorderColor && skinBorderWidthDp != 0f
    val borderWidthDp = when {
        skinBorderWidthDp != null -> skinBorderWidthDp
        effectActive -> 0.75f
        else -> 1f
    }
    return TopBarCapsuleChromeSpec(
        borderWidthDp = borderWidthDp,
        shadowElevationDp = if (effectActive) 5f else 2f,
        spotShadowAlpha = if (darkMode) 0.38f else 0.12f,
        ambientShadowAlpha = if (darkMode) 0.22f else 0.06f,
        useGradientRim = useGradientRim,
    )
}

@Composable
private fun liquidCapsuleRimBorder(
    width: Dp,
    darkMode: Boolean,
    isCrystalGlass: Boolean,
): BorderStroke {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val colors = when {
        darkMode && isCrystalGlass -> listOf(
            Color.White.copy(alpha = 0.36f),
            Color.White.copy(alpha = 0.11f),
            Color.White.copy(alpha = 0.04f),
        )

        darkMode -> listOf(
            Color.White.copy(alpha = 0.24f),
            Color.White.copy(alpha = 0.08f),
            outline.copy(alpha = 0.24f),
        )

        isCrystalGlass -> listOf(
            Color.White.copy(alpha = 0.84f),
            Color.White.copy(alpha = 0.24f),
            Color.Black.copy(alpha = 0.08f),
        )

        else -> listOf(
            Color.White.copy(alpha = 0.62f),
            outline.copy(alpha = 0.16f),
            outline.copy(alpha = 0.30f),
        )
    }
    return BorderStroke(width = width, brush = Brush.verticalGradient(colors))
}

/**
 * 顶栏浮动液态胶囊容器（三段式顶栏：左导航丸 / 中标题胶囊 / 右操作胶囊共用）。
 *
 * - 背景效果直接跟随顶栏自身设置（[DisplaySetting.enableTopBarBlur] / [DisplaySetting.topBarEffectType]）；
 * - 仅当消息滚到标题栏下方时按 [showBackground] 伴随微幅弹性缩放凝结淡入，前景按钮/标题保持稳定；
 * - 玻璃态下以「顶部高光 → 底部暗隐」渐变棱边与轻柔浮空投影替代生硬灰框；自定义皮肤仍可按需覆写。
 */
@Composable
fun TopBarCapsule(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    showBackground: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val darkMode = LocalDarkMode.current
    val motion = LocalMotionTokens.current
    val panel = LocalAppSkin.current.skin.topBar.panel
    val shape = panel.radiusDp?.let { RoundedCornerShape(it.dp) } ?: CircleShape
    val ink = panel.ink(MaterialTheme.colorScheme.onSurface, containerColor)
    val effectType = resolveTopBarCapsuleEffectType(displaySetting)
    val chromeSpec = resolveTopBarCapsuleChromeSpec(
        effectType = effectType,
        darkMode = darkMode,
        skinBorderWidthDp = panel.borderWidthDp,
        hasSkinBorderColor = !panel.borderColorHex.isNullOrBlank(),
        hasSkinBackground = panel.background != null,
    )
    val isCrystalGlass = effectType == BackgroundEffectType.GLASS &&
        displaySetting.liquidGlassReadabilityMode == LiquidGlassReadabilityMode.AUTO

    val backgroundAlpha by animateFloatAsState(
        targetValue = if (showBackground) 1f else 0f,
        animationSpec = tween(durationMillis = motion.quickMs),
        label = "TopBarCapsuleBackgroundAlpha",
    )
    val backdropScale by animateFloatAsState(
        targetValue = if (showBackground) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "TopBarCapsuleBackdropScale",
    )

    val resolvedBorder: BorderStroke? = when {
        chromeSpec.borderWidthDp <= 0f -> null
        chromeSpec.useGradientRim -> liquidCapsuleRimBorder(
            width = chromeSpec.borderWidthDp.dp,
            darkMode = darkMode,
            isCrystalGlass = isCrystalGlass,
        )
        panel.borderWidthDp != null -> panel.border()
        else -> BorderStroke(
            chromeSpec.borderWidthDp.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }

    val resolvedPadding = contentPadding ?: PaddingValues(
        horizontal = (panel.paddingHorizontalDp ?: 0f).dp,
        vertical = (panel.paddingVerticalDp ?: 0f).dp,
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (backgroundAlpha > 0.005f) {
            Surface(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = backgroundAlpha
                        scaleX = backdropScale
                        scaleY = backdropScale
                    }
                    .shadow(
                        elevation = chromeSpec.shadowElevationDp.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = chromeSpec.ambientShadowAlpha),
                        spotColor = Color.Black.copy(alpha = chromeSpec.spotShadowAlpha),
                    ),
                shape = shape,
                color = Color.Transparent,
                border = resolvedBorder,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .then(
                            when {
                                panel.background != null -> Modifier.appSkinPanelBackground(
                                    panel,
                                    containerColor,
                                    ink,
                                    shape,
                                )

                                effectType != null -> Modifier.topBarCapsuleBackgroundEffect(
                                    hazeState = hazeState,
                                    displaySetting = displaySetting,
                                    containerColor = containerColor,
                                    shape = shape,
                                )

                                else -> Modifier.background(containerColor)
                            }
                        )
                )
            }
        }
        Row(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier
                            .clip(shape)
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(resolvedPadding),
            horizontalArrangement = Arrangement.spacedBy((panel.gapDp ?: 0f).dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
