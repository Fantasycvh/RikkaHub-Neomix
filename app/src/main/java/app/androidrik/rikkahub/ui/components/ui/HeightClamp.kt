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
 * 在 measure 阶段给子项有限 maxHeight，Compose 会把子项测量结果 clamp 到该上限——
 * 即使某个元素（坏图 / 未闭合 SVG / TikZ 块 / 异常 tool UI）内部返回无限高度，
 * 也不会以 Int.MAX 进入布局，杜绝 `Can't represent height of 2147483647`。
 * 超出部分裁剪（配合内容截断/提示展示前段）。
 */
@Composable
fun HeightClamp(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val maxHeightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
    Layout(
        modifier = modifier.clipToBounds(),
        content = content,
    ) { measurables, constraints ->
        val hMax = maxOf(1, minOf(constraints.maxHeight, maxHeightPx))
        val placeable = measurables.first().measure(constraints.copy(maxHeight = hMax))
        val h = min(placeable.height, hMax)
        layout(placeable.width, h) {
            placeable.place(0, 0)
        }
    }
}
