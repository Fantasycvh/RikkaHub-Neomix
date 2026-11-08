package app.androidrik.rikkahub.ui.components.message

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.BubbleNoise
import app.androidrik.rikkahub.data.model.BubblePattern
import app.androidrik.rikkahub.data.model.BubblePatternKind
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.ui.theme.toBrush
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/** Repeating tiles are bounded and cached; tall streaming messages do not allocate per-pixel canvases. */
@Composable
internal fun Modifier.bubbleMaterialBackground(bubble: ChatBubble, base: Color, ink: Color, liveGlass: Boolean = false): Modifier {
    val density = LocalDensity.current.density
    val pattern = if (bubble.material == BubbleMaterial.PATTERN && bubble.visible) {
        remember(bubble.pattern, density, ink) { patternBrush(bubble.pattern, density, ink) }
    } else null
    val noise = if (bubble.material == BubbleMaterial.NOISE && bubble.visible) {
        remember(bubble.noise.seed) { noiseBrush(bubble.noise) }
    } else null
    val gradient = remember(bubble.gradient) { bubble.gradient.toBrush() }
    return drawWithCache {
        onDrawBehind {
            if (bubble.visible) {
                if (bubble.material == BubbleMaterial.GRADIENT) {
                    drawRect(brush = gradient, alpha = bubble.opacity)
                } else if (bubble.material == BubbleMaterial.GLASS) {
                    if (!liveGlass) {
                        drawRect(base.copy(alpha = bubble.opacity * 0.35f))
                    }
                    drawRect(brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.White.copy(alpha = 0.08f))),
                        alpha = bubble.opacity)
                } else {
                    drawRect(base.copy(alpha = bubble.opacity))
                    pattern?.let { drawRect(brush = it, alpha = bubble.pattern.opacity * bubble.opacity) }
                    noise?.let { drawRect(brush = it, alpha = bubble.noise.strength * bubble.opacity) }
                }
            }
        }
    }
}

private fun patternBrush(pattern: BubblePattern, density: Float, fallback: Color): ShaderBrush {
    val side = (pattern.spacingDp * density).roundToInt().coerceIn(4, 256)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (pattern.colorHex.toComposeColorOrNull() ?: fallback).toArgb()
        strokeWidth = density.coerceAtLeast(1f)
    }
    val edge = side.toFloat()
    when (pattern.kind) {
        BubblePatternKind.DOTS -> canvas.drawCircle(edge / 2, edge / 2, (edge * 0.09f).coerceAtLeast(1f), paint)
        BubblePatternKind.GRID -> {
            canvas.drawLine(0f, 0f, edge, 0f, paint)
            canvas.drawLine(0f, 0f, 0f, edge, paint)
        }
        BubblePatternKind.STRIPES -> {
            canvas.drawLine(0f, edge, edge, 0f, paint)
            canvas.drawLine(-edge / 2, edge / 2, edge / 2, -edge / 2, paint)
            canvas.drawLine(edge / 2, edge * 1.5f, edge * 1.5f, edge / 2, paint)
        }
    }
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private fun noiseBrush(noise: BubbleNoise): ShaderBrush {
    val random = Random(noise.seed)
    val pixels = IntArray(64 * 64) {
        val value = if (random.nextBoolean()) 0x00FFFFFF else 0
        (random.nextInt(32, 256) shl 24) or value
    }
    val bitmap = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
