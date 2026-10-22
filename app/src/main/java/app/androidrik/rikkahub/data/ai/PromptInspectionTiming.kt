package app.androidrik.rikkahub.data.ai

/** Content-free timings: never stores prompt text, tool arguments or conversation identifiers. */
class PromptInspectionTiming(private val clock: () -> Long = System::nanoTime) {
    private val started = clock()
    private var last = started
    private val stages = linkedMapOf<String, Long>()

    fun mark(stage: String) {
        val now = clock()
        record(stage, now - last)
        last = now
    }

    fun record(stage: String, nanos: Long) {
        stages[stage] = (stages[stage] ?: 0L) + nanos.coerceAtLeast(0)
    }

    /** Transformer entries are nested in transforms, not additive to total. */
    fun summary(): String = buildString {
        append("totalMs=").append((clock() - started).coerceAtLeast(0) / 1_000_000)
        stages.forEach { (name, nanos) -> append(' ').append(name).append("Ms=").append(nanos / 1_000_000) }
    }
}
