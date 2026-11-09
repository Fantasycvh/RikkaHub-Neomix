package app.androidrik.rikkahub.ui.components.message

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import app.androidrik.rikkahub.data.files.BubbleTextureStore
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Export pins decoded assets before capture, rather than racing asynchronous composition-time loading. */
val LocalBubbleTextureBitmaps = compositionLocalOf<Map<String, Bitmap>> { emptyMap() }

@Composable
internal fun rememberBubbleTexture(id: String?): Bitmap? {
    if (id == null) return null
    LocalBubbleTextureBitmaps.current[id]?.let { return it }
    val context = LocalContext.current.applicationContext
    val store = remember(context) { BubbleTextureStore(context) }
    val bitmap by produceState(store.cached(id), id, context) {
        value = withContext(Dispatchers.IO) { store.load(id) }
    }
    return bitmap
}

@Composable
internal fun Modifier.bubbleLayerBackground(
    bubble: ChatBubble, base: Color, ink: Color, liveGlass: Boolean, coordinates: BubbleCoordinateNode?,
): Modifier {
    val stack = bubble.effectiveStack()
    val density = LocalDensity.current.density
    val layers = stack.layers.filter { it.enabled }
    val painters = layers.map { layer -> key(layer.id) {
        val texture = rememberBubbleTexture(layer.texture.assetId.takeIf { layer.kind == BubbleLayerKind.TEXTURE })
        remember(layer, density, base, ink, texture) { BubbleLayerPainter(layer, density, base, ink, texture) }
    } }
    return drawWithCache {
        val groupPaint = Paint().apply { alpha = (bubble.opacity * 255).toInt() }
        val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.White.copy(alpha = 0.08f)))
        onDrawBehind {
            if (!bubble.visible || bubble.opacity <= 0f) return@onDrawBehind
            if (stack.glass) {
                if (!liveGlass) drawRect(base.copy(alpha = bubble.opacity * 0.35f))
                drawRect(sheen, alpha = bubble.opacity)
            }
            val offset = coordinates?.offset() ?: Offset.Zero
            val bounds = coordinates?.space?.bounds(size) ?: size
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                // Isolate layer blends from wallpaper/text. Only allocate for the visible clip of long messages.
                val isolated = bubble.opacity < 1f || layers.any { it.blend != BubbleBlend.NORMAL }
                val clip = canvas.clipBounds
                val save = if (isolated) canvas.saveLayer(RectF(clip), groupPaint) else canvas.save()
                try {
                    var painted = false
                    painters.forEach { painter -> if (painter.draw(canvas, size, bounds, offset, !painted)) painted = true }
                } finally { canvas.restoreToCount(save) }
            }
        }
    }
}

private class BubbleLayerPainter(
    val layer: BubbleLayer, val density: Float, val base: Color, ink: Color, val bitmap: Bitmap?,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var lastBounds = Size.Unspecified
    private var gradient: Shader? = null
    private val texture = bitmap?.let { BitmapShader(it,
        if (layer.texture.mode == BubbleTextureMode.COVER) Shader.TileMode.CLAMP else if (layer.texture.mode == BubbleTextureMode.MIRROR) Shader.TileMode.MIRROR else Shader.TileMode.REPEAT,
        if (layer.texture.mode == BubbleTextureMode.COVER) Shader.TileMode.CLAMP else if (layer.texture.mode == BubbleTextureMode.MIRROR) Shader.TileMode.MIRROR else Shader.TileMode.REPEAT) }
    private val pattern = if (layer.kind == BubbleLayerKind.PATTERN) patternBrush(layer.pattern, density, ink).createShader(Size(1f, 1f)) else null
    private val noise = if (layer.kind == BubbleLayerKind.NOISE) noiseBrush(layer.noise).createShader(Size(1f, 1f)) else null
    private val blend = PorterDuffXfermode(when (layer.blend) {
        BubbleBlend.NORMAL -> PorterDuff.Mode.SRC_OVER
        BubbleBlend.MULTIPLY -> PorterDuff.Mode.MULTIPLY
        BubbleBlend.SCREEN -> PorterDuff.Mode.SCREEN
        BubbleBlend.OVERLAY -> PorterDuff.Mode.OVERLAY
    })
    fun draw(canvas: android.graphics.Canvas, size: Size, bounds: Size, offset: Offset, first: Boolean): Boolean {
        if (layer.opacity <= 0f) return false
        paint.shader = null
        paint.xfermode = if (first) null else blend
        paint.color = (layer.colorHex.toComposeColorOrNull() ?: base).toArgb()
        var alpha = layer.opacity
        when (layer.kind) {
            BubbleLayerKind.SOLID -> Unit
            BubbleLayerKind.GRADIENT -> {
                if (gradient == null || lastBounds != bounds) {
                    lastBounds = bounds
                    val endX = if (layer.gradient.direction == GradientDirection.VERTICAL) 0f else bounds.width.coerceAtLeast(1f)
                    val endY = if (layer.gradient.direction == GradientDirection.HORIZONTAL) 0f else bounds.height.coerceAtLeast(1f)
                    gradient = LinearGradient(0f, 0f, endX, endY,
                        layer.gradient.colors.map { requireNotNull(it.toComposeColorOrNull()).toArgb() }.toIntArray(), null, Shader.TileMode.CLAMP)
                }
                paint.shader = gradient
            }
            BubbleLayerKind.PATTERN -> { paint.shader = pattern; alpha *= layer.pattern.opacity }
            BubbleLayerKind.NOISE -> { paint.shader = noise; alpha *= layer.noise.strength }
            BubbleLayerKind.TEXTURE -> {
                val image = bitmap ?: return false // Missing assets skip their layer, preserving the remaining material.
                val scale = if (layer.texture.mode == BubbleTextureMode.COVER)
                    max(bounds.width / image.width, bounds.height / image.height)
                else layer.texture.sizeDp * density / image.width
                matrix.setScale(scale, scale)
                val x = if (layer.texture.mode == BubbleTextureMode.COVER) (bounds.width - image.width * scale) / 2 else 0f
                val y = if (layer.texture.mode == BubbleTextureMode.COVER) (bounds.height - image.height * scale) / 2 else 0f
                matrix.postTranslate(x + layer.texture.offsetXDp * density - offset.x,
                    y + layer.texture.offsetYDp * density - offset.y)
                texture?.setLocalMatrix(matrix)
                paint.shader = texture
                paint.isFilterBitmap = true
            }
        }
        if (layer.kind != BubbleLayerKind.TEXTURE) {
            matrix.setTranslate(-offset.x, -offset.y)
            paint.shader?.setLocalMatrix(matrix)
        }
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        if (paint.alpha == 0) return false
        canvas.drawRect(0f, 0f, size.width, size.height, paint)
        return true
    }
}
