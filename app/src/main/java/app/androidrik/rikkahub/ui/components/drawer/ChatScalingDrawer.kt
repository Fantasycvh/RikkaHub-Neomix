package app.androidrik.rikkahub.ui.components.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DrawerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private object ChatDrawerSpec {
    const val FRACTION = 0.8f
    val MAX_WIDTH = 360.dp
    const val CONTENT_SCALE_END = 0.95f
    val CORNER_RADIUS_END = 22.dp
    val SHADOW_END = 20.dp
    const val SCRIM_ALPHA = 0.12f
    const val DRAG_SPAN_FRACTION = 0.6f
    const val SETTLE_THRESHOLD = 0.25f
    val EDGE_OPEN_ZONE = 24.dp
    val SPEC_SNAP = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)
}

/**
 * Scaling modal drawer. Closed-state drags are accepted only from the left 24dp;
 * rejected gestures are never consumed, so horizontal chat controls keep working.
 * Animation reads live in graphics layers, not in the content's composition.
 */
@Composable
fun ChatScalingDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdropColor = DrawerDefaults.modalContainerColor
    val edgeWidthPx = with(LocalDensity.current) { ChatDrawerSpec.EDGE_OPEN_ZONE.toPx() }
    val currentOpen by rememberUpdatedState(open)
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    BackHandler(enabled = open) { currentOnOpenChange(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val drawerWidth = (maxWidth * ChatDrawerSpec.FRACTION).coerceAtMost(ChatDrawerSpec.MAX_WIDTH)
        val progress = remember { Animatable(if (open) 1f else 0f) }
        val scope = rememberCoroutineScope()
        // This derived boolean changes only at the boundary, not once per animation frame.
        val blockHome by remember { derivedStateOf { progress.value > 0.01f } }

        LaunchedEffect(open) {
            progress.animateTo(if (open) 1f else 0f, ChatDrawerSpec.SPEC_SNAP)
        }

        fun settle(startProgress: Float) {
            val delta = progress.value - startProgress
            val target = when {
                delta > ChatDrawerSpec.SETTLE_THRESHOLD -> true
                delta < -ChatDrawerSpec.SETTLE_THRESHOLD -> false
                else -> progress.value > 0.5f
            }
            // Also settle when the callback leaves open unchanged (e.g. a short drag).
            scope.launch { progress.animateTo(if (target) 1f else 0f, ChatDrawerSpec.SPEC_SNAP) }
            currentOnOpenChange(target)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropColor)
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = ChatDrawerSpec.SCRIM_ALPHA * (1f - progress.value)))
                }
                .systemGestureExclusion { coordinates ->
                    Rect(0f, 0f, edgeWidthPx, coordinates.size.height.toFloat())
                }
                .pointerInput(drawerWidth) {
                    val dragSpanPx = drawerWidth.toPx() / ChatDrawerSpec.FRACTION *
                        ChatDrawerSpec.DRAG_SPAN_FRACTION
                    val edgeZonePx = ChatDrawerSpec.EDGE_OPEN_ZONE.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val wasOpen = currentOpen
                        // Check DOWN, not the post-touch-slop position. Returning before detection
                        // means a rejected gesture cannot consume any of the child's drag events.
                        if (!wasOpen && down.position.x > edgeZonePx) return@awaitEachGesture
                        val startProgress = progress.value
                        var dragProgress = startProgress
                        fun dragBy(amount: Float) {
                            dragProgress = (dragProgress + amount / dragSpanPx).coerceIn(0f, 1f)
                            val target = dragProgress
                            scope.launch { progress.snapTo(target) }
                        }
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            change.consume()
                            dragBy(overSlop)
                        } ?: return@awaitEachGesture
                        val completed = horizontalDrag(drag.id) { change ->
                            dragBy(change.position.x - change.previousPosition.x)
                            change.consume()
                        }
                        if (completed) {
                            settle(startProgress)
                        } else {
                            // Cancellation must not turn a partial drag into a navigation action.
                            scope.launch {
                                progress.animateTo(if (wasOpen) 1f else 0f, ChatDrawerSpec.SPEC_SNAP)
                            }
                        }
                    }
                },
        ) {
            DrawerSlidePanel(
                width = drawerWidth,
                open = open,
                progress = { progress.value },
                onDismiss = { currentOnOpenChange(false) },
                content = drawerContent,
            )
            ScaledHomePanel(
                drawerWidth = drawerWidth,
                progress = { progress.value },
                blockHome = blockHome,
                onTapWhenOpen = { currentOnOpenChange(false) },
                content = content,
            )
        }
    }
}

@Composable
private fun DrawerSlidePanel(
    width: Dp,
    open: Boolean,
    progress: () -> Float,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .graphicsLayer { translationX = -size.width * (1f - progress()) }
            .semantics {
                if (open) {
                    paneTitle = "Navigation drawer"
                    dismiss { onDismiss(); true }
                } else {
                    hideFromAccessibility()
                }
            },
    ) {
        content()
    }
}

@Composable
private fun ScaledHomePanel(
    drawerWidth: Dp,
    progress: () -> Float,
    blockHome: Boolean,
    onTapWhenOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress()
                val scale = 1f - (1f - ChatDrawerSpec.CONTENT_SCALE_END) * p
                translationX = (drawerWidth * p).toPx()
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                shadowElevation = (ChatDrawerSpec.SHADOW_END * p).toPx()
                shape = RoundedCornerShape(ChatDrawerSpec.CORNER_RADIUS_END * p)
                clip = true
            },
    ) {
        Box(Modifier.fillMaxSize().then(if (blockHome) Modifier.clearAndSetSemantics {} else Modifier)) {
            content()
        }
        if (blockHome) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onTapWhenOpen() } },
            )
        }
    }
}
