package app.androidrik.rikkahub.utils

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 拖拽排序的「先本地、松手才落盘」状态（ModelList dc2a43003 / 上游 395a0ee0 的通用化）。
 *
 * 抽搐根因：设置走 DataStore 异步 round-trip，拖到列表边缘自动滚动时拖拽库每帧都会按
 * 当前布局再算一次移动——onMove 里直接写设置，异步回来的旧顺序会把刚做的移动反复改回去，
 * 看起来就是来回抽搐。拖动期间只改 [pendingOrder]（显示列表优先读它），松手才经 [onDragStopped]
 * 落盘；落盘后的设置回流、源列表追上本地顺序时由 [syncFromSource] 撤掉本地顺序，避免闪回。
 *
 * 用法（三件套）：
 * ```
 * val dragOrder = remember { DragOrderState<Foo>() }
 * // 显示：dragOrder.pendingOrder ?: sourceList
 * reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
 *     dragOrder.onMove(sourceList) { it.reorderForDrag(from.index, to.index) }
 * }
 * // 拖拽手柄 onDragStopped：dragOrder.onDragStopped { order -> vm.updateSettings(...) }
 * LaunchedEffect(sourceList) { dragOrder.syncFromSource(sourceList) }
 * ```
 */
@Stable
class DragOrderState<T> {
    var pendingOrder: List<T>? by mutableStateOf(null)
        private set

    /** 是否已把本地顺序交给落盘（落盘后、设置回流前保持本地顺序，防止闪回旧顺序） */
    private var committed = false

    /** onMove 回调里调：基于当前顺序（优先本地 pending）做移动，只改本地不落盘 */
    fun onMove(current: List<T>, move: (List<T>) -> List<T>) {
        pendingOrder = move(pendingOrder ?: current)
    }

    /** 拖拽手柄 onDragStopped 里调：把最终顺序交给落盘方 */
    fun onDragStopped(commit: (List<T>) -> Unit) {
        val order = pendingOrder ?: return
        committed = true
        commit(order)
    }

    /** 源列表（设置回流）每次更新时调：已落盘且源列表已追上后撤掉本地顺序 */
    fun syncFromSource(source: List<T>) {
        if (!committed) return
        pendingOrder = null
        committed = false
    }

    /** 显示列表与源列表脱钩时（开启过滤/搜索）丢弃本地顺序，拖拽在该状态下本来就被禁用 */
    fun reset() {
        pendingOrder = null
        committed = false
    }
}
