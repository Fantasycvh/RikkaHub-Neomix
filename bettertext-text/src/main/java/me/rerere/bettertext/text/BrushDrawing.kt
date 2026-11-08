package me.rerere.bettertext.text

import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint

/** Draw-only extension: shader dimensions never affect measuring, hit testing or selection. */
internal fun StaticLayout.drawWithBrush(canvas: Canvas, brush: Brush?, alpha: Float, brushPaint: Paint) {
    if (brush == null) {
        draw(canvas)
        return
    }
    val oldShader = paint.shader
    val oldColor = paint.color
    val oldAlpha = paint.alpha
    try {
        brush.applyTo(Size(width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat()), brushPaint,
            if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 1f)
        val native = brushPaint.asFrameworkPaint()
        paint.shader = native.shader
        paint.color = native.color
        paint.alpha = native.alpha
        draw(canvas)
    } finally {
        paint.shader = oldShader
        paint.color = oldColor
        paint.alpha = oldAlpha
    }
}

/** A solid link or syntax span must opt out of the inherited paragraph shader. */
internal class SolidForegroundSpan(color: Int) : ForegroundColorSpan(color) {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.shader = null
        super.updateDrawState(textPaint)
    }
}
