package app.androidrik.rikkahub.utils

import app.androidrik.common.tokenizer.TokenizerRegistry
import java.util.concurrent.ConcurrentHashMap

/** Request-scoped exact counts. Stable chunks reuse existing memory/disk caches, never normalized counts. */
internal class VolatileTokenCounter(private val annotations: Map<String, List<IntRange>>) {
    private val counts = ConcurrentHashMap<String, Int>()

    fun count(text: String): Int = counts.computeIfAbsent(text) {
        // Fast path first: if the exact text was counted before, the answer is already known and
        // the whole-text cache hit costs a hash instead of a full pre-tokenizer scan (which runs
        // ~100x a plain char loop even in a debug build). Only a miss needs the span split.
        TokenCountDiskCache.get(text)?.let { return@computeIfAbsent it }
        val spans = annotations[text].orEmpty()
        val tokenizer = TokenizerRegistry.current
        val result = if (spans.isEmpty() || tokenizer == null) {
            estimateTokens(text)
        } else {
            val rejected = spans.any { it.isEmpty() || it.first < 0 || it.last >= text.length }
            if (rejected) {
                android.util.Log.d(
                    "PromptInspectionPerf",
                    "volatile spans rejected len=${text.length} spans=${spans.size}",
                )
                estimateTokens(text)
            } else {
                tokenizer.countWithVolatileSpans(
                    text,
                    spans,
                    minimumContextChars = 16,
                    countStable = ::estimateTokens,
                    onStats = { stable, volatile, chunks, splitNanos, countNanos ->
                        android.util.Log.d(
                            "PromptInspectionPerf",
                            "volatile split len=${text.length} stable=$stable volatile=$volatile " +
                                "chunks=$chunks splitMs=${splitNanos / 1_000_000} countMs=${countNanos / 1_000_000}",
                        )
                    },
                )
            }
        }
        // The split total is exact, so remembering it turns the next identical request into a
        // single hash lookup instead of another full pre-tokenizer scan.
        TokenCountDiskCache.put(text, result)
        result
    }

}

/** Project offsets from joined Text parts into a single original part (attachment parts consume no offset). */
internal fun partVolatileRanges(ranges: List<IntRange>, offset: Int, length: Int): List<IntRange> =
    ranges.mapNotNull { range ->
        val start = maxOf(range.first, offset)
        val end = minOf(range.last.toLong() + 1, offset.toLong() + length).toInt()
        if (start < end) (start - offset) until (end - offset) else null
    }
