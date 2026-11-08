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
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.ui.components.haze.LocalBubbleBackdrop
import app.androidrik.rikkahub.ui.components.haze.BubbleGlassBackend
import app.androidrik.rikkahub.ui.components.haze.bubbleGlassBackend
import app.androidrik.rikkahub.ui.components.haze.bubbleGlassEffect
import app.androidrik.rikkahub.ui.components.haze.recoverableBlurEnabled
import app.androidrik.rikkahub.ui.theme.adaptiveContentColor
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull

/** Shared bubble host for chat and image export. Material rendering is isolated from message content. */
@Composable
fun ChatBubbleSurface(
    style: ChatBubble,
    defaultColor: Color,
    modifier: Modifier = Modifier,
    defaultContentColor: Color = MaterialTheme.colorScheme.onSurface,
    padWhenHidden: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val bubble = style.normalized()
    val override = bubble.colorHex.toComposeColorOrNull()
    val shape = RoundedCornerShape(bubble.geometry.radiusDp.dp)
    val contentColor = when {
        !bubble.visible -> MaterialTheme.colorScheme.onSurface
        bubble.material == BubbleMaterial.GRADIENT -> adaptiveContentColor(
            bubble.gradient.colors[bubble.gradient.colors.size / 2].toComposeColorOrNull() ?: defaultColor)
        override != null -> adaptiveContentColor(override)
        else -> defaultContentColor
    }
    val backdrop = LocalBubbleBackdrop.current
    val liveGlass = bubble.visible && bubble.opacity > 0f && bubble.material == BubbleMaterial.GLASS && backdrop != null &&
        bubbleGlassBackend(Build.VERSION.SDK_INT, recoverableBlurEnabled(backdrop)) != BubbleGlassBackend.STATIC
    val glass = if (liveGlass) Modifier.bubbleGlassEffect(requireNotNull(backdrop), override ?: defaultColor, bubble.opacity, shape) else Modifier
    val background = modifier.clip(shape).then(glass)
        .bubbleMaterialBackground(bubble, override ?: defaultColor, contentColor, liveGlass)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(if (bubble.visible || padWhenHidden) bubble.geometry.paddingDp.dp else 0.dp)) {
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
