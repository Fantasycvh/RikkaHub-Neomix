package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * M3 Expressive polygon morph 的通用 Shape 封装，与 [AppLogo] 内的 MorphShape 同构
 * （同样的 toPath + Matrix 组合，仅去掉自转、按组件尺寸缩放）。
 * progress 0 = start、1 = end，由调用方用 animateFloatAsState + MotionScheme 驱动；
 * 消费点：ChatInput 发送↔停止按钮的形态过渡。
 */
@Composable
fun rememberMorphShape(start: RoundedPolygon, end: RoundedPolygon, progress: Float): Shape {
    val morph = remember(start, end) { Morph(start, end) }
    return remember(morph, progress) { PolygonMorphShape(morph, progress) }
}

private class PolygonMorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress = progress)
        val matrix = Matrix().apply {
            translate(size.width / 2f, size.height / 2f)
            translate(-size.width / 2f, -size.height / 2f)
            scale(size.width, size.height)
        }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
