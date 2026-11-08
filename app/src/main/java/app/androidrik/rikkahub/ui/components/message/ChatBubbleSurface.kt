package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.ChatBubble
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
    val color = if (bubble.visible) (override ?: defaultColor).copy(alpha = bubble.opacity) else Color.Transparent
    val contentColor = when {
        !bubble.visible -> MaterialTheme.colorScheme.onSurface
        override != null -> adaptiveContentColor(override)
        else -> defaultContentColor
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(if (bubble.visible || padWhenHidden) bubble.geometry.paddingDp.dp else 0.dp)) {
            content()
        }
    }
    if (onClick == null) {
        Surface(modifier = modifier, shape = RoundedCornerShape(bubble.geometry.radiusDp.dp),
            color = color, contentColor = contentColor, content = body)
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(bubble.geometry.radiusDp.dp),
            color = color, contentColor = contentColor, content = body)
    }
}
