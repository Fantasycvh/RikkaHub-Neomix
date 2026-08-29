package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.min

/**
 * 高度哨兵：防「坏元素无限高度」导致布局崩溃。
 *
 * 只兜异常值，不限制正常内容：子项测量结果 ≤ [normalLimit] 时原样放行
 * （多长的消息都完整渲染、可滚动）；仅当测量结果异常巨大（趋近无限，
 * 典型为坏图/未闭合 SVG/异常 tool UI 内部返回 Int.MAX）时 clamp 到
 * [normalLimit]，杜绝 `Can't represent height of 2147483647`。
 *
 * 旧实现（固定 maxHeight=10000dp 传给子项测量）会把超过约 20 屏的
 * 正常长消息/思考内容静默裁剪——"较长思考内容截断"的根因。
 */
@Composable
fun HeightClamp(
    normalLimit: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val limitPx = with(LocalDensity.current) { normalLimit.roundToPx() }
    Layout(
        modifier = modifier.clipToBounds(),
        content = content,
    ) { measurables, constraints ->
        // 给子项的测量约束仍传外层约束（可能无限），让正常内容按需伸展
        val placeable = measurables.first().measure(constraints)
        val h = if (placeable.height >= limitPx) min(placeable.height, limitPx) else placeable.height
        layout(placeable.width, h) {
            placeable.place(0, 0)
        }
    }
}