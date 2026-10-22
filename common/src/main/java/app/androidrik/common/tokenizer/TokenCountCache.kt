package app.androidrik.common.tokenizer

/** Immutable, internally consistent snapshot; counters belong to the current tokenizer installation. */
data class TokenCountCacheStats(
    val hits: Long,
    val misses: Long,
    val entries: Int,
    val retainedTextBytes: Long,
)

/**
 * One tokenizer installation's LRU. All access-order mutations and accounting share a short lock;
 * [compute] always runs outside it. Duplicate concurrent misses may compute independently.
 * Text retention is budgeted as UTF-16 (2 * String.length), not JVM object/heap size.
 */
internal class TokenCountCache(
    private val maxEntries: Int = 512,
    private val maxRetainedTextBytes: Long = 4L * 1024 * 1024,
    private val maxTextBytes: Long = 256L * 1024,
) {
    init {
        require(maxEntries >= 0)
        require(maxRetainedTextBytes >= 0)
        require(maxTextBytes >= 0)
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Int>(64, 0.75f, true)
    private var retainedTextBytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var active = true

    fun count(text: String, compute: () -> Int?): Int? {
        val textBytes = text.length.toLong() * 2
        val cacheable = textBytes <= maxTextBytes && textBytes <= maxRetainedTextBytes && maxEntries > 0
        synchronized(lock) {
            if (active) {
                if (cacheable) entries[text]?.let {
                    hits++
                    return it
                }
                misses++
            }
        }
        val counted = compute() ?: return null
        if (cacheable) synchronized(lock) {
            // Invalidation is permanent: even an in-flight miss cannot refill a released cache.
            if (active) {
                if (entries.put(text, counted) == null) retainedTextBytes += textBytes
                val iterator = entries.entries.iterator()
                while (entries.size > maxEntries || retainedTextBytes > maxRetainedTextBytes) {
                    retainedTextBytes -= iterator.next().key.length.toLong() * 2
                    iterator.remove()
                }
            }
        }
        return counted
    }

    fun snapshot(): TokenCountCacheStats = synchronized(lock) {
        TokenCountCacheStats(hits, misses, entries.size, retainedTextBytes)
    }

    fun invalidate() = synchronized(lock) {
        active = false
        entries.clear()
        retainedTextBytes = 0
        hits = 0
        misses = 0
    }
}
