@file:Suppress("unused")

package app.androidrik.rikkahub.ui.components.table

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.context.LocalSettings
import kotlin.math.max

private class TableGridMetrics {
    var colWidths: IntArray = IntArray(0)
    var headerHeight: Int = 0
    var rowHeights: IntArray = IntArray(0)
    var tableWidth: Int = 0
    var tableHeight: Int = 0
}

/**
 * 计算表格最终列宽：先夹紧到 `[minWidths, maxWidths]`，内容窄于视口时按比例拉伸铺满。
 */
internal fun distributeColumnWidths(
    naturalWidths: IntArray,
    minWidths: IntArray,
    maxWidths: IntArray,
    viewportMaxWidth: Int,
    stretchToFillWidth: Boolean,
): IntArray {
    val count = naturalWidths.size
    if (count == 0) return IntArray(0)
    val result = IntArray(count) { c ->
        val minW = minWidths.getOrElse(c) { 0 }.coerceAtLeast(0)
        val maxW = maxWidths.getOrElse(c) { Int.MAX_VALUE }.coerceAtLeast(minW)
        naturalWidths[c].coerceIn(minW, maxW)
    }
    val naturalTotal = result.sum()
    if (stretchToFillWidth &&
        viewportMaxWidth != Constraints.Infinity &&
        viewportMaxWidth > naturalTotal &&
        naturalTotal > 0
    ) {
        val extra = viewportMaxWidth - naturalTotal
        var distributed = 0
        for (c in 0 until count) {
            val add = if (c == count - 1) {
                extra - distributed
            } else {
                (extra.toLong() * result[c] / naturalTotal).toInt()
            }
            result[c] += add
            distributed += add
        }
    }
    return result
}

/**
 * DataTable（单次测量布局 + 横向滚动 + 行内等高）
 * - 单阶段 SubcomposeLayout 测量，网格背景与边框在 drawBehind 一次绘制，避免双倍组合节点与重复测量
 * - 高度自适应内容（不提供纵向滚动）
 * - 宽度可超出视口，外层内置 horizontalScroll
 */
@Composable
fun DataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 4.dp,
    cellBorder: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    headerBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    zebraStriping: Boolean = false,
    columnMinWidths: List<Dp> = emptyList(),
    columnMaxWidths: List<Dp> = emptyList(),
    columnAlignments: List<Alignment> = emptyList(),
    cellAlignment: Alignment = Alignment.CenterStart,
    outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shape: Shape = MaterialTheme.shapes.small,
    stretchToFillWidth: Boolean = true,
) {
    val hScroll = rememberScrollState()
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val gridMetrics = remember { TableGridMetrics() }

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .then(
                if (outerBorder != null) Modifier.border(outerBorder, shape) else Modifier
            )
    ) {
        // 捕获滚动视口的可用宽度，用于在内容较窄时把列宽拉伸铺满
        val viewportMaxWidth = constraints.maxWidth

        Box(modifier = Modifier.horizontalScroll(hScroll)) {
            SubcomposeLayout(
                modifier = Modifier.drawBehind {
                    val colWidths = gridMetrics.colWidths
                    val headerHeight = gridMetrics.headerHeight
                    val rowHeights = gridMetrics.rowHeights
                    val tableWidth = gridMetrics.tableWidth
                    val tableHeight = gridMetrics.tableHeight
                    if (colWidths.isEmpty() || tableWidth <= 0 || tableHeight <= 0) return@drawBehind

                    if (headerHeight > 0 && headerBackground != Color.Transparent) {
                        drawRect(
                            color = headerBackground,
                            topLeft = Offset.Zero,
                            size = Size(tableWidth.toFloat(), headerHeight.toFloat()),
                        )
                    }

                    if (zebraStriping && surfaceContainer != Color.Transparent) {
                        var y = headerHeight.toFloat()
                        for (r in rowHeights.indices) {
                            val rh = rowHeights[r].toFloat()
                            if (r % 2 == 1 && rh > 0f) {
                                drawRect(
                                    color = surfaceContainer,
                                    topLeft = Offset(0f, y),
                                    size = Size(tableWidth.toFloat(), rh),
                                )
                            }
                            y += rh
                        }
                    }

                    if (cellBorder != null) {
                        val stroke = cellBorder.width.toPx().coerceAtLeast(1f)
                        val half = stroke / 2f
                        val maxW = tableWidth.toFloat()
                        val maxH = tableHeight.toFloat()

                        var x = 0f
                        for (c in 0..colWidths.size) {
                            val clampedX = when (c) {
                                0 -> half
                                colWidths.size -> (maxW - half).coerceAtLeast(half)
                                else -> x
                            }
                            drawLine(
                                brush = cellBorder.brush,
                                start = Offset(clampedX, 0f),
                                end = Offset(clampedX, maxH),
                                strokeWidth = stroke,
                            )
                            if (c < colWidths.size) x += colWidths[c]
                        }

                        drawLine(
                            brush = cellBorder.brush,
                            start = Offset(0f, half),
                            end = Offset(maxW, half),
                            strokeWidth = stroke,
                        )
                        var y = headerHeight.toFloat()
                        if (headerHeight > 0) {
                            drawLine(
                                brush = cellBorder.brush,
                                start = Offset(0f, y),
                                end = Offset(maxW, y),
                                strokeWidth = stroke,
                            )
                        }
                        for (r in rowHeights.indices) {
                            y += rowHeights[r]
                            val clampedY = if (r == rowHeights.lastIndex) (maxH - half).coerceAtLeast(half) else y
                            drawLine(
                                brush = cellBorder.brush,
                                start = Offset(0f, clampedY),
                                end = Offset(maxW, clampedY),
                                strokeWidth = stroke,
                            )
                        }
                    }
                }
            ) { layoutConstraints ->
                val columnCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
                val rowCount = rows.size
                if (columnCount == 0) {
                    gridMetrics.colWidths = IntArray(0)
                    gridMetrics.headerHeight = 0
                    gridMetrics.rowHeights = IntArray(0)
                    gridMetrics.tableWidth = 0
                    gridMetrics.tableHeight = 0
                    return@SubcomposeLayout layout(0, 0) {}
                }

                val infinity = Constraints.Infinity
                val unbounded = Constraints(0, infinity, 0, infinity)
                val minWidthsPx = IntArray(columnCount) { i -> columnMinWidths.getOrNull(i)?.roundToPx() ?: 0 }
                val maxWidthsPx = IntArray(columnCount) { i -> columnMaxWidths.getOrNull(i)?.roundToPx() ?: Int.MAX_VALUE }
                val naturalWidths = IntArray(columnCount)

                val headerPlaceables = Array(columnCount) { c ->
                    val measurables = subcompose("h_$c") {
                        Box(modifier = Modifier.padding(cellPadding)) {
                            headers.getOrNull(c)?.invoke()
                        }
                    }
                    val cellConstraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                        Constraints(0, maxWidthsPx[c], 0, infinity)
                    } else {
                        unbounded
                    }
                    measurables.first().measure(cellConstraints).also { p ->
                        naturalWidths[c] = max(naturalWidths[c], p.width)
                    }
                }

                val bodyPlaceables = Array(rowCount * columnCount) { i ->
                    val r = i / columnCount
                    val c = i % columnCount
                    val measurables = subcompose("b_${r}_$c") {
                        Box(modifier = Modifier.padding(cellPadding)) {
                            rows[r].getOrNull(c)?.invoke()
                        }
                    }
                    val cellConstraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                        Constraints(0, maxWidthsPx[c], 0, infinity)
                    } else {
                        unbounded
                    }
                    measurables.first().measure(cellConstraints).also { p ->
                        naturalWidths[c] = max(naturalWidths[c], p.width)
                    }
                }

                val colWidths = distributeColumnWidths(
                    naturalWidths = naturalWidths,
                    minWidths = minWidthsPx,
                    maxWidths = maxWidthsPx,
                    viewportMaxWidth = viewportMaxWidth,
                    stretchToFillWidth = stretchToFillWidth,
                )
                val headerHeight = headerPlaceables.maxOfOrNull { it.height } ?: 0
                val rowHeights = IntArray(rowCount) { r ->
                    var h = 0
                    for (c in 0 until columnCount) {
                        h = max(h, bodyPlaceables[r * columnCount + c].height)
                    }
                    h
                }

                val tableWidth = colWidths.sum()
                val tableHeight = headerHeight + rowHeights.sum()
                gridMetrics.colWidths = colWidths
                gridMetrics.headerHeight = headerHeight
                gridMetrics.rowHeights = rowHeights
                gridMetrics.tableWidth = tableWidth
                gridMetrics.tableHeight = tableHeight

                val finalWidth = tableWidth.coerceIn(layoutConstraints.minWidth, layoutConstraints.maxWidth)
                val finalHeight = tableHeight.coerceIn(layoutConstraints.minHeight, layoutConstraints.maxHeight)

                layout(finalWidth, finalHeight) {
                    var x = 0
                    for (c in 0 until columnCount) {
                        val p = headerPlaceables[c]
                        val align = columnAlignments.getOrNull(c) ?: cellAlignment
                        val offset = align.align(
                            size = IntSize(p.width, p.height),
                            space = IntSize(colWidths[c], headerHeight),
                            layoutDirection = layoutDirection,
                        )
                        p.placeRelative(x + offset.x, offset.y)
                        x += colWidths[c]
                    }
                    var y = headerHeight
                    for (r in 0 until rowCount) {
                        x = 0
                        val rowH = rowHeights[r]
                        for (c in 0 until columnCount) {
                            val p = bodyPlaceables[r * columnCount + c]
                            val align = columnAlignments.getOrNull(c) ?: cellAlignment
                            val offset = align.align(
                                size = IntSize(p.width, p.height),
                                space = IntSize(colWidths[c], rowH),
                                layoutDirection = layoutDirection,
                            )
                            p.placeRelative(x + offset.x, y + offset.y)
                            x += colWidths[c]
                        }
                        y += rowH
                    }
                }
            }
        }
    }
}

// -------------------- 示例 --------------------
@Preview(showBackground = true)
@Composable
private fun DataTablePreview() {
    CompositionLocalProvider(LocalSettings provides Settings()) {
        Surface {
            val headers = listOf<@Composable () -> Unit>(
                { Text("Semester", style = MaterialTheme.typography.labelLarge) },
                { Text("Attendance", style = MaterialTheme.typography.labelLarge) },
                { Text("Notes / Example", style = MaterialTheme.typography.labelLarge) },
            )

            val rows = listOf<List<@Composable () -> Unit>>(
                listOf<@Composable () -> Unit>(
                    { Text("Fall 2024") },
                    { Text("Excellent", style = MaterialTheme.typography.bodyMedium) },
                    { Text("x² + y² = 1") },
                ),
                listOf(
                    { Text("Fall 2024") },
                    { Text("Good", style = MaterialTheme.typography.bodyMedium) },
                    { Text("∑ k = n(n+1)/2", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                ),
                listOf(
                    { Text("Fall 2024") },
                    { Text("Fair", style = MaterialTheme.typography.bodyMedium) },
                    { MarkdownBlock("这行更高会把整行拉齐! 这是一个很长的文本用来测试换行功能!  \n>haha") },
                ),
            )

            DataTable(
                headers = headers,
                rows = rows,
                columnMinWidths = listOf(60.dp, 100.dp, 80.dp),
                columnMaxWidths = listOf(120.dp, 100.dp, 200.dp),
                zebraStriping = false,
            )
        }
    }
}
