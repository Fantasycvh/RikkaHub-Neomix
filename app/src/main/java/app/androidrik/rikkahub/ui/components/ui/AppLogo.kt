package app.androidrik.rikkahub.ui.components.ui

import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastForEach
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.Morph
import app.androidrik.rikkahub.R
import kotlin.math.floor
import kotlin.math.roundToInt

/** 应用图标的各层：直接画图层，避免系统图标蒙版和外面的轮廓叠在一起。 */
@Composable
fun rememberAppLogoLayers(): List<Drawable> {
    val context = LocalContext.current
    return remember(context) {
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        if (icon is AdaptiveIconDrawable) listOfNotNull(icon.background, icon.foreground) else listOfNotNull(icon)
    }
}

/** 把 logo 铺满当前区域，轮廓由外面的 clip 决定。 */
fun Modifier.drawAppLogo(layers: List<Drawable>): Modifier = drawBehind {
    drawIntoCanvas { canvas ->
        layers.fastForEach { layer ->
            layer.bounds = logoLayerBounds(size)
            layer.draw(canvas.nativeCanvas)
        }
    }
}

/** 整数位置是完整形状；每段转 90 度，[extraRotation] 可叠加加载时的整体自转。 */
fun logoMorphShape(position: Float, extraRotation: Float = 0f): Shape {
    val segment = floor(position).toInt()
    return MorphShape(
        morph = LogoMorphs[segment.mod(LogoMorphs.size)],
        progress = position - segment,
        rotation = position * 90f + extraRotation,
    )
}

// 自适应图标的图层是 108dp，可见的只有中间 72dp，四周各多出四分之一。
private fun logoLayerBounds(size: Size): Rect {
    val insetX = (size.width / 4f).roundToInt()
    val insetY = (size.height / 4f).roundToInt()
    return Rect(-insetX, -insetY, size.width.roundToInt() + insetX, size.height.roundToInt() + insetY)
}

private val LogoShapes = listOf(
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Flower,
    MaterialShapes.Cookie4Sided,
)
private val LogoMorphs = LogoShapes.indices.map { index ->
    Morph(LogoShapes[index], LogoShapes[(index + 1) % LogoShapes.size])
}

private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val rotation: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress = progress)
        val matrix = Matrix().apply {
            translate(size.width / 2f, size.height / 2f)
            rotateZ(rotation)
            translate(-size.width / 2f, -size.height / 2f)
            scale(size.width, size.height)
        }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
