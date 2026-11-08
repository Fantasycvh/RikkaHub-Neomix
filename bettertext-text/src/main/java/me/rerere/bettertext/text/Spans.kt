package me.rerere.bettertext.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Sets the text size to [size] pixels, or multiplies it by [size] when [relative]. */
internal class TextSizeSpan(private val size: Float, private val relative: Boolean) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) {
        tp.textSize = if (relative) tp.textSize * size else size
    }

    override fun updateDrawState(tp: TextPaint) = updateMeasureState(tp)
}

/** Sets the typeface; `android.text.style.TypefaceSpan(Typeface)` needs API 28. */
internal class TypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = typeface
    }

    override fun updateDrawState(tp: TextPaint) = updateMeasureState(tp)
}

/** Letter spacing in em, or in pixels when [pixels]. */
internal class LetterSpacingSpan(private val value: Float, private val pixels: Boolean) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) {
        tp.letterSpacing = if (pixels) value / tp.textSize else value
    }

    override fun updateDrawState(tp: TextPaint) = updateMeasureState(tp)
}

/** Shifts the baseline by [multiplier] times the ascent, like Compose's `BaselineShift`. */
internal class BaselineShiftSpan(private val multiplier: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) {
        tp.baselineShift += ceil(tp.ascent() * multiplier).toInt()
    }

    override fun updateDrawState(tp: TextPaint) = updateMeasureState(tp)
}

internal class FontFeatureSpan(private val settings: String) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) {
        tp.fontFeatureSettings = settings
    }

    override fun updateDrawState(tp: TextPaint) = updateMeasureState(tp)
}

/**
 * Makes every line at least as tall as a strut of [ascent] + [descent] pixels
 * around the baseline, like CSS `line-height`. Unlike Compose's line height,
 * lines with taller content grow instead of overlapping their neighbors.
 *
 * The line's own extent is measured here rather than taken from the metrics
 * `StaticLayout` passes in: when a line breaks inside a span run, `StaticLayout`
 * seeds the next line with the metrics a `LineHeightSpan` returned for the
 * previous one, so a tall formula would make the following line tall too.
 *
 * @param paint the base paint of the text; spans are applied to a copy.
 */
internal class StrutSpan(
    private val ascent: Int,
    private val descent: Int,
    private val paint: TextPaint,
) : LineHeightSpan {
    private val workPaint = TextPaint()
    private val runFm = Paint.FontMetricsInt()

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        var lineAscent = -ascent
        var lineDescent = descent
        val spanned = text as? Spanned
        var i = start
        while (i < end) {
            val next = spanned?.nextSpanTransition(i, end, MetricAffectingSpan::class.java) ?: end
            measureRun(text, spanned, i, next)
            lineAscent = minOf(lineAscent, runFm.ascent)
            lineDescent = maxOf(lineDescent, runFm.descent)
            i = next
        }
        fm.ascent = lineAscent
        fm.descent = lineDescent
        fm.top = lineAscent
        fm.bottom = lineDescent
    }

    /** Measures `[start, end)` into [runFm] the way `MeasuredParagraph` does. */
    private fun measureRun(text: CharSequence, spanned: Spanned?, start: Int, end: Int) {
        workPaint.set(paint)
        var replacement: ReplacementSpan? = null
        spanned?.getSpans(start, end, MetricAffectingSpan::class.java)?.forEach {
            if (it is ReplacementSpan) replacement = it else it.updateMeasureState(workPaint)
        }
        workPaint.getFontMetricsInt(runFm)
        val r = replacement
        if (r != null) {
            r.getSize(workPaint, text, start, end, runFm)
        } else if (Build.VERSION.SDK_INT >= 33) {
            // Includes fallback fonts, e.g. CJK, which are taller than the primary font.
            // Older versions use the primary font's metrics.
            workPaint.getFontMetricsInt(text, start, end - start, start, end - start, false, runFm)
        }
        val shift = workPaint.baselineShift
        if (shift < 0) runFm.ascent += shift else runFm.descent += shift
    }
}

/**
 * Reports a box of [width] × ([ascent] + [descent]) pixels as the metrics of a
 * replaced character. The line keeps at least the metrics of the surrounding
 * font, so a small item doesn't make its line shorter.
 */
private fun ReplacementSpan.reportSize(
    paint: Paint,
    fm: Paint.FontMetricsInt?,
    width: Float,
    ascent: Float,
    descent: Float,
): Int {
    if (fm != null) {
        paint.getFontMetricsInt(fm)
        fm.ascent = minOf(fm.ascent, -ceil(ascent).toInt())
        fm.descent = maxOf(fm.descent, ceil(descent).toInt())
        fm.top = minOf(fm.top, fm.ascent)
        fm.bottom = maxOf(fm.bottom, fm.descent)
    }
    return width.roundToInt()
}

/**
 * The color of the text at [start]. The paint handed to a [ReplacementSpan]
 * only has metric-affecting spans applied, so color spans are looked up here.
 */
private fun textColor(text: CharSequence?, start: Int, paint: Paint): Int {
    if (text !is Spanned) return paint.color
    val spans = text.getSpans(start, start + 1, ForegroundColorSpan::class.java)
    return spans.lastOrNull()?.foregroundColor ?: paint.color
}

/** One segment of a [DrawnInline]. */
internal class DrawnSegmentSpan(private val inline: DrawnInline, private val segment: Int) : ReplacementSpan() {
    private val metrics = InlineMetrics()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        inline.measure(segment, paint, metrics)
        return reportSize(paint, fm, metrics.width, metrics.ascent, metrics.descent)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        paint.color = textColor(text, start, paint)
        inline.draw(segment, canvas, x, y.toFloat(), paint)
    }
}

/**
 * Reserves space for a [ComposableInline]; the composable is placed on top.
 * [setSize] must be called with the measured size before layout.
 */
internal class ComposableSpan(val inline: ComposableInline) : ReplacementSpan() {
    var width = 0
        private set
    var height = 0
        private set
    private var baseline = 0

    /** Top of the composable relative to the text baseline, set when measured by the layout. */
    var top = 0
        private set

    /** Returns whether the size changed. */
    fun setSize(width: Int, height: Int, baseline: Int): Boolean {
        if (width == this.width && height == this.height && baseline == this.baseline) return false
        this.width = width
        this.height = height
        this.baseline = baseline
        return true
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        top = when (inline.align) {
            InlineAlign.Baseline -> -baseline
            InlineAlign.Center -> ((paint.ascent() + paint.descent()) / 2f - height / 2f).roundToInt()
        }
        return reportSize(paint, fm, width.toFloat(), -top.toFloat(), (height + top).toFloat())
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) = Unit
}
