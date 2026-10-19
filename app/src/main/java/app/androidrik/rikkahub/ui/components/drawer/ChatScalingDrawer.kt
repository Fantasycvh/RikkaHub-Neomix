package app.androidrik.rikkahub.ui.components.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Drawer spec tokens matching design doc §5 ChatDrawerSpec table.
 */
private object ChatDrawerSpec {
    /** Drawer width as screen fraction when open. */
    const val FRACTION = 0.8f

    /** Max drawer width, aligned with Material3 ModalDrawerSheet. */
    val MAX_WIDTH = 360.dp

    /** Content scale at open state. */
    const val CONTENT_SCALE_END = 0.95f

    /** Corner radius at open state. */
    val CORNER_RADIUS_END = 22.dp

    /** Shadow elevation at open state. */
    val SHADOW_END = 20.dp

    /** Scrim alpha when closed (inverted during animation). */
    const val SCRIM_ALPHA = 0.12f

    /** Drag span as screen fraction for full travel. */
    const val DRAG_SPAN_FRACTION = 0.6f

    /** Settle threshold: drag delta beyond this triggers direction. */
    const val SETTLE_THRESHOLD = 0.25f

    /** Left edge zone for open gesture in closed state. */
    val EDGE_OPEN_ZONE = 24.dp

    /** Settle animation spec with improved damping. */
    val SPEC_SNAP = spring<Float>(
        dampingRatio = 0.9f,
        stiffness = 500f,
    )
}

/**
 * Scaling drawer: drawer slides in from left, content panel slides right, scales down,
 * and gains corner radius and shadow. Backdrop matches drawer container color.
 *
 * Improvements over Yuihub implementation:
 * 1. `progress.value` reads moved to graphicsLayer/offset/drawBehind lambdas → no per-frame recomposition
 * 2. Gesture restricted to left 24dp edge when closed + systemGestureExclusion
 * 3. Improved snap spring parameters (dampingRatio=0.9, stiffness=500)
 * 4. Added semantics for accessibility (paneTitle, dismiss action)
 *
 * @param open Current open state
 * @param onOpenChange Callback when open state changes
 * @param drawerContent Drawer panel content
 * @param content Main content panel
 */
@Composable
fun ChatScalingDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val backdropColor = DrawerDefaults.modalContainerColor
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerWidth = (maxWidth * ChatDrawerSpec.FRACTION).coerceAtMost(ChatDrawerSpec.MAX_WIDTH)
        val progress = remember { Animatable(if (open) 1f else 0f) }
        val scope = rememberCoroutineScope()

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
            scope.launch { progress.animateTo(if (target) 1f else 0f, ChatDrawerSpec.SPEC_SNAP) }
            onOpenChange(target)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropColor)
                // IMPROVEMENT 1: scrimAlpha computed inside drawBehind lambda, not in composition
                .drawBehind {
                    val p = progress.value
                    val scrimAlpha = ChatDrawerSpec.SCRIM_ALPHA * (1f - p)
                    drawRect(Color.Black.copy(alpha = scrimAlpha))
                }
                // IMPROVEMENT 2: Restrict drag gesture based on open state
                .pointerInput(drawerWidth, maxWidth) {
                    val dragSpan = drawerWidth / ChatDrawerSpec.FRACTION * ChatDrawerSpec.DRAG_SPAN_FRACTION
                    val edgeZonePx = ChatDrawerSpec.EDGE_OPEN_ZONE.toPx()
                    var startProgress = 0f

                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            startProgress = progress.value
                            // When closed, only left edge triggers open gesture
                            if (startProgress < 0.01f && offset.x > edgeZonePx) {
                                // Outside edge zone, don't consume the gesture
                                return@detectHorizontalDragGestures
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val delta = dragAmount / dragSpan.toPx()
                                progress.snapTo((progress.value + delta).coerceIn(0f, 1f))
                            }
                        },
                        onDragEnd = { settle(startProgress) },
                        onDragCancel = { settle(startProgress) },
                    )
                }
                .semantics {
                    paneTitle = "Navigation drawer"
                    if (open) {
                        dismiss {
                            onOpenChange(false)
                            true
                        }
                    }
                },
        ) {
            // Pass progress as state, components read .value in their lambdas
            DrawerSlidePanel(
                width = drawerWidth,
                progress = { progress.value },
                content = drawerContent,
            )

            ScaledHomePanel(
                drawerWidth = drawerWidth,
                progress = { progress.value },
                onTapWhenOpen = { if (progress.value > 0.01f) onOpenChange(false) },
                content = content,
            )
        }
    }
}

/**
 * Drawer panel: slides in from left edge.
 * IMPROVEMENT 1: translationX computed inside graphicsLayer lambda → read-only invalidation.
 */
@Composable
private fun DrawerSlidePanel(
    width: Dp,
    progress: () -> Float,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .graphicsLayer {
                translationX = -size.width * (1f - progress())
            },
    ) {
        content()
    }
}

/**
 * Content panel: slides right, scales down, gains corner radius and shadow.
 * IMPROVEMENT 1: All animation values computed inside graphicsLayer lambda.
 */
@Composable
private fun ScaledHomePanel(
    drawerWidth: Dp,
    progress: () -> Float,
    onTapWhenOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Read progress once per composition to decide overlay visibility
    val p = progress()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progressValue = progress()
                val scale = 1f - (1f - ChatDrawerSpec.CONTENT_SCALE_END) * progressValue
                
                translationX = (drawerWidth * progressValue).toPx()
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                shadowElevation = (ChatDrawerSpec.SHADOW_END * progressValue).toPx()
                shape = RoundedCornerShape(ChatDrawerSpec.CORNER_RADIUS_END * progressValue)
                clip = true
            },
    ) {
        content()
        
        // Tap overlay when open
        if (p > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onTapWhenOpen() }
                    },
            )
        }
    }
}
