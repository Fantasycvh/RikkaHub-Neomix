package app.androidrik.rikkahub.ui.components.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Minimize2
import com.composables.icons.lucide.X
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.service.floating.FloatingWindowGeometry
import app.androidrik.rikkahub.service.floating.ResizeEdge
import app.androidrik.rikkahub.service.floating.ScreenSizePx
import app.androidrik.rikkahub.service.floating.WindowGeometry

/**
 * 悬浮小窗的**原型界面**：标题栏（拖动/最大化/关闭）+ 占位正文 + 八向拉伸热区。
 *
 * 这一版刻意不接聊天内容——先把「窗口能不能拖、能不能拉、拉到头会不会怪」验证掉。
 * 聊天界面在 P0 接进来时只替换 body，窗口外壳不动。
 *
 * 手势分工：
 * - 标题栏：拖动窗口（子层消费，根层的边缘检测收不到）；
 * - 窗口内缘 20dp：八向拉伸（根层检测，**命中才消费**，否则放行给内部滚动）；
 * - 松手贴边：左/右 16dp 内吸到边上。
 */
@Composable
fun FloatingWindowPane(
    geometry: WindowGeometry,
    screenSize: ScreenSizePx,
    onGeometryChange: (WindowGeometry) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val minWidthPx = with(density) { FloatingWindowGeometry.MIN_WIDTH_DP.dp.roundToPx() }
    val minHeightPx = with(density) { FloatingWindowGeometry.MIN_HEIGHT_DP.dp.roundToPx() }
    val edgeZonePx = with(density) { 20.dp.toPx() }
    val snapPx = with(density) { FloatingWindowGeometry.EDGE_SNAP_DP.dp.roundToPx() }

    // 拖动/拉伸期间 geometry 会被不断改写：pointerInput 的 key 里不能放 geometry，
    // 否则每次改写都会重启手势识别、拖动直接断掉。用 rememberUpdatedState 读最新值。
    val current by rememberUpdatedState(geometry)
    val screen by rememberUpdatedState(screenSize)
    val onGeometry by rememberUpdatedState(onGeometryChange)

    var preMaximize by remember { mutableStateOf<WindowGeometry?>(null) }
    val maximized = FloatingWindowGeometry.isMaximized(geometry, screenSize.width, screenSize.height)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .pointerInput(screen) {
                // PointerInputScope 里才有 size（窗口像素尺寸），边缘判定必须在这里做
                fun edgeAt(offset: Offset): ResizeEdge? {
                    val nearLeft = offset.x <= edgeZonePx
                    val nearRight = offset.x >= size.width - edgeZonePx
                    val nearTop = offset.y <= edgeZonePx
                    val nearBottom = offset.y >= size.height - edgeZonePx
                    return when {
                        nearTop && nearLeft -> ResizeEdge.TOP_LEFT
                        nearTop && nearRight -> ResizeEdge.TOP_RIGHT
                        nearBottom && nearLeft -> ResizeEdge.BOTTOM_LEFT
                        nearBottom && nearRight -> ResizeEdge.BOTTOM_RIGHT
                        nearLeft -> ResizeEdge.LEFT
                        nearRight -> ResizeEdge.RIGHT
                        nearTop -> ResizeEdge.TOP
                        nearBottom -> ResizeEdge.BOTTOM
                        else -> null
                    }
                }

                var edge: ResizeEdge? = null
                var start: WindowGeometry? = null
                var accumulated = Offset.Zero
                detectDragGestures(
                    onDragStart = { offset ->
                        edge = edgeAt(offset)
                        start = current
                        accumulated = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        val activeEdge = edge ?: return@detectDragGestures
                        val origin = start ?: return@detectDragGestures
                        // 命中边缘才消费：窗口内部（正文区）的拖动要放行给滚动
                        change.consume()
                        accumulated += dragAmount
                        onGeometry(
                            FloatingWindowGeometry.resized(
                                origin = origin,
                                edge = activeEdge,
                                dx = accumulated.x.toInt(),
                                dy = accumulated.y.toInt(),
                                screenWidth = screen.width,
                                screenHeight = screen.height,
                                minWidth = minWidthPx,
                                minHeight = minHeightPx,
                            )
                        )
                    },
                    onDragEnd = {
                        if (edge != null) {
                            onGeometry(FloatingWindowGeometry.snapped(current, screen.width, snapPx))
                        }
                        edge = null
                        start = null
                    },
                    onDragCancel = {
                        edge = null
                        start = null
                    },
                )
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 标题栏：唯一的拖动热区 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInput(screen) {
                        var start: WindowGeometry? = null
                        var accumulated = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                start = current
                                accumulated = Offset.Zero
                            },
                            onDrag = { change, dragAmount ->
                                val origin = start ?: return@detectDragGestures
                                change.consume()
                                accumulated += dragAmount
                                onGeometry(
                                    FloatingWindowGeometry.dragged(
                                        origin = origin,
                                        dx = accumulated.x.toInt(),
                                        dy = accumulated.y.toInt(),
                                        screenWidth = screen.width,
                                        screenHeight = screen.height,
                                    )
                                )
                            },
                            onDragEnd = {
                                onGeometry(FloatingWindowGeometry.snapped(current, screen.width, snapPx))
                                start = null
                            },
                            onDragCancel = { start = null },
                        )
                    }
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.floating_window_title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        R.string.floating_window_size,
                        with(density) { geometry.width.toDp().value.toInt() },
                        with(density) { geometry.height.toDp().value.toInt() },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = {
                        if (maximized) {
                            preMaximize?.let(onGeometryChange)
                            preMaximize = null
                        } else {
                            preMaximize = geometry
                            onGeometryChange(
                                FloatingWindowGeometry.maximized(screen.width, screen.height)
                            )
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (maximized) Lucide.Minimize2 else Lucide.Maximize2,
                        contentDescription = stringResource(
                            if (maximized) R.string.floating_window_restore
                            else R.string.floating_window_maximize
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.floating_window_close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ---- 占位正文：P0 替换成聊天消息列表 ----
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.floating_window_prototype_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.floating_window_prototype_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.floating_window_prototype_drag_hint),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onOpenApp) {
                    Text(stringResource(R.string.floating_window_open_app))
                }
            }
        }

        // ---- 角上的小折角标记：暗示「这里可以拉」。纯装饰，不吃事件 ----
        CornerGrip(Modifier.align(Alignment.TopStart))
        CornerGrip(Modifier.align(Alignment.TopEnd))
        CornerGrip(Modifier.align(Alignment.BottomStart))
        CornerGrip(Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun CornerGrip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(14.dp)
            .padding(2.dp)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(3.dp),
            )
    )
}
