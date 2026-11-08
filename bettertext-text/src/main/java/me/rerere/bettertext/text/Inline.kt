package me.rerere.bettertext.text

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString

/** Annotation tag marking the alt text of an inline item; the annotation value is its id. */
internal const val INLINE_TAG = "me.rerere.bettertext.inline"

/**
 * Content placed inside the text of a [BetterText], looked up by the id given
 * to [appendInline]. Each line grows to fit the inline items on it.
 */
sealed interface BetterInline

/**
 * Inline content drawn directly with the text, measured with the paint of the
 * surrounding text so it follows its font size and color.
 *
 * It can consist of several unbreakable segments; lines may wrap between them.
 */
abstract class DrawnInline : BetterInline {
    open val segmentCount: Int get() = 1

    /** Measures [segment] in pixels. [paint] has the size and typeface of the surrounding text. */
    abstract fun measure(segment: Int, paint: Paint, metrics: InlineMetrics)

    /**
     * Draws [segment] with its left edge at [x] and baseline at [baseline].
     * [paint]'s color is the surrounding text color; it may be modified.
     */
    abstract fun draw(segment: Int, canvas: Canvas, x: Float, baseline: Float, paint: Paint)
}

/** The size of a [DrawnInline] segment in pixels; [ascent] is above the baseline, [descent] below. */
class InlineMetrics {
    var width = 0f
    var ascent = 0f
    var descent = 0f
}

/** How a [ComposableInline] is placed vertically. */
enum class InlineAlign {
    /** Its first baseline on the text baseline; its bottom when it has no baseline. */
    Baseline,

    /** Centered on the middle of the surrounding text. */
    Center,
}

/**
 * Inline content that is a composable, for interactive items such as citation
 * tags. It's measured with the text's maximum width and never wraps.
 */
class ComposableInline(
    val align: InlineAlign = InlineAlign.Baseline,
    val content: @Composable () -> Unit,
) : BetterInline

/**
 * Appends the inline item [id], resolved through `inlineContent` of [BetterText].
 * [altText] stands for the item in semantics and is shown when [id] is missing.
 * An empty [altText] makes a decoration, such as a swatch before a color code:
 * it's drawn but copies as nothing, and isn't styled by spans that end where it is.
 */
fun AnnotatedString.Builder.appendInline(id: String, altText: String = "\uFFFC") {
    pushStringAnnotation(INLINE_TAG, id)
    append(altText)
    pop()
}
