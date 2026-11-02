package app.androidrik.rikkahub.data.task

/** UI leases are counted so a floating panel and a full page cannot unregister each other. */
object TaskVisibility {
    private val counts = mutableMapOf<String, Int>()
    @Synchronized fun acquire(key: String): AutoCloseable {
        counts[key] = (counts[key] ?: 0) + 1
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val remaining = (counts[key] ?: 1) - 1
                    if (remaining == 0) counts.remove(key) else counts[key] = remaining
                }
            }
        }
    }
    @Synchronized fun isVisible(runId: String, conversationId: String?): Boolean =
        counts.containsKey("all") || counts.containsKey("run:$runId") ||
            (conversationId != null && counts.containsKey("conversation:$conversationId"))
}
