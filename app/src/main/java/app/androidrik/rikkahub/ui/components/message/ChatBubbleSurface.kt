package app.androidrik.rikkahub.ui.components.message

import android.os.Build

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.BubbleLayerKind
import app.androidrik.rikkahub.data.model.effectiveStack
import app.androidrik.rikkahub.ui.components.haze.LocalBubbleBackdrop
import app.androidrik.rikkahub.ui.components.haze.BubbleGlassBackend
import app.androidrik.rikkahub.ui.components.haze.bubbleGlassBackend
import app.androidrik.rikkahub.ui.components.haze.bubbleGlassEffect
import app.androidrik.rikkahub.ui.components.haze.recoverableBlurEnabled
import app.androidrik.rikkahub.ui.theme.adaptiveContentColor
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull

/** Shared bubble host for chat and image export. Material rendering is isolated from message content. */
data class BubbleSegment(val first: Boolean, val last: Boolean, val spacing: Dp,
    val group: Any? = null, val unitId: Any? = null)

@Composable
fun ChatBubbleSurface(
    style: ChatBubble,
    defaultColor: Color,
    modifier: Modifier = Modifier,
    defaultContentColor: Color = MaterialTheme.colorScheme.onSurface,
    padWhenHidden: Boolean = false,
    onClick: (() -> Unit)? = null,
    segment: BubbleSegment? = null,
    content: @Composable () -> Unit,
) {
    val bubble = style.normalized()
    val override = bubble.colorHex.toComposeColorOrNull()
    val stack = bubble.stack?.normalized()
    val fill = stack?.layers?.lastOrNull { it.enabled && it.opacity >= 0.5f &&
        (it.kind == BubbleLayerKind.SOLID || it.kind == BubbleLayerKind.GRADIENT) }
    val radius = bubble.geometry.radiusDp.dp
    val shape = if (segment == null) RoundedCornerShape(radius) else RoundedCornerShape(
        topStart = if (segment.first) radius else 0.dp, topEnd = if (segment.first) radius else 0.dp,
        bottomStart = if (segment.last) radius else 0.dp, bottomEnd = if (segment.last) radius else 0.dp,
    )
    val contentColor = when {
        !bubble.visible -> MaterialTheme.colorScheme.onSurface
        fill?.kind == BubbleLayerKind.GRADIENT -> adaptiveContentColor(
            fill.gradient.colors[fill.gradient.colors.size / 2].toComposeColorOrNull() ?: defaultColor)
        fill?.colorHex != null -> adaptiveContentColor(fill.colorHex.toComposeColorOrNull() ?: defaultColor)
        stack != null -> override?.let { adaptiveContentColor(it) } ?: defaultContentColor
        bubble.material == BubbleMaterial.GRADIENT -> adaptiveContentColor(
            bubble.gradient.colors[bubble.gradient.colors.size / 2].toComposeColorOrNull() ?: defaultColor)
        override != null -> adaptiveContentColor(override)
        else -> defaultContentColor
    }
    val backdrop = LocalBubbleBackdrop.current
    val liveGlass = bubble.visible && bubble.opacity > 0f && bubble.effectiveStack().glass && backdrop != null &&
        bubbleGlassBackend(Build.VERSION.SDK_INT, recoverableBlurEnabled(backdrop)) != BubbleGlassBackend.STATIC
    val glass = if (liveGlass) Modifier.bubbleGlassEffect(requireNotNull(backdrop), override ?: defaultColor, bubble.opacity, shape) else Modifier
    val (coordinateModifier, coordinates) = rememberBubbleCoordinates(segment.takeIf { stack != null })
    val background = if (stack == null) modifier.clip(shape).then(glass)
        .bubbleMaterialBackground(bubble, override ?: defaultColor, contentColor, liveGlass)
    else modifier.then(coordinateModifier).clip(shape).then(glass)
        .bubbleLayerBackground(bubble, override ?: defaultColor, contentColor, liveGlass, coordinates)
    val body: @Composable () -> Unit = {
        val padding = if (bubble.visible || padWhenHidden) bubble.geometry.paddingDp.dp else 0.dp
        Column(if (segment == null) Modifier.padding(padding) else Modifier.padding(
            start = padding, end = padding, top = if (segment.first) padding else segment.spacing,
            bottom = if (segment.last) padding else 0.dp,
        )) {
            content()
        }
    }
    if (onClick == null) {
        Surface(modifier = background, shape = shape,
            color = Color.Transparent, contentColor = contentColor, content = body)
    } else {
        Surface(onClick = onClick, modifier = background, shape = shape,
            color = Color.Transparent, contentColor = contentColor, content = body)
    }
}
