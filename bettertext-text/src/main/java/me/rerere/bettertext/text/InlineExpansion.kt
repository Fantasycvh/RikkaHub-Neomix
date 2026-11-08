package me.rerere.bettertext.text

import androidx.compose.ui.text.AnnotatedString

/** The character that stands for one segment of an inline item in the laid-out text. */
internal const val OBJECT_CHAR = '\uFFFC'

/** Follows a decoration, so the line doesn't break between it and the text after it. */
internal const val WORD_JOINER = '\u2060'

/**
 * An inline item occupying `[start, start + segments)` of [ExpandedText.text];
 * [altText] stands for it when the text is copied. A decoration (empty alt
 * text) also covers the [WORD_JOINER] after it.
 */
internal class InlineItem(val content: BetterInline, val start: Int, val segments: Int, val altText: String) {
    val end: Int get() = start + segments + if (altText.isEmpty()) 1 else 0
}

/**
 * [AnnotatedString] text with the alt text of each resolved inline item
 * replaced by one [OBJECT_CHAR] per segment.
 *
 * @property offsets maps each offset of the original text (including its end)
 *   to the expanded text. Offsets inside an alt text map to the item's start.
 * @property endOffsets is [offsets] for the end of a range: it differs only
 *   before an item with an empty alt text, which a range ending there doesn't cover.
 */
internal class ExpandedText(
    val text: String,
    val offsets: IntArray,
    val endOffsets: IntArray,
    val items: List<InlineItem>,
)

internal fun expandInlines(text: AnnotatedString, inlineContent: Map<String, BetterInline>): ExpandedText {
    val source = text.text
    val annotations = text.getStringAnnotations(INLINE_TAG, 0, source.length).sortedBy { it.start }
    val out = StringBuilder(source.length)
    val offsets = IntArray(source.length + 1)
    // Where each item with an empty alt text starts, by its offset in the original text.
    val emptyStarts = HashMap<Int, Int>()
    val items = ArrayList<InlineItem>()
    var pos = 0
    fun copyTo(end: Int) {
        while (pos < end) {
            offsets[pos] = out.length
            out.append(source[pos++])
        }
    }
    for (annotation in annotations) {
        // Nested or overlapping items keep their alt text.
        if (annotation.start < pos || annotation.start > annotation.end) continue
        val content = inlineContent[annotation.item] ?: continue
        val segments = when (content) {
            is DrawnInline -> content.segmentCount
            is ComposableInline -> 1
        }
        copyTo(annotation.start)
        val start = out.length
        for (i in annotation.start until annotation.end) offsets[i] = start
        pos = annotation.end
        if (segments <= 0) continue
        if (annotation.start == annotation.end) emptyStarts.putIfAbsent(annotation.start, start)
        repeat(segments) { out.append(OBJECT_CHAR) }
        if (annotation.start == annotation.end) out.append(WORD_JOINER)
        items.add(InlineItem(content, start, segments, source.substring(annotation.start, annotation.end)))
    }
    copyTo(source.length)
    offsets[source.length] = out.length
    val endOffsets = if (emptyStarts.isEmpty()) {
        offsets
    } else {
        offsets.copyOf().also { for ((at, start) in emptyStarts) it[at] = start }
    }
    return ExpandedText(out.toString(), offsets, endOffsets, items)
}
