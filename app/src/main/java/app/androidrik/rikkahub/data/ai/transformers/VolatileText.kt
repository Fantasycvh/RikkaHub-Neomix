package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/** Offsets are UTF-16 offsets in Text parts joined with one newline, never in attachment notes. */
internal fun UIMessage.countingBody(): String =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

/** A conservative set of edited regions. Line-aligned edits stay separate so large stable bodies remain cacheable. */
internal fun changedOutputRange(before: String, after: String): List<IntRange> {
    if (before == after || after.isEmpty()) return emptyList()
    lineAlignedRanges(before, after)?.let { return it }
    val prefix = before.commonPrefixWith(after).length
    val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
    val end = after.length - suffix
    // A deletion still changes the joining boundary. Include a neighbour so it can be re-counted safely.
    return if (prefix < end) listOf(prefix until end)
    else listOf((prefix - 1).coerceAtLeast(0)..prefix.coerceAtMost(after.lastIndex))
}

/**
 * Same line count ⇒ only differing lines are volatile.
 *
 * Real prompts put placeholders on separate lines (`- Time: {{cur_date}}`), so this keeps the rest of
 * a long system prompt reusable instead of collapsing it into one huge volatile region. Tokenizer-side
 * windowing still expands each range to whole pre-token matches, so this stays conservative.
 */
private fun lineAlignedRanges(before: String, after: String): List<IntRange>? {
    val beforeLines = before.split('\n')
    val afterLines = after.split('\n')
    if (beforeLines.size != afterLines.size) return null
    val ranges = mutableListOf<IntRange>()
    var offset = 0
    for ((index, line) in afterLines.withIndex()) {
        val previous = beforeLines[index]
        if (line != previous) {
            val prefix = line.commonPrefixWith(previous).length
            val suffix = line.substring(prefix).commonSuffixWith(previous.substring(prefix)).length
            val end = line.length - suffix
            // Pure deletion inside a line: cover the whole line so the joining boundary is re-counted.
            if (prefix < end) ranges += (offset + prefix) until (offset + end)
            else if (line.isNotEmpty()) ranges += offset until (offset + line.length)
        }
        offset += line.length + 1
    }
    return ranges.takeIf { it.isNotEmpty() }
}

/** For wrappers/templates, the unchanged body may be retained exactly once inside the output. */
internal fun wrapperOutputRanges(before: String, after: String): List<IntRange> {
    if (before == after || after.isEmpty()) return emptyList()
    val offset = uniqueBodyOffset(before, after)
    if (offset == null) return changedOutputRange(before, after)
    return buildList {
        if (offset > 0) add(0 until offset)
        if (offset + before.length < after.length) add(offset + before.length until after.length)
    }
}

private fun uniqueBodyOffset(before: String, after: String): Int? {
    if (before.isEmpty()) return null
    val offset = after.indexOf(before)
    return offset.takeIf { it >= 0 && after.indexOf(before, it + 1) < 0 }
}

/** Retain annotations only where the original bytes provably survive the next transform. */
internal fun rebaseVolatileRanges(before: String, after: String, ranges: List<IntRange>): List<IntRange> {
    if (before == after) return ranges
    uniqueBodyOffset(before, after)?.let { offset ->
        return ranges.map { (it.first + offset)..(it.last + offset) }
    }
    val prefix = before.commonPrefixWith(after).length
    val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
    val shift = after.length - before.length
    return ranges.mapNotNull { range ->
        when {
            range.last < prefix -> range
            range.first >= before.length - suffix -> (range.first + shift)..(range.last + shift)
            else -> null // overwritten or ambiguous: exact whole-text fallback remains available
        }
    }
}

internal fun mergeVolatileRanges(ranges: List<IntRange>, length: Int): List<IntRange> {
    val result = mutableListOf<IntRange>()
    ranges.filter { !it.isEmpty() && it.first >= 0 && it.last < length }
        .sortedBy { it.first }.forEach { range ->
            val last = result.lastOrNull()
            if (last != null && range.first.toLong() <= last.last.toLong() + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, range.last)
            } else result += range
        }
    return result
}
