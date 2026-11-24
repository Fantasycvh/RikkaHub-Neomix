package app.androidrik.rikkahub.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.GradientDirection

fun AppearanceGradient.toBrush(): Brush {
    val colors = normalized().colors.map { requireNotNull(it.toComposeColorOrNull()) }
    return direction.toBrush(colors)
}

internal fun GradientDirection.toBrush(colors: List<Color>): Brush = when (this) {
    GradientDirection.HORIZONTAL -> Brush.horizontalGradient(colors)
    GradientDirection.VERTICAL -> Brush.verticalGradient(colors)
    GradientDirection.DIAGONAL -> Brush.linearGradient(colors)
}
