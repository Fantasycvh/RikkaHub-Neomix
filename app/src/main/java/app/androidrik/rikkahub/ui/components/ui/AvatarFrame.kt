package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.AvatarFrameStyle

/**
 * 聊天页头像装饰框（轻量个性化，橘瓣灵感；design-light-personalization.md 决策 3）。
 * 纯 Canvas 预设绘制、随主题取色，零图片资源。聊天页头像是圆形（`rememberAvatarShape`
 * 常态 CircleShape），故环用 drawCircle；内容侧收缩 [contentInset] 给环留位。
 */

/** 头像内容为框留出的收缩量（外圈描边不越界）。 */
fun AvatarFrameStyle.contentInset(): Dp = when (this) {
    AvatarFrameStyle.NONE -> 0.dp
    AvatarFrameStyle.RING -> 2.dp
    AvatarFrameStyle.GRADIENT -> 2.dp
    AvatarFrameStyle.DOUBLE -> 4.dp
    AvatarFrameStyle.DASHED -> 2.dp
    AvatarFrameStyle.GLOW -> 4.dp
}

/**
 * 在当前 DrawScope 边界内画圆环框。宿主需先收缩 [contentInset]；描边半径按
 * 「半径 - 描边宽/2」内收，保证描边不越出宿主边界。
 */
fun DrawScope.drawAvatarFrame(
    style: AvatarFrameStyle,
    primary: Color,
    tertiary: Color,
) {
    if (style == AvatarFrameStyle.NONE) return
    val inset = style.contentInset().toPx()
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxRadius = size.minDimension / 2f
    fun ring(stroke: Float, color: Color, radiusInset: Float = 0f, brush: Brush? = null, dash: PathEffect? = null) {
        val radius = maxRadius - inset - radiusInset - stroke / 2f
        if (radius <= 0f) return
        val style = Stroke(width = stroke, pathEffect = dash)
        if (brush != null) {
            drawCircle(brush = brush, radius = radius, center = center, style = style)
        } else {
            drawCircle(color = color, radius = radius, center = center, style = style)
        }
    }
    when (style) {
        AvatarFrameStyle.NONE -> Unit
        AvatarFrameStyle.RING -> ring(2.dp.toPx(), primary)
        AvatarFrameStyle.GRADIENT -> ring(
            2.5.dp.toPx(), Color.Transparent,
            brush = Brush.sweepGradient(listOf(primary, tertiary, primary), center = center),
        )
        AvatarFrameStyle.DOUBLE -> {
            ring(1.5f.dp.toPx(), primary)
            ring(1.dp.toPx(), tertiary, radiusInset = 2.5f.dp.toPx())
        }
        AvatarFrameStyle.DASHED -> ring(
            1.75f.dp.toPx(), primary,
            dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        )
        AvatarFrameStyle.GLOW -> {
            // 三层递缩描边模拟柔光（宽→窄、alpha 低→高）
            ring(6.dp.toPx(), primary.copy(alpha = 0.15f))
            ring(4.dp.toPx(), primary.copy(alpha = 0.30f))
            ring(2.dp.toPx(), primary.copy(alpha = 0.60f))
        }
    }
}

/** 头像框叠加层：铺满宿主 Box（`matchParentSize`），把框画在内容之上、不挡点击。 */
@Composable
fun AvatarFrameOverlay(
    style: AvatarFrameStyle,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    if (style == AvatarFrameStyle.NONE) return
    Canvas(modifier = modifier) {
        drawAvatarFrame(style, primary, tertiary)
    }
}

/**
 * 带装饰框的头像容器：内容收缩 [AvatarFrameStyle.contentInset] 后铺满，框画在边距里。
 * `style == NONE` 时与普通 Box 等价。
 */
@Composable
fun AvatarFrameBox(
    style: AvatarFrameStyle,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(modifier = Modifier.matchParentSize().padding(style.contentInset()), content = content)
        AvatarFrameOverlay(style, primary, tertiary, modifier = Modifier.matchParentSize())
    }
}
