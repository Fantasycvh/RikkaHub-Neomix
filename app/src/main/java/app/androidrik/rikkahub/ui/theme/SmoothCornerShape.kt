package app.androidrik.rikkahub.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * iOS 式连续曲率圆角（squircle）：每角一段 cubic，曲率在切点处连续过渡。
 * 平滑度 0 = 圆形圆角（控制距 0.4477r 的圆弧近似），越大越「方」：切点沿边外扩、角部收紧。
 * 走 CornerBasedShape 扩展点（createOutline(size, 四角px) 是 abstract），因此可进 material3 Shapes。
 * lerp 退化为圆弧圆角插值（形状动画期间丢平滑，落位恢复）；首轮只供卡片/容器层，列表项暂不消费。
 */
class SmoothCornerShape : CornerBasedShape {
    /** 0..1，默认 0.6（iOS 图标观感）。 */
    val smoothing: Float

    constructor(radius: Dp, smoothing: Float = 0.6f) : super(
        CornerSize(radius), CornerSize(radius), CornerSize(radius), CornerSize(radius),
    ) {
        this.smoothing = smoothing
    }

    private constructor(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
        smoothing: Float,
    ) : super(topStart, topEnd, bottomEnd, bottomStart) {
        this.smoothing = smoothing
    }

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val radii = floatArrayOf(topStart, topEnd, bottomEnd, bottomStart)
        if (radii.all { it <= 0f }) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val s = smoothing.coerceIn(0f, 1f)
        // 切点外扩 (1+s) 倍，四角不重叠的前提
        val maxR = min(size.width, size.height) / 2f / (1f + s)
        val path = Path()
        var first = true
        // 顺时针四角；from/to = 从顶点出发的两条边方向
        val corners = listOf(
            CornerDef(Offset(0f, 0f), from = Offset(0f, 1f), to = Offset(1f, 0f), radius = radii[0]), // 左上
            CornerDef(Offset(size.width, 0f), from = Offset(-1f, 0f), to = Offset(0f, 1f), radius = radii[1]), // 右上
            CornerDef(Offset(size.width, size.height), from = Offset(0f, -1f), to = Offset(-1f, 0f), radius = radii[2]), // 右下
            CornerDef(Offset(0f, size.height), from = Offset(1f, 0f), to = Offset(0f, -1f), radius = radii[3]), // 左下
        )
        corners.forEachIndexed { index, def ->
            val r = def.radius.coerceIn(0f, maxR)
            if (r <= 0f) {
                // 直角：落在顶点上，沿边走到下一角
                if (first) path.moveTo(def.vertex.x, def.vertex.y) else path.lineTo(def.vertex.x, def.vertex.y)
                path.lineTo(def.vertex.x, def.vertex.y)
                return@forEachIndexed
            }
            val a = r * (1f + s)
            val c = r * 0.4477f * (1f - 0.55f * s)
            val p0 = def.vertex + def.from * a
            val c1 = def.vertex + def.from * c
            val c2 = def.vertex + def.to * c
            val p3 = def.vertex + def.to * a
            if (first) path.moveTo(p0.x, p0.y) else path.lineTo(p0.x, p0.y)
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, p3.x, p3.y)
            first = false
        }
        path.close()
        return Outline.Generic(path)
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = SmoothCornerShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    // 此版 compose 的 Interpolatable 经 raw 泛型擦除，可覆写签名是 (Any?, Float)；退化为圆弧圆角插值
    override fun lerp(other: Any?, t: Float): Any? =
        RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart).lerp(other, t)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmoothCornerShape) return false
        return topStart == other.topStart && topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd && bottomStart == other.bottomStart &&
            smoothing == other.smoothing
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + smoothing.hashCode()
        return result
    }

    override fun toString(): String =
        "SmoothCornerShape(topStart = $topStart, topEnd = $topEnd, bottomEnd = $bottomEnd, " +
            "bottomStart = $bottomStart, smoothing = $smoothing)"

    private data class CornerDef(val vertex: Offset, val from: Offset, val to: Offset, val radius: Float)
}
