package app.androidrik.rikkahub.ui.floating

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.androidrik.rikkahub.service.floating.ResizeEdge
import app.androidrik.rikkahub.service.floating.WindowGeometry

/** Only edge hits are consumed at Initial pass; body scroll/buttons keep their own gestures. */
internal fun Modifier.windowGeometryGesture(
    edgeZonePx: Float,
    geometry: () -> WindowGeometry,
    edges: Boolean,
    onChange: (WindowGeometry, ResizeEdge?, Int, Int) -> Unit,
    onFinished: () -> Unit,
): Modifier = composed {
    val latestChange = rememberUpdatedState(onChange)
    val latestFinished = rememberUpdatedState(onFinished)
    val latestGeometry = rememberUpdatedState(geometry)
    val view = LocalView.current
    fun screenOffset(): Offset {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Offset(location[0].toFloat(), location[1].toFloat())
    }
    pointerInput(edgeZonePx, edges) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!edges && down.isConsumed) return@awaitEachGesture
        val edge = if (edges) {
            val left = down.position.x < edgeZonePx
            val right = down.position.x > size.width - edgeZonePx
            val top = down.position.y < edgeZonePx
            val bottom = down.position.y > size.height - edgeZonePx
            when {
                left && top -> ResizeEdge.TOP_LEFT
                right && top -> ResizeEdge.TOP_RIGHT
                left && bottom -> ResizeEdge.BOTTOM_LEFT
                right && bottom -> ResizeEdge.BOTTOM_RIGHT
                left -> ResizeEdge.LEFT
                right -> ResizeEdge.RIGHT
                top -> ResizeEdge.TOP
                bottom -> ResizeEdge.BOTTOM
                else -> return@awaitEachGesture
            }
        } else null
        val origin = latestGeometry.value()
        val start = down.position + screenOffset()
        if (edges) down.consume()
        var dragging = edges
        try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging) change.consume()
                break
            }
            val delta = change.position + screenOffset() - start
            if (!dragging && delta.getDistance() > viewConfiguration.touchSlop) dragging = true
            if (dragging) {
                change.consume()
                latestChange.value(origin, edge, delta.x.toInt(), delta.y.toInt())
            }
        }
        } finally {
            if (dragging) latestFinished.value()
        }
    }
    }
}
