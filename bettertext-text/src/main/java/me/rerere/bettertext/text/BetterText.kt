package me.rerere.bettertext.text

import android.graphics.Path
import android.graphics.Typeface
import android.text.StaticLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/**
 * Displays [text] with inline content, laid out with Android's `StaticLayout`.
 *
 * Inline items are added with [appendInline] and resolved through
 * [inlineContent]; remember the map, since a new map re-prepares the text.
 * Each line is at least `style.lineHeight` tall and grows to fit taller
 * inline items or larger text, instead of overlapping its neighbors.
 * Inside a [BetterSelectionContainer] the text is selectable. Tapping a
 * [LinkAnnotation] calls its listener, or opens its URL when it has none.
 *
 * @param maxLines lines beyond this are cut off with an ellipsis.
 */
@Composable
fun BetterText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    inlineContent: Map<String, BetterInline> = emptyMap(),
    maxLines: Int = Int.MAX_VALUE,
) {
    require(maxLines > 0) { "maxLines must be positive" }
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val prepared = remember(text, inlineContent, style, density, fontFamilyResolver) {
        PreparedText.create(text, inlineContent, style, density) { family, weight, fontStyle, synthesis ->
            fontFamilyResolver.resolve(family, weight, fontStyle, synthesis).value as Typeface
        }
    }
    val cache = remember(prepared) { TextLayoutCache(prepared) }
    val holder = remember(prepared) { LayoutHolder() }
    val brushPaint = remember { androidx.compose.ui.graphics.Paint() }
    val registry = LocalSelectionRegistry.current
    val selectable = remember(registry) { registry?.let { SelectableText() } }
    selectable?.pinnableContainer = LocalPinnableContainer.current
    selectable?.group = LocalSelectionGroup.current
    selectable?.separatorBefore = LocalSelectionSeparator.current
    if (registry != null && selectable != null) {
        DisposableEffect(registry, selectable) {
            registry.register(selectable)
            onDispose { registry.unregister(selectable) }
        }
    }
    val uriHandler = LocalUriHandler.current
    val selectionColor = LocalTextSelectionColors.current.backgroundColor
    val selectionPath = remember { Path() }

    Layout(
        contents = prepared.composables.map { it.inline.content },
        modifier = modifier
            .semantics { this.text = text }
            .then(
                if (selectable != null) {
                    Modifier
                        .onGloballyPositioned {
                            selectable.coordinates = it
                            registry?.onLayoutChanged(selectable)
                        }
                        .nestedScroll(NoScrollConnection, selectable.scrollDispatcher)
                } else {
                    Modifier
                },
            )
            .then(
                if (prepared.links.isNotEmpty()) {
                    Modifier.pointerInput(prepared) {
                        fun linkAt(position: Offset): LinkRange? {
                            val index = holder.layout?.charIndexAt(position.x, position.y) ?: -1
                            return prepared.links.lastOrNull { index >= it.start && index < it.end }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val link = linkAt(down.position) ?: return@awaitEachGesture
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            if (linkAt(up.position) !== link) return@awaitEachGesture
                            up.consume()
                            open(link.link, uriHandler)
                        }
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                val layout = holder.layout ?: return@drawBehind
                val range = selectable?.let { registry?.rangeOf(it) }
                if (range != null) {
                    selectionPath.reset()
                    layout.getSelectionPath(range.first, range.last + 1, selectionPath)
                    drawPath(selectionPath.asComposePath(), selectionColor)
                }
                drawIntoCanvas { layout.drawWithBrush(it.nativeCanvas, style.brush, style.alpha, brushPaint) }
            },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { group -> group.map { it.measure(loose) } }
        val sizes = IntArray(placeables.size * 3)
        for ((i, group) in placeables.withIndex()) {
            val height = group.maxOfOrNull { it.height } ?: 0
            val baseline = group.firstOrNull()?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified }
            sizes[i * 3] = group.maxOfOrNull { it.width } ?: 0
            sizes[i * 3 + 1] = height
            sizes[i * 3 + 2] = baseline ?: height
        }
        cache.measureComposables(sizes)
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val layout = cache.layout(constraints.minWidth, maxWidth, maxLines)
        holder.layout = layout
        if (selectable != null && (selectable.layout !== layout || selectable.prepared !== prepared)) {
            selectable.prepared = prepared
            selectable.layout = layout
            registry?.onLayoutChanged(selectable)
        }
        val width = layout.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = layout.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(
            width,
            height,
            mapOf(
                FirstBaseline to layout.getLineBaseline(0),
                LastBaseline to layout.getLineBaseline(layout.lineCount - 1),
            ),
        ) {
            for ((i, group) in placeables.withIndex()) {
                val (x, y) = cache.composablePosition(layout, i) ?: continue
                for (placeable in group) placeable.place(x, y)
            }
        }
    }
}

/** Displays plain [text]; see the [AnnotatedString] overload. */
@Composable
fun BetterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
) {
    BetterText(remember(text) { AnnotatedString(text) }, modifier, style, maxLines = maxLines)
}

/** Calls the link's listener, or opens a URL link with [uriHandler] when it has none. */
private fun open(link: LinkAnnotation, uriHandler: UriHandler) {
    val listener = link.linkInteractionListener
    when {
        listener != null -> listener.onClick(link)
        link is LinkAnnotation.Url -> runCatching { uriHandler.openUri(link.url) }
    }
}

/** The text only dispatches scrolling, for the selection's auto-scroll. */
private val NoScrollConnection = object : NestedScrollConnection {}

/** The last layout, observable so drawing follows relayouts. */
private class LayoutHolder {
    var layout: StaticLayout? by mutableStateOf(null)
}
