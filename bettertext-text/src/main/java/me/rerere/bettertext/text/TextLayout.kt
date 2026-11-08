package me.rerere.bettertext.text

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

/**
 * Text converted to an Android [Spanned] with its paint, ready to be laid out
 * at any width. Font sizes, typefaces and inline items are resolved here, so
 * only the composable inline sizes and the width vary between layouts.
 */
internal class PreparedText(
    val spanned: Spanned,
    /** Inline items in text order; offsets are in [spanned]. */
    val items: List<InlineItem>,
    val paint: TextPaint,
    val alignment: Layout.Alignment,
    val justify: Boolean,
    /** Links with their ranges in [spanned]. */
    val links: List<LinkRange>,
    /** Composable inline items in text order, with their offsets in [spanned]. */
    val composables: List<ComposableSpan>,
    val composableOffsets: IntArray,
) {
    companion object {
        fun create(
            text: AnnotatedString,
            inlineContent: Map<String, BetterInline>,
            style: TextStyle,
            density: Density,
            resolveTypeface: (FontFamily?, FontWeight, FontStyle, FontSynthesis) -> Typeface,
        ): PreparedText {
            val expanded = expandInlines(text, inlineContent)
            val spanned = SpannableString(expanded.text)
            val baseSize = with(density) {
                when {
                    style.fontSize.isSp -> style.fontSize.toPx()
                    style.fontSize.isEm -> 14.sp.toPx() * style.fontSize.value
                    else -> 14.sp.toPx()
                }
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.density = density.density
                textSize = baseSize
                color = (if (style.color.isSpecified) style.color else Color.Black).toArgb()
                typeface = resolveTypeface(
                    style.fontFamily,
                    style.fontWeight ?: FontWeight.Normal,
                    style.fontStyle ?: FontStyle.Normal,
                    style.fontSynthesis ?: FontSynthesis.All,
                )
                letterSpacing = letterSpacingEm(style.letterSpacing, baseSize, density) ?: 0f
                style.textDecoration?.let {
                    isUnderlineText = TextDecoration.Underline in it
                    isStrikeThruText = TextDecoration.LineThrough in it
                }
                style.fontFeatureSettings?.let { fontFeatureSettings = it }
            }
            if (style.background.isSpecified) {
                spanned.setSpan(BackgroundColorSpan(style.background.toArgb()), 0, spanned.length, SPAN_FLAGS)
            }

            val links = text.getLinkAnnotations(0, text.length).mapNotNull { range ->
                val start = expanded.offsets[range.start]
                val end = expanded.endOffsets[range.end]
                if (start < end) LinkRange(range.item, start, end) else null
            }
            // Span styles, then link styles over them, in offsets of the expanded text.
            val styles = text.spanStyles.mapNotNull { range ->
                val start = expanded.offsets[range.start]
                val end = expanded.endOffsets[range.end]
                if (start < end) AnnotatedString.Range(range.item, start, end) else null
            } + links.mapNotNull { link -> link.link.styles?.style?.let { AnnotatedString.Range(it, link.start, link.end) } }
            for (range in styles) applySpanStyle(spanned, range.item, range.start, range.end, density)
            applyTypefaces(spanned, styles, style, resolveTypeface)

            val composables = ArrayList<ComposableSpan>()
            val composableOffsets = ArrayList<Int>()
            for (item in expanded.items) {
                when (val content = item.content) {
                    is DrawnInline -> repeat(item.segments) { segment ->
                        val at = item.start + segment
                        spanned.setSpan(DrawnSegmentSpan(content, segment), at, at + 1, SPAN_FLAGS)
                    }

                    is ComposableInline -> {
                        val span = ComposableSpan(content)
                        spanned.setSpan(span, item.start, item.start + 1, SPAN_FLAGS)
                        composables.add(span)
                        composableOffsets.add(item.start)
                    }
                }
            }

            lineHeightPx(style.lineHeight, baseSize, density)?.let { lineHeight ->
                // Center the natural font height in the line, like CSS half-leading.
                val ascent = -paint.ascent()
                val descent = paint.descent()
                val strutAscent = (ascent + (lineHeight - ascent - descent) / 2f).roundToInt()
                val strutDescent = lineHeight.roundToInt() - strutAscent
                spanned.setSpan(StrutSpan(strutAscent, strutDescent, paint), 0, spanned.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }

            val alignment = when (style.textAlign) {
                TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
                TextAlign.End, TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            return PreparedText(
                spanned,
                expanded.items,
                paint,
                alignment,
                style.textAlign == TextAlign.Justify,
                links,
                composables,
                composableOffsets.toIntArray(),
            )
        }

        private fun applySpanStyle(spanned: SpannableString, style: SpanStyle, start: Int, end: Int, density: Density) {
            fun set(span: Any) = spanned.setSpan(span, start, end, SPAN_FLAGS)
            if (style.color.isSpecified) set(SolidForegroundSpan(style.color.toArgb()))
            when (style.fontSize.type) {
                TextUnitType.Sp -> set(TextSizeSpan(with(density) { style.fontSize.toPx() }, relative = false))
                TextUnitType.Em -> set(TextSizeSpan(style.fontSize.value, relative = true))
                else -> Unit
            }
            when (style.letterSpacing.type) {
                TextUnitType.Sp -> set(LetterSpacingSpan(with(density) { style.letterSpacing.toPx() }, pixels = true))
                TextUnitType.Em -> set(LetterSpacingSpan(style.letterSpacing.value, pixels = false))
                else -> Unit
            }
            if (style.background.isSpecified) set(BackgroundColorSpan(style.background.toArgb()))
            style.textDecoration?.let {
                if (TextDecoration.Underline in it) set(UnderlineSpan())
                if (TextDecoration.LineThrough in it) set(StrikethroughSpan())
            }
            style.baselineShift?.let { set(BaselineShiftSpan(it.multiplier)) }
            style.fontFeatureSettings?.let { set(FontFeatureSpan(it)) }
        }

        /**
         * Font family, weight, style and synthesis combine across overlapping
         * spans, so the text is split where any of them changes and each piece
         * gets one resolved typeface, as Compose does.
         */
        private fun applyTypefaces(
            spanned: SpannableString,
            styles: List<AnnotatedString.Range<SpanStyle>>,
            base: TextStyle,
            resolveTypeface: (FontFamily?, FontWeight, FontStyle, FontSynthesis) -> Typeface,
        ) {
            val ranges = styles.filter {
                val s = it.item
                s.fontFamily != null || s.fontWeight != null || s.fontStyle != null || s.fontSynthesis != null
            }
            if (ranges.isEmpty()) return
            val points = ranges.flatMap { listOf(it.start, it.end) }.distinct().sorted()
            for (i in 0 until points.lastIndex) {
                val start = points[i]
                val end = points[i + 1]
                var family = base.fontFamily
                var weight = base.fontWeight ?: FontWeight.Normal
                var fontStyle = base.fontStyle ?: FontStyle.Normal
                var synthesis = base.fontSynthesis ?: FontSynthesis.All
                var covered = false
                for (range in ranges) {
                    if (range.start > start || range.end < end) continue
                    covered = true
                    val s = range.item
                    s.fontFamily?.let { family = it }
                    s.fontWeight?.let { weight = it }
                    s.fontStyle?.let { fontStyle = it }
                    s.fontSynthesis?.let { synthesis = it }
                }
                if (covered) {
                    spanned.setSpan(TypefaceSpan(resolveTypeface(family, weight, fontStyle, synthesis)), start, end, SPAN_FLAGS)
                }
            }
        }

        private fun letterSpacingEm(value: TextUnit, fontSize: Float, density: Density): Float? = when (value.type) {
            TextUnitType.Sp -> with(density) { value.toPx() } / fontSize
            TextUnitType.Em -> value.value
            else -> null
        }

        private fun lineHeightPx(value: TextUnit, fontSize: Float, density: Density): Float? = when (value.type) {
            TextUnitType.Sp -> with(density) { value.toPx() }
            TextUnitType.Em -> value.value * fontSize
            else -> null
        }
    }
}

/**
 * Lays out a [PreparedText], caching the last layout. Call [measureComposables]
 * first whenever the composable inline sizes may have changed.
 */
internal class TextLayoutCache(private val prepared: PreparedText) {
    private var desiredWidth = Float.NaN
    private var cached: StaticLayout? = null
    private var cachedWidth = -1
    private var cachedMaxLines = -1

    /** Updates the sizes of composable items; `sizes` holds width, height and baseline per item. */
    fun measureComposables(sizes: IntArray) {
        var changed = false
        for ((i, span) in prepared.composables.withIndex()) {
            changed = span.setSize(sizes[i * 3], sizes[i * 3 + 1], sizes[i * 3 + 2]) or changed
        }
        if (changed) {
            desiredWidth = Float.NaN
            cached = null
        }
    }

    /**
     * Lays out the text as wide as its longest paragraph, within
     * [minWidth]..[maxWidth] ([maxWidth] may be [Int.MAX_VALUE]).
     */
    fun layout(minWidth: Int, maxWidth: Int, maxLines: Int): StaticLayout {
        if (desiredWidth.isNaN()) desiredWidth = Layout.getDesiredWidth(prepared.spanned, prepared.paint)
        val width = ceil(desiredWidth).toInt().coerceIn(minWidth, maxOf(minWidth, maxWidth))
        cached?.let { if (cachedWidth == width && cachedMaxLines == maxLines) return it }
        val text = prepared.spanned
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, prepared.paint, width)
            .setAlignment(prepared.alignment)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(maxLines)
        if (maxLines != Int.MAX_VALUE) builder.setEllipsize(TextUtils.TruncateAt.END)
        if (prepared.justify && Build.VERSION.SDK_INT >= 26) {
            // Lint wants LineBreaker's constant, which has the same value but needs API 29.
            @SuppressLint("WrongConstant")
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
        return builder.build().also {
            cached = it
            cachedWidth = width
            cachedMaxLines = maxLines
        }
    }

    /** Position of composable item [index] in [layout], or null if it was cut off by `maxLines`. */
    fun composablePosition(layout: StaticLayout, index: Int): Pair<Int, Int>? {
        val offset = prepared.composableOffsets[index]
        val line = layout.getLineForOffset(offset)
        val ellipsis = layout.getEllipsisCount(line)
        if (offset >= layout.getLineEnd(line) ||
            (ellipsis > 0 && offset >= layout.getLineStart(line) + layout.getEllipsisStart(line))
        ) {
            return null
        }
        val span = prepared.composables[index]
        var x = layout.getPrimaryHorizontal(offset)
        if (layout.isRtlCharAt(offset)) x -= span.width
        return x.roundToInt() to layout.getLineBaseline(line) + span.top
    }
}

internal class LinkRange(val link: LinkAnnotation, val start: Int, val end: Int)

/** The index of the character under [x], [y] in [layout], or -1 if the point is beside the text. */
internal fun Layout.charIndexAt(x: Float, y: Float): Int {
    if (y < 0 || y >= height) return -1
    val line = getLineForVertical(y.toInt())
    if (x < getLineLeft(line) || x > getLineRight(line)) return -1
    val start = getLineStart(line)
    val end = getLineEnd(line)
    if (start == end) return -1
    // The offset after a wrapped line's last character is on the next line, so that character's far edge
    // can't be measured. The characters of a line cover it, so it's the one under x when no other is.
    val wrapped = line < lineCount - 1
    val measurable = if (wrapped) end - 1 else end
    val path = Path()
    val bounds = RectF()
    fun charContains(offset: Int): Boolean {
        // Selection resolves both edges using the character's actual bidi run. Choosing primary or
        // secondary carets by direction alone fails for nested runs, such as digits inside RTL text.
        // Both offsets must be on this line, otherwise the selection also covers the line break.
        getSelectionPath(offset, offset + 1, path)
        path.computeBounds(bounds, true)
        return x >= bounds.left && x < bounds.right
    }
    // getOffsetForHorizontal returns the nearest boundary; the character under x is on one side of it,
    // which in mixed-direction text may not be the logical neighbor, so check all as a fallback.
    val offset = getOffsetForHorizontal(line, x)
    for (candidate in intArrayOf(offset - 1, offset)) {
        if (candidate in start until measurable && charContains(candidate)) return candidate
    }
    for (candidate in start until measurable) {
        if (charContains(candidate)) return candidate
    }
    return if (wrapped) end - 1 else offset.coerceIn(start, end - 1)
}
