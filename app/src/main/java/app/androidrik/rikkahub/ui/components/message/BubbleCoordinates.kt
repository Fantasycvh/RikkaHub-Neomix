package app.androidrik.rikkahub.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** Shared draw coordinates for currently mounted lazy slices; never forces off-screen text measurement. */
internal class BubbleCoordinateSpace {
    var origin by mutableStateOf(Offset.Zero)
    private val nodes = mutableStateMapOf<Any, BubbleCoordinateNode>()
    private val known = linkedMapOf<Any, Offset>()
    private var anchor: Any? = null
    private var extent = Size.Zero
    fun attach(id: Any): BubbleCoordinateNode = BubbleCoordinateNode(this).also { nodes[id] = it }
    fun update(id: Any, position: Offset, size: Size) {
        val node = nodes[id] ?: return
        node.position = position; node.size = size
        if (anchor == null) { anchor = id; origin = position - (known[id] ?: Offset.Zero) }
        if (anchor == id) origin = position - (known[id] ?: Offset.Zero)
    }
    fun detach(id: Any) {
        nodes.remove(id)?.let { known[id] = it.position - origin }
        if (known.size > 512) known.remove(known.keys.first())
        if (anchor == id) {
            anchor = nodes.keys.firstOrNull()
            anchor?.let { known[it] = requireNotNull(nodes[it]).position - origin }
        }
    }
    fun bounds(local: Size): Size {
        val width = nodes.values.maxOfOrNull { (it.position.x - origin.x + it.size.width).coerceAtLeast(0f) } ?: 0f
        val height = nodes.values.maxOfOrNull { (it.position.y - origin.y + it.size.height).coerceAtLeast(0f) } ?: 0f
        extent = Size(maxOf(extent.width, width, local.width), maxOf(extent.height, height, local.height))
        return extent
    }
}

internal class BubbleCoordinateNode(val space: BubbleCoordinateSpace) {
    var position by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(Size.Zero)
    fun offset() = position - space.origin
}

internal class BubbleCoordinateRegistry {
    private val spaces = linkedMapOf<Any, BubbleCoordinateSpace>()
    fun space(id: Any): BubbleCoordinateSpace = spaces.getOrPut(id) { BubbleCoordinateSpace() }.also {
        if (spaces.size > 128) spaces.remove(spaces.keys.first())
    }
}

internal val LocalBubbleCoordinates = compositionLocalOf<BubbleCoordinateRegistry?> { null }

@Composable
internal fun rememberBubbleCoordinates(segment: BubbleSegment?): Pair<Modifier, BubbleCoordinateNode?> {
    val registry = LocalBubbleCoordinates.current
    val group = segment?.group
    val id = segment?.unitId
    if (registry == null || group == null || id == null) return Modifier to null
    val space = remember(registry, group) { registry.space(group) }
    val node = remember(space, id) { space.attach(id) }
    DisposableEffect(space, id) { onDispose { space.detach(id) } }
    return Modifier.onGloballyPositioned { coordinates ->
        space.update(id, coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    } to node
}
