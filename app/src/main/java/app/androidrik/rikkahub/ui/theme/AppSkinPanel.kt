package app.androidrik.rikkahub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.TopBarChromeStyle
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.bubbleLayerBackground
import app.androidrik.rikkahub.ui.components.message.bubbleMaterialBackground

fun AppSkin.topBarMode(display: DisplaySetting): TopBarChromeStyle = topBar.mode ?: display.topBarStyle
/** Legacy settings remain an effective user override when a skin supplies its own chrome mode. */
fun DisplaySetting.withTopBarMode(mode: TopBarChromeStyle): DisplaySetting {
    val topBar = resolveAppSkin(this).skin.topBar
    return copy(topBarStyle = mode, appSkinOverrides = if (topBar.mode == null) appSkinOverrides
        else appSkinOverrides.copy(topBar = topBar.copy(mode = mode)))
}
fun AppSkinPanel.shape(fallback: Shape): Shape = radiusDp?.let { RoundedCornerShape(it.dp) } ?: fallback

@Composable
fun AppSkinPanel.border(): BorderStroke? = if ((borderWidthDp ?: 0f) > 0f)
    BorderStroke(requireNotNull(borderWidthDp).dp, borderColorHex.toComposeColorOrNull() ?: MaterialTheme.colorScheme.outlineVariant) else null

fun AppSkinPanel.ink(fallback: Color, base: Color): Color {
    contentColorHex.toComposeColorOrNull()?.let { return it }
    val material = background ?: return fallback
    if (!material.visible || material.opacity < 0.5f) return fallback
    val fill = material.effectiveStack().layers.lastOrNull { it.enabled && it.opacity >= 0.5f }
    val color = when (fill?.kind) {
        BubbleLayerKind.SOLID -> fill.colorHex.toComposeColorOrNull() ?: base
        BubbleLayerKind.GRADIENT -> fill.gradient.colors[fill.gradient.colors.size / 2].toComposeColorOrNull()
        BubbleLayerKind.SHADER -> fill.shader.colors[1].toComposeColorOrNull()
        else -> null
    }
    return color?.let(::adaptiveContentColor) ?: fallback
}

/** The existing bounded material engine paints only the background, without owning host layout/state. */
@Composable
fun Modifier.appSkinPanelBackground(panel: AppSkinPanel, base: Color, ink: Color, shape: Shape): Modifier {
    val material = panel.background?.normalized() ?: return this
    val color = material.colorHex.toComposeColorOrNull() ?: base
    val clipped = clip(shape)
    return if (material.stack == null) clipped.bubbleMaterialBackground(material, color, ink, liveGlass = false)
    else clipped.bubbleLayerBackground(material, color, ink, liveGlass = false, coordinates = null)
}

/** A stable decoration host for tool output and previews; content keeps its own interaction logic. */
@Composable
fun AppSkinPanelBox(panel: AppSkinPanel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = panel.shape(RectangleShape)
    val ink = panel.ink(LocalContentColor.current, MaterialTheme.colorScheme.surfaceContainerLow)
    val border = panel.border()
    val clipped = if (panel.radiusDp != null || panel.background != null) modifier.clip(shape) else modifier
    Box(clipped.appSkinPanelBackground(panel, MaterialTheme.colorScheme.surfaceContainerLow, ink, shape)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .padding(horizontal = (panel.paddingHorizontalDp ?: 0f).dp, vertical = (panel.paddingVerticalDp ?: 0f).dp)) {
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

@Composable
fun AppSkinPanelSurface(panel: AppSkinPanel, modifier: Modifier = Modifier, defaultShape: Shape = RectangleShape,
    defaultColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    defaultInk: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit) {
    val shape = panel.shape(defaultShape)
    val ink = panel.ink(defaultInk, defaultColor)
    val decoration = modifier.appSkinPanelBackground(panel, defaultColor, ink, shape)
    val color = if (panel.background == null) defaultColor else Color.Transparent
    if (onClick == null) Surface(decoration, shape = shape, color = color, contentColor = ink, border = panel.border(), content = content)
    else Surface(onClick, decoration, shape = shape, color = color, contentColor = ink, border = panel.border(), content = content)
}
