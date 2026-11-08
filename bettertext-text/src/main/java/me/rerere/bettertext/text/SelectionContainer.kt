package me.rerere.bettertext.text

import android.content.ClipData
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HandleSize = 25.dp

/** How close to the edge of the visible content a drag starts scrolling it. */
private val AutoScrollEdge = 40.dp

/** The fastest auto-scroll, per second, reached at the edge and beyond it. */
private val AutoScrollSpeed = 1200.dp

/**
 * Makes the [BetterText]s in [content] selectable together, like Compose's
 * `SelectionContainer` (which they can't join, see issue 233102278).
 *
 * Long press selects a word, or a whole inline item, and dragging extends the
 * selection across texts. Inline items are selected whole and copied as their
 * alt text. Texts are joined with a newline when copied.
 *
 * Dragging near the edge of the visible content scrolls what's cut off
 * there, whether the container is in a scrolling page or wraps a list, and
 * code blocks or tables that scroll sideways. Texts with part of the
 * selection are pinned, so a lazy list keeps them when they scroll out of
 * view and the selection can span its items.
 *
 * @param obscuredPadding how far other UI covers the content from each edge
 *   of the window, such as app bars over a list that scrolls behind them;
 *   usually the `Scaffold`'s content padding. Handles under it are hidden,
 *   and dragging to its edge scrolls.
 */
@Composable
fun BetterSelectionContainer(
    modifier: Modifier = Modifier,
    obscuredPadding: PaddingValues = PaddingValues(),
    content: @Composable () -> Unit,
) {
    val registry = remember { SelectionRegistry() }
    registry.obscuredPadding = obscuredPadding
    registry.density = LocalDensity.current
    registry.layoutDirection = LocalLayoutDirection.current
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    val toolbar = LocalTextToolbar.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Set while a long-press drag or a handle drag is in progress.
    var dragging by remember { mutableStateOf(false) }
    val gestures = remember(registry) { SelectionGestures(registry) }
    gestures.onStart = {
        focusRequester.requestFocus()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    gestures.onMove = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    fun copy() {
        val text = registry.selectedText()
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text))) }
        registry.selection = null
    }

    CompositionLocalProvider(LocalSelectionRegistry provides registry) {
        Box(
            modifier
                .onGloballyPositioned {
                    registry.containerCoordinates = it
                    registry.onLayoutChanged()
                }
                .focusRequester(focusRequester)
                .onFocusChanged { if (!it.hasFocus) registry.selection = null }
                // Not focusable(): it scrolls the container into view when focused.
                .focusTarget()
                .nestedScroll(gestures.confinement)
                .magnifier(sourceCenter = { gestures.magnifierCenter() })
                .pointerInput(gestures) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            // A tap that nothing else handled clears the selection.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed && !up.isConsumed &&
                                (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                            ) {
                                registry.selection = null
                            }
                            return@awaitEachGesture
                        }
                        if (!gestures.start(longPress.position)) return@awaitEachGesture
                        longPress.consume()
                        dragging = true
                        // Take the drag in the initial pass, before content inside sees it:
                        // a code block would scroll with it, and a link would open on release.
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == longPress.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            gestures.dragTo(change.position)
                        }
                        gestures.end()
                        dragging = false
                    }
                },
        ) {
            content()
            val selection = registry.selection
            if (selection != null) {
                val ordered = registry.normalized(selection)
                if (ordered != null) {
                    for (stored in listOf(true, false)) {
                        val point = if (stored) selection.start else selection.end
                        SelectionHandle(
                            registry = registry,
                            point = point,
                            isLeft = point === ordered.first,
                            onDragStart = { position ->
                                gestures.startHandle(stored, position)
                                dragging = true
                            },
                            onDrag = gestures::dragBy,
                            onDragEnd = {
                                gestures.end()
                                dragging = false
                            },
                        )
                    }
                }
            }
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(dragging, density) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { AutoScrollEdge.toPx() }
        val speed = with(density) { AutoScrollSpeed.toPx() }
        var previous = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // After a stalled frame, don't jump further than a few frames would have.
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                gestures.autoScroll(edge, speed * seconds)
                previous = now
            }
        }
    }

    val selection = registry.selection
    val version = registry.layoutVersion
    val handleHeight = with(density) { HandleSize.toPx() }
    LaunchedEffect(selection, dragging, version) {
        val container = registry.containerCoordinates
        val bounds = registry.selectionBounds()
        if (selection == null || dragging || container == null || !container.isAttached || bounds == null) {
            if (toolbar.status == TextToolbarStatus.Shown) toolbar.hide()
            return@LaunchedEffect
        }
        val visible = registry.visibleBounds(container)?.intersect(bounds)
        if (visible == null || visible.width < 0 || visible.height < 0) {
            if (toolbar.status == TextToolbarStatus.Shown) toolbar.hide()
            return@LaunchedEffect
        }
        val rect = visible.translate(container.positionInRoot())
        toolbar.showMenu(
            rect = rect.copy(bottom = rect.bottom + handleHeight),
            onCopyRequested = ::copy,
            onSelectAllRequested = if (registry.isAllSelected()) null else registry::selectAll,
        )
    }
    DisposableEffect(toolbar) {
        onDispose { if (registry.selection != null && toolbar.status == TextToolbarStatus.Shown) toolbar.hide() }
    }
    DisposableEffect(registry) {
        onDispose { registry.releasePins() }
    }
}

/**
 * Long-press and handle drags. Long press starts at a word and extends from
 * it, keeping the word selected; a handle drag moves one end.
 */
private class SelectionGestures(private val registry: SelectionRegistry) {
    var onStart: () -> Unit = {}
    var onMove: () -> Unit = {}
    private var anchor: SelectableText? = null
    private var word = IntRange.EMPTY

    /** The handle being dragged: true for the stored start, false for the end, null for a long-press drag. */
    private var handle: Boolean? = null

    /** The drag's position in window coordinates, which stay put when the content scrolls under it. */
    private var pointer by mutableStateOf(Offset.Unspecified)

    /** Installed on the container, to keep [autoScroll] inside it. */
    val confinement = ScrollConfinement()

    /** Whether the last [autoScroll] moved the content, which is laid out at the new position by the next one. */
    private var scrolled = false

    /** Selects the word at [position]; returns false if there's no text there. */
    fun start(position: Offset): Boolean {
        val (text, local) = registry.textAt(position) ?: return false
        val range = text.wordAt(local)
        anchor = text
        word = range
        handle = null
        pointer = toWindow(position)
        registry.selection = Selection(SelectionPoint(text, range.first), SelectionPoint(text, range.last + 1))
        onStart()
        return true
    }

    /** Starts dragging the stored start ([start]) or end handle from [position]. */
    fun startHandle(start: Boolean, position: Offset) {
        handle = start
        pointer = toWindow(position)
    }

    /** Moves a long-press drag to [position]. */
    fun dragTo(position: Offset) {
        pointer = toWindow(position)
        update(position, haptic = true)
    }

    /** Moves a handle drag by [delta]. */
    fun dragBy(delta: Offset) {
        if (!pointer.isSpecified) return
        pointer += delta
        toLocal(pointer)?.let { update(it, haptic = true) }
    }

    fun end() {
        // The last scroll has been laid out by the time the pointer goes up; follow it.
        if (scrolled) toLocal(pointer)?.let { update(it, haptic = false) }
        pointer = Offset.Unspecified
        scrolled = false
    }

    /** Where the drag is, in container coordinates, for the magnifier. */
    fun magnifierCenter(): Offset = pointer.takeIf { it.isSpecified }?.let(::toLocal) ?: Offset.Unspecified

    /**
     * Scrolls by up to [step] while the drag is within [edge] of the visible
     * edge, faster the closer it gets. Vertically that's the edge of the
     * container's visible part, horizontally the edge of the text under the
     * drag's. The scroll is dispatched from that text, so the scrollable
     * containers around it take it, nearest first; whether anything scrolls
     * is up to them.
     */
    fun autoScroll(edge: Float, step: Float) {
        val pointer = pointer.takeIf { it.isSpecified } ?: return
        val container = registry.containerCoordinates?.takeIf { it.isAttached } ?: return
        val local = container.windowToLocal(pointer)
        if (scrolled) update(local, haptic = false)
        val text = registry.pointAt(local)?.text ?: return
        val coordinates = text.coordinates?.takeIf { it.isAttached } ?: return
        fun speed(depth: Float) = step * (depth / edge).coerceIn(0f, 1f)
        val visible = registry.visibleBounds(container) ?: return
        // Vertically, the edge of the container's visible part is a viewport's edge, whether the
        // container scrolls in a page or a list scrolls inside it.
        val dy = speed(visible.top + edge - local.y) - speed(local.y - visible.bottom + edge)
        // Horizontally, a code block or table that scrolls sideways cuts off the text under the drag.
        var dx = 0f
        val textVisible = registry.visibleBounds(coordinates)
        if (textVisible != null) {
            val x = coordinates.windowToLocal(pointer).x
            if (textVisible.left > 0.5f) dx += speed(textVisible.left + edge - x)
            if (textVisible.right < coordinates.size.width - 0.5f) dx -= speed(x - textVisible.right + edge)
        }
        val delta = Offset(dx, dy)
        if (delta == Offset.Zero) {
            scrolled = false
            return
        }
        // Scrolling outside the container only makes sense where it's cut off; elsewhere only
        // content inside it, such as a list it wraps, scrolls. The pre-scroll, which reaches the
        // container's ancestors first, is skipped on those axes.
        val confineX = dx != 0f && if (dx > 0) visible.left <= 0.5f else visible.right >= container.size.width - 0.5f
        val confineY = dy != 0f && if (dy > 0) visible.top <= 0.5f else visible.bottom >= container.size.height - 0.5f
        confinement.confine(confineX, confineY)
        // Scroll the way a finger dragging over the text would.
        val dispatcher = text.scrollDispatcher
        val preDelta = Offset(if (confineX) 0f else dx, if (confineY) 0f else dy)
        val preConsumed = dispatcher.dispatchPreScroll(preDelta, NestedScrollSource.UserInput)
        val postConsumed = dispatcher.dispatchPostScroll(Offset.Zero, delta - preConsumed, NestedScrollSource.UserInput)
        scrolled = preConsumed + postConsumed - confinement.release() != Offset.Zero
    }

    private fun update(position: Offset, haptic: Boolean) {
        val changed = when (val start = handle) {
            null -> extend(position)
            else -> moveHandle(start, position)
        }
        if (changed && haptic) onMove()
    }

    private fun extend(position: Offset): Boolean {
        val text = anchor ?: return false
        val point = registry.pointAt(position) ?: return false
        val texts = registry.ordered()
        val before = compare(texts, point, SelectionPoint(text, word.first)) < 0
        val next = if (before) {
            Selection(SelectionPoint(text, word.last + 1), point)
        } else {
            val end = if (compare(texts, point, SelectionPoint(text, word.last + 1)) > 0) point else SelectionPoint(text, word.last + 1)
            Selection(SelectionPoint(text, word.first), end)
        }
        if (sameAs(registry.selection, next)) return false
        registry.selection = next
        return true
    }

    /** Moves the stored start ([start]) or end of the selection; returns whether it changed. */
    private fun moveHandle(start: Boolean, position: Offset): Boolean {
        val s = registry.selection ?: return false
        val point = registry.pointAt(position) ?: return false
        val other = if (start) s.end else s.start
        if (point.text === other.text && point.offset == other.offset) return false
        val next = if (start) Selection(point, s.end) else Selection(s.start, point)
        if (sameAs(s, next)) return false
        registry.selection = next
        return true
    }

    private fun toWindow(position: Offset): Offset =
        registry.containerCoordinates?.takeIf { it.isAttached }?.localToWindow(position) ?: Offset.Unspecified

    private fun toLocal(window: Offset): Offset? =
        registry.containerCoordinates?.takeIf { it.isAttached }?.windowToLocal(window)

    private fun compare(texts: List<SelectableText>, a: SelectionPoint, b: SelectionPoint): Int {
        val i = texts.indexOf(a.text).compareTo(texts.indexOf(b.text))
        return if (i != 0) i else a.offset.compareTo(b.offset)
    }

    private fun sameAs(a: Selection?, b: Selection): Boolean =
        a != null && a.start.text === b.start.text && a.start.offset == b.start.offset &&
            a.end.text === b.end.text && a.end.offset == b.end.offset
}

/**
 * During the auto-scroll, takes what's left of the scroll on the confined
 * axes once the content inside the container has scrolled, so it doesn't
 * reach the container's ancestors. Other scrolling passes through.
 */
private class ScrollConfinement : NestedScrollConnection {
    private var x = false
    private var y = false
    private var taken = Offset.Zero

    fun confine(x: Boolean, y: Boolean) {
        this.x = x
        this.y = y
        taken = Offset.Zero
    }

    /** Ends the confinement; returns what it took. */
    fun release(): Offset {
        x = false
        y = false
        return taken
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val take = Offset(if (x) available.x else 0f, if (y) available.y else 0f)
        taken += take
        return take
    }
}

/**
 * A teardrop handle below the selection edge at [point]. [isLeft] handles
 * point up-right at the start of the selection, others up-left at its end.
 * [onDragStart] gets the middle of the handle's line in container
 * coordinates, and [onDrag] how far the handle moved since.
 */
@Composable
private fun SelectionHandle(
    registry: SelectionRegistry,
    point: SelectionPoint,
    isLeft: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    registry.layoutVersion // Reposition when texts move.
    // A dragged handle stays composed even where it isn't drawn: removing its popup would cancel the drag.
    var dragged by remember { mutableStateOf(false) }
    val container = registry.containerCoordinates?.takeIf { it.isAttached } ?: return
    val coordinates = point.text.coordinates?.takeIf { it.isAttached } ?: return
    val handle = point.text.handlePosition(point.offset, isStart = isLeft) ?: return
    val anchor = container.localPositionOf(coordinates, handle.bottom)
    val lineMiddle = (anchor.y - container.localPositionOf(coordinates, Offset(0f, handle.lineTop)).y) / 2
    // Hide the handle when its text is scrolled out of view or under the obscured padding.
    val visible = registry.visibleBounds(container)
    val shown = visible != null && anchor.x >= visible.left - 1 && anchor.x <= visible.right + 1 &&
        anchor.y >= visible.top && anchor.y <= visible.bottom + 1
    if (!shown && !dragged) return

    val color = LocalTextSelectionColors.current.handleColor
    // Keep a hidden, dragged handle's popup on screen.
    val placed = if (shown || visible == null) anchor else Offset(anchor.x.coerceIn(visible.left, visible.right), anchor.y.coerceIn(visible.top, visible.bottom))
    val window = container.localToWindow(placed)
    val size = with(LocalDensity.current) { HandleSize.toPx() }
    val provider = HandlePositionProvider(
        IntOffset((window.x - if (isLeft) size else 0f).roundToInt(), window.y.roundToInt()),
    )
    val currentLineMiddle by rememberUpdatedState(anchor - Offset(0f, lineMiddle))
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Popup(popupPositionProvider = provider, properties = PopupProperties(clippingEnabled = false)) {
        Spacer(
            Modifier
                .size(HandleSize)
                .pointerInput(Unit) {
                    fun end() {
                        dragged = false
                        currentOnDragEnd()
                    }
                    detectDragGestures(
                        onDragStart = {
                            dragged = true
                            currentOnDragStart(currentLineMiddle)
                        },
                        onDragEnd = ::end,
                        onDragCancel = ::end,
                    ) { change, delta ->
                        change.consume()
                        currentOnDrag(delta)
                    }
                }
                .drawBehind {
                    if (!shown) return@drawBehind
                    drawCircle(color, radius = this.size.width / 2)
                    val corner = Size(this.size.width / 2, this.size.height / 2)
                    drawRect(color, topLeft = if (isLeft) Offset(corner.width, 0f) else Offset.Zero, size = corner)
                },
        )
    }
}

private data class HandlePositionProvider(val position: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = position
}
