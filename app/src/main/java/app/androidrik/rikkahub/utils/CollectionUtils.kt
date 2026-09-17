package app.androidrik.rikkahub.utils

fun <E> Collection<E>.checkDifferent(
    other: Collection<E>,
    eq: (E, E) -> Boolean,
): Pair<List<E>, List<E>> {
    val added = other.filter { e ->
        this.none { eq(it, e) }
    }
    val removed = this.filter { e ->
        other.none { eq(it, e) }
    }
    return added to removed
}

/**
 * 拖拽重排：把 [fromIndex] 处的元素移动到 [toIndex]。
 *
 * ⚠️ **不要用 `add(toIndex, removeAt(fromIndex))` 这个流传很广的写法——它会崩**。
 * 拖拽库把「拖到列表末尾之外」表示为 `toIndex == 原列表大小`（=「追加到末尾」的语义），
 * 而该写法里 `removeAt` 会**先**把列表缩短，随后的 `add(原大小, …)` 就越界：
 * 真机实测 `IndexOutOfBoundsException: Index: 3, Size: 2`（3 个条目拖到末尾）。
 *
 * 这里先移除、再按**移除后**的长度夹取下标，故末尾拖拽安全；
 * 越界的 [fromIndex] 原样返回（拖拽库偶发边界值不应导致丢数据）。
 *
 * 所有 `rememberReorderableLazyListState(lazyListState) { from, to -> … }` 回调都应当用它，
 * 而不是各自手写「remove + add」。
 */
fun <T> List<T>.reorderForDrag(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in indices) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex.coerceIn(0, mutable.size), item)
    return mutable
}
