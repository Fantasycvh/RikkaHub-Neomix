package app.androidrik.rikkahub.ui.theme

import androidx.compose.ui.graphics.Brush
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.GradientDirection

fun AppearanceGradient.toBrush(): Brush {
    val colors = normalized().colors.map { requireNotNull(it.toComposeColorOrNull()) }
    return when (direction) {
        GradientDirection.HORIZONTAL -> Brush.horizontalGradient(colors)
        GradientDirection.VERTICAL -> Brush.verticalGradient(colors)
        GradientDirection.DIAGONAL -> Brush.linearGradient(colors)
    }
}
