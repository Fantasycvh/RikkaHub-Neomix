package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.flow.first

/** Wait for this generation's reply and its render entry; a drag abandons this attempt. */
@Composable
internal fun AnchorReplyStartEffect(
    conversationKey: Any,
    generationKey: Any?,
    loading: Boolean,
    dragging: Boolean,
    targetIndex: () -> Int,
    onAnchor: (Int) -> Unit,
) {
    val dragged by rememberUpdatedState(dragging)
    val target by rememberUpdatedState(targetIndex)
    val anchor by rememberUpdatedState(onAnchor)
    LaunchedEffect(conversationKey, generationKey, loading) {
        if (!loading) return@LaunchedEffect
        val (cancelled, index) = snapshotFlow { dragged to target() }
            .first { (dragging, index) -> dragging || index >= 0 }
        if (!cancelled) anchor(index)
    }
}

/**
 * ANCHOR_START 锚定补白（上游 2afc907d2 判据）：锚点要停在视口顶，但锚点之后的内容
 * （生成中的回复）不足一屏时 LazyColumn 会把滚动钳在列表末尾——锚定到不了真正的顶。
 * 给**最后一条消息行**补一段最小高度空白补足一屏；回复变长时空白同步变短，总高度不变，
 * 列表不动。返回值用 [Modifier.anchorTailMinHeight] 挂到最后一条消息行上。
 *
 * 空白并入末行而不是单独 item：补白按上一帧布局算，比内容慢一帧；放单独 item 时流式
 * 内容哪怕缩 1px（Markdown 藏起没写完的语法、思考卡折叠）这一帧就不满屏，列表被往回推、
 * 下一帧再按回顶，生成中持续抖。并入末行自身测量后同帧此消彼长，总高恒定。
 *
 * 空白数学与上游 ChatScrollAnchor 同构，关键在 otherHeight **不含末行自身**：
 * 末行 size 含上一帧已补的空白，扣掉后补白量与自身无关（不动点稳定）；底行可见时经
 * bottom 项端点减末行 size 精确算出，不可见时退化为起-起距离——少算的部分让空白偏大
 * （宁多勿少，多了只是末尾多段可滚动空白，少了锚定就失效）。
 *
 * 作用域 = 生成窗口（[enabled] = ANCHOR_START && loading）：本仓锚点是生成期概念
 * （[AnchorReplyStartEffect] 只在 loading 生效），生成结束清零交还自然布局，不引入
 * 上游「锚点常驻直到下一次发送」的常驻空白语义。
 *
 * [correctionEnabled] 闩锁：补白比内容慢一帧 + 程序化内容收缩的瞬态会让锚点短暂偏离
 * 顶部，生成窗口内自动按回顶；用户一旦滚动（拖动/惯性经过 isScrollInProgress）即闩死
 * 到本次生成结束，锚点让位给用户自选视口。
 */
@Composable
internal fun rememberAnchorTailMinHeight(
    listState: LazyListState,
    enabled: Boolean,
    generationKey: Any?,
    dragging: Boolean,
    anchorRowIndex: () -> Int,
    lastRowKey: Any,
    bottomKey: Any,
): IntState {
    val minHeight = remember { mutableIntStateOf(0) }
    val enabledState by rememberUpdatedState(enabled)
    val draggingState by rememberUpdatedState(dragging)
    val anchorRow by rememberUpdatedState(anchorRowIndex)
    val lastRow by rememberUpdatedState(lastRowKey)
    val correctionEnabled = remember(generationKey) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) correctionEnabled.value = false
        }
    }
    LaunchedEffect(listState) {
        // 显式载体类型：Triple 在 collect 解构里 K2 推不出参数类型，级联到 offset/size 全红
        snapshotFlow { AnchorTailSnapshot(listState.layoutInfo, anchorRow(), lastRow) }
            .collect { snapshot ->
                val info = snapshot.info
                val anchorIndex = snapshot.anchorIndex
                val lastKey = snapshot.lastKey
                if (!enabledState) {
                    minHeight.intValue = 0
                    return@collect
                }
                // 拖动中冻结补白：不追着用户改布局，放手后按当前锚点重算
                if (draggingState) return@collect
                val items = info.visibleItemsInfo
                if (items.isEmpty() || anchorIndex < 0) return@collect
                val visibleHeight = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
                if (anchorIndex > items.last().index) {
                    // 锚点还在视口下（生成起手，回复行刚排进列表）：先补足一屏，锚定滚动才有落点
                    minHeight.intValue = visibleHeight
                    return@collect
                }
                val anchorItem = items.firstOrNull { it.index == anchorIndex } ?: return@collect
                val lastItem = items.firstOrNull { it.key == lastKey } ?: return@collect
                val bottomItem = items.lastOrNull { it.key == bottomKey }
                val otherHeight = if (bottomItem != null) {
                    bottomItem.offset + bottomItem.size - anchorItem.offset - lastItem.size
                } else {
                    lastItem.offset - anchorItem.offset
                }
                minHeight.intValue = (visibleHeight - otherHeight).coerceAtLeast(0)
                if (correctionEnabled.value && !listState.isScrollInProgress &&
                    bottomItem != null && anchorItem.offset != 0
                ) {
                    listState.requestScrollToItem(anchorIndex)
                }
            }
    }
    return minHeight
}

/** 补白重算的快照载体：显式类型让 K2 推断成立（Triple 解构在此推不出）。 */
private data class AnchorTailSnapshot(
    val info: LazyListLayoutInfo,
    val anchorIndex: Int,
    val lastKey: Any,
)

/** 末条消息行至少有 [minHeight] 高，多出来的空白在行下方。 */
internal fun Modifier.anchorTailMinHeight(minHeight: IntState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = maxOf(placeable.height, minHeight.intValue).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, 0) }
}
