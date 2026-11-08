package app.androidrik.rikkahub.data.repository

internal const val MEMORY_ORDER_STEP = 1_000_000L

/** Usually changes one row. Exhausted gaps expand the suffix, never compact deletion gaps. */
internal fun planMemoryDisplayOrder(rows: List<Pair<Int, Long>>, movedIndex: Int): Map<Int, Long> {
    require(movedIndex in rows.indices)
    val previous = rows.getOrNull(movedIndex - 1)?.second
    val next = rows.getOrNull(movedIndex + 1)?.second
    val order = when {
        previous == null && next == null -> MEMORY_ORDER_STEP
        previous == null -> Math.subtractExact(next!!, MEMORY_ORDER_STEP)
        next == null -> Math.addExact(previous, MEMORY_ORDER_STEP)
        next > previous && next - previous > 1L -> previous + (next - previous) / 2
        else -> null
    }
    if (order != null) return mapOf(rows[movedIndex].first to order)
    val updates = linkedMapOf<Int, Long>()
    var cursor = previous!!
    for (index in movedIndex..rows.lastIndex) {
        cursor = Math.addExact(cursor, MEMORY_ORDER_STEP)
        updates[rows[index].first] = cursor
    }
    return updates
}
