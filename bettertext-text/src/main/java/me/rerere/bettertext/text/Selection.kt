package me.rerere.bettertext.text

import android.icu.text.BreakIterator
import android.text.StaticLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Provided by [BetterSelectionContainer]; null outside one. */
internal val LocalSelectionRegistry = staticCompositionLocalOf<SelectionRegistry?> { null }

/** One [BetterText] taking part in selection. Offsets are in its laid-out text, where inline items are U+FFFC. */
internal class SelectableText {
    var prepared: PreparedText? = null
    var layout: StaticLayout? = null
    var coordinates: LayoutCoordinates? = null

    /** Pinned while part of the text is selected, so a lazy list keeps it when it scrolls out of view. */
    var pinnableContainer: PinnableContainer? = null

    /** Scrolls the scrollable ancestors of the text, such as a code block's, when a drag reaches their edge. */
    val scrollDispatcher = NestedScrollDispatcher()

    val length: Int get() = layout?.text?.length ?: 0
    val isAttached: Boolean get() = layout != null && coordinates?.isAttached == true

    /** The offset nearest to [position], in this text's local coordinates. */
    fun offsetAt(position: Offset): Int {
        val layout = layout ?: return 0
        val y = position.y.toInt().coerceIn(0, maxOf(0, layout.height - 1))
        return layout.getOffsetForHorizontal(layout.getLineForVertical(y), position.x)
    }

    /** The word, or the whole inline item, under [position]; `[start, end)`. */
    fun wordAt(position: Offset): IntRange {
        val layout = layout ?: return IntRange.EMPTY
        if (length == 0) return 0 until 0
        var offset = offsetAt(position)
        // offsetAt returns the nearest boundary; step back to the character under x.
        val line = layout.getLineForOffset(offset)
        if (offset > layout.getLineStart(line) && layout.getPrimaryHorizontal(offset) > position.x) offset--
        offset = offset.coerceAtMost(length - 1)
        itemAt(offset)?.let { return it.start until it.end }
        val words = BreakIterator.getWordInstance()
        words.setText(layout.text.toString())
        val end = words.following(offset).takeIf { it != BreakIterator.DONE } ?: length
        val start = words.preceding(end).takeIf { it != BreakIterator.DONE } ?: 0
        return start until end
    }

    /** Widens `[start, end)` so it doesn't cut through an inline item. */
    fun snap(start: Int, end: Int): IntRange {
        var s = start.coerceIn(0, length)
        var e = end.coerceIn(s, length)
        itemAt(s)?.let { if (it.start < s) s = it.start }
        if (e > 0) itemAt(e - 1)?.let { e = maxOf(e, it.end) }
        return s until e
    }

    /** The text of `[start, end)` with inline items replaced by their alt text. */
    fun copyText(start: Int, end: Int): String {
        val text = layout?.text ?: return ""
        val items = prepared?.items.orEmpty()
        val out = StringBuilder()
        var i = start
        while (i < end) {
            val item = items.firstOrNull { i >= it.start && i < it.end }
            if (item != null) {
                out.append(item.altText)
                i = item.end
            } else {
                out.append(text[i++])
            }
        }
        return out.toString()
    }

    /** Where a handle at [offset] points, as the bottom of its line, in this text's coordinates. */
    fun handlePosition(offset: Int, isStart: Boolean): HandlePosition? {
        val layout = layout ?: return null
        val o = offset.coerceIn(0, length)
        var line = layout.getLineForOffset(o)
        // An end at a soft line break belongs to the end of the previous line.
        val wrapped = !isStart && o > 0 && o == layout.getLineStart(line) && layout.text[o - 1] != '\n'
        if (wrapped) line--
        val x = if (wrapped) layout.getLineRight(line) else layout.getPrimaryHorizontal(o)
        return HandlePosition(
            Offset(x, layout.getLineBottom(line).toFloat()),
            layout.getLineTop(line).toFloat(),
        )
    }

    private fun itemAt(offset: Int): InlineItem? =
        prepared?.items?.firstOrNull { offset >= it.start && offset < it.end }
}

/** A handle's anchor at the bottom of a line, and the top of that line. */
internal class HandlePosition(val bottom: Offset, val lineTop: Float)

internal class SelectionPoint(val text: SelectableText, val offset: Int)

/** A selection from [start] to [end]; [end] may come before [start]. */
internal class Selection(val start: SelectionPoint, val end: SelectionPoint)

/**
 * Tracks the texts in a [BetterSelectionContainer] and the selection across them.
 * Texts are ordered by position: top to bottom, then left to right.
 */
internal class SelectionRegistry {
    private val texts = ArrayList<SelectableText>()

    var containerCoordinates: LayoutCoordinates? = null

    /** The parts of the window covered by other UI; see [BetterSelectionContainer]. */
    var obscuredPadding = PaddingValues()
    var density = Density(1f)
    var layoutDirection = LayoutDirection.Ltr

    private var currentSelection: Selection? by mutableStateOf(null)
    private val pins = HashMap<SelectableText, PinnableContainer.PinnedHandle>()

    var selection: Selection?
        get() = currentSelection
        set(value) {
            currentSelection = value
            updatePins()
        }

    /** Bumped when a text in the selection moves or is laid out again, to reposition handles. */
    var layoutVersion by mutableIntStateOf(0)
        private set

    fun register(text: SelectableText) {
        texts.add(text)
    }

    fun unregister(text: SelectableText) {
        texts.remove(text)
        pins.remove(text)?.release()
        val s = selection
        if (s != null && (s.start.text === text || s.end.text === text)) selection = null
    }

    fun onLayoutChanged() {
        if (selection != null) layoutVersion++
    }

    /** Pins the texts from the selection's start to its end and releases the others, as Compose's selection does. */
    private fun updatePins() {
        val texts = ordered()
        val ends = selection?.let { normalized(it, texts) }
        val selected = if (ends == null) emptySet() else texts.subList(texts.indexOf(ends.first.text), texts.indexOf(ends.second.text) + 1).toSet()
        val iterator = pins.iterator()
        while (iterator.hasNext()) {
            val (text, handle) = iterator.next()
            if (text !in selected) {
                handle.release()
                iterator.remove()
            }
        }
        for (text in selected) {
            if (text !in pins) text.pinnableContainer?.let { pins[text] = it.pin() }
        }
    }

    fun releasePins() {
        pins.values.forEach { it.release() }
        pins.clear()
    }

    /**
     * The part of [coordinates] that is visible and not under [obscuredPadding],
     * in its own coordinates; null when there's none.
     */
    fun visibleBounds(coordinates: LayoutCoordinates): Rect? {
        val root = coordinates.findRootCoordinates()
        val rootTopLeft = root.localToWindow(Offset.Zero)
        val padding = obscuredPadding
        val window = with(density) {
            Rect(
                rootTopLeft.x + padding.calculateLeftPadding(layoutDirection).toPx(),
                rootTopLeft.y + padding.calculateTopPadding().toPx(),
                rootTopLeft.x + root.size.width - padding.calculateRightPadding(layoutDirection).toPx(),
                rootTopLeft.y + root.size.height - padding.calculateBottomPadding().toPx(),
            )
        }
        val bounds = coordinates.boundsInWindow().intersect(window)
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        return Rect(coordinates.windowToLocal(bounds.topLeft), coordinates.windowToLocal(bounds.bottomRight))
    }

    /** Attached texts in document order. */
    fun ordered(): List<SelectableText> {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return emptyList()
        return texts.filter { it.isAttached }.sortedWith(
            compareBy<SelectableText>({ container.localPositionOf(it.coordinates!!, Offset.Zero).y })
                .thenBy { container.localPositionOf(it.coordinates!!, Offset.Zero).x },
        )
    }

    /** The text under [position] (container coordinates) and the position local to it. */
    fun textAt(position: Offset): Pair<SelectableText, Offset>? {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return null
        for (text in ordered()) {
            val coordinates = text.coordinates!!
            val local = coordinates.localPositionOf(container, position)
            val size = coordinates.size
            if (local.x >= 0 && local.y >= 0 && local.x < size.width && local.y < size.height) return text to local
        }
        return null
    }

    /**
     * The point nearest to [position] (container coordinates): in the text on
     * that row nearest to it, else at the end of the text above the gap.
     */
    fun pointAt(position: Offset): SelectionPoint? {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return null
        val texts = ordered()
        if (texts.isEmpty()) return null
        val bounds = texts.map { boundsOf(it, container) }
        val row = texts.indices.filter { position.y >= bounds[it].top && position.y < bounds[it].bottom }
        if (row.isNotEmpty()) {
            val i = row.minBy { i ->
                val b = bounds[i]
                when {
                    position.x < b.left -> b.left - position.x
                    position.x > b.right -> position.x - b.right
                    else -> 0f
                }
            }
            val text = texts[i]
            return SelectionPoint(text, text.offsetAt(text.coordinates!!.localPositionOf(container, position)))
        }
        val below = texts.indices.firstOrNull { bounds[it].top > position.y }
        return when (below) {
            null -> texts.last().let { SelectionPoint(it, it.length) }
            0 -> SelectionPoint(texts[0], 0)
            else -> texts[below - 1].let { SelectionPoint(it, it.length) }
        }
    }

    /** The selected range of [text], snapped to whole inline items, or null if none of it is selected. */
    fun rangeOf(text: SelectableText): IntRange? {
        val s = selection ?: return null
        val texts = ordered()
        val (first, last) = normalized(s, texts) ?: return null
        val index = texts.indexOf(text)
        val firstIndex = texts.indexOf(first.text)
        val lastIndex = texts.indexOf(last.text)
        if (index < firstIndex || index > lastIndex) return null
        val start = if (index == firstIndex) first.offset else 0
        val end = if (index == lastIndex) last.offset else text.length
        return text.snap(start, end).takeUnless { it.isEmpty() }
    }

    /** The selection's points in document order. */
    fun normalized(s: Selection, texts: List<SelectableText> = ordered()): Pair<SelectionPoint, SelectionPoint>? {
        val a = texts.indexOf(s.start.text)
        val b = texts.indexOf(s.end.text)
        if (a < 0 || b < 0) return null
        return if (a < b || (a == b && s.start.offset <= s.end.offset)) s.start to s.end else s.end to s.start
    }

    fun selectedText(): String {
        val texts = ordered()
        return texts.mapNotNull { text -> rangeOf(text)?.let { text.copyText(it.first, it.last + 1) } }
            .joinToString("\n")
    }

    fun selectAll() {
        val texts = ordered()
        if (texts.isEmpty()) return
        selection = Selection(SelectionPoint(texts.first(), 0), SelectionPoint(texts.last(), texts.last().length))
    }

    fun isAllSelected(): Boolean {
        val texts = ordered()
        return texts.isNotEmpty() && texts.all { text -> rangeOf(text).let { it != null && it.first == 0 && it.last + 1 == text.length } }
    }

    /** Bounds of the selection in container coordinates. */
    fun selectionBounds(): Rect? {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return null
        var bounds: Rect? = null
        for (text in ordered()) {
            val range = rangeOf(text) ?: continue
            val layout = text.layout ?: continue
            val first = layout.getLineForOffset(range.first)
            val last = layout.getLineForOffset(range.last + 1)
            val left = if (first == last) layout.getPrimaryHorizontal(range.first) else 0f
            val right = if (first == last) layout.getPrimaryHorizontal(range.last + 1) else layout.width.toFloat()
            val topLeft = container.localPositionOf(text.coordinates!!, Offset(minOf(left, right), layout.getLineTop(first).toFloat()))
            val bottomRight = container.localPositionOf(text.coordinates!!, Offset(maxOf(left, right), layout.getLineBottom(last).toFloat()))
            val rect = Rect(topLeft, bottomRight)
            bounds = bounds?.let { Rect(minOf(it.left, rect.left), minOf(it.top, rect.top), maxOf(it.right, rect.right), maxOf(it.bottom, rect.bottom)) } ?: rect
        }
        return bounds
    }

    private fun boundsOf(text: SelectableText, container: LayoutCoordinates): Rect {
        val coordinates = text.coordinates!!
        val topLeft = container.localPositionOf(coordinates, Offset.Zero)
        return Rect(topLeft, Offset(topLeft.x + coordinates.size.width, topLeft.y + coordinates.size.height))
    }
}
