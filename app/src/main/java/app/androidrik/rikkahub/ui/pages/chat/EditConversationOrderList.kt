package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.Pin
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Conversation

/**
 * 「编辑顺序」模式（设计稿 `docs/design-conversation-manual-order.md` §四）。
 *
 * 交互与约束：
 * - **长按即拖**（不常驻拖拽手柄）：会话行的长按在浏览态用于弹出菜单，所以拖拽只在
 *   本模式内生效——这正是「进入后长按即拖、退出即锁定」的由来（设计稿 §一.4）。
 * - **档内拖动**：置顶档在前、非置顶档在后，两档各自可拖；跨越档位边界的拖动被忽略
 *   （顺序只在档内定义，跨档意味着"改档位"，那要用置顶/取消置顶的语义，见 §一.6）。
 * - **松手才落库**：拖动过程中只改本地顺序（不动数据库），松手时把「被拖项 + 新邻居」
 *   交给仓库；仓库按需只写一行或整档重排（设计稿 §三.5）。
 */
@Composable
fun EditConversationOrderList(
    conversations: List<Conversation>,
    onPersistMove: (movedId: Uuid, pinned: Boolean, beforeId: Uuid?, afterId: Uuid?) -> Unit,
    onExit: () -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    // 本地顺序镜像：拖动期间以它为准；数据源变化（落库后 Flow 重发）时重新同步
    val items = remember { mutableStateListOf<Conversation>() }
    LaunchedEffect(conversations) {
        if (items.map { it.id } != conversations.map { it.id }) {
            items.clear()
            items.addAll(conversations)
        }
    }
    val haptic = LocalHapticFeedback.current
    // 拖动开始时的顺序快照：松手时用它判断「这次拖动真的换了位吗」。
    // 没有它就会在"长按但没挪动"时也落库，把整档白物化一次（真机实测踩到）。
    var orderAtDragStart by remember { mutableStateOf<List<Uuid>?>(null) }

    /** 松手：按「同档邻居」算出落点交给仓库（跨档边界的拖动在 onMove 已被拒，这里不会出现）。 */
    fun persistMove(moved: Conversation) {
        val index = items.indexOfFirst { it.id == moved.id }
        if (index < 0) return
        val before = items.getOrNull(index - 1)?.takeIf { it.isPinned == moved.isPinned }
        val after = items.getOrNull(index + 1)?.takeIf { it.isPinned == moved.isPinned }
        onPersistMove(moved.id, moved.isPinned, before?.id, after?.id)
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditOrderHeader(onExit = onExit, onResetOrder = onResetOrder)

        val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
            // 用 Lazy 列表自己的下标（本列表第一项就是第一条会话，索引与 items 对齐）：
            // 早期写成 `it.id.toString() == from.key` 是错的——`from.key` 是 Uuid **对象**，
            // 与字符串永不相等，于是换位静默失效、只在松手时白物化一次整档（真机实测）。
            val move = computeReorder(
                size = items.size,
                fromIndex = from.index,
                toIndex = to.index,
                isPinnedAt = { items[it].isPinned },
            ) ?: return@rememberReorderableLazyListState
            items.add(move.second, items.removeAt(move.first))
            // 跨档位 / 越界 / 原地：什么都不做（条目会弹回原位）
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { conversation ->
                ReorderableItem(reorderableState, key = conversation.id) { _ ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    orderAtDragStart = items.map { it.id }
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    val start = orderAtDragStart
                                    orderAtDragStart = null
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    // 只有真的换了位才落库（长按但没挪动 ⇒ 一次写都不该发生）
                                    if (start != null && start != items.map { it.id }) {
                                        persistMove(conversation)
                                    }
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.DragDropVertical,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_message) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (conversation.isPinned) {
                            Icon(
                                imageVector = HugeIcons.Pin,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(12.dp)) }
        }
    }
}

/**
 * 拖动落点的**纯函数**（便于单测）：把第 [fromIndex] 项移到第 [toIndex] 项的位置。
 *
 * 返回 `null` 表示「这一次不动」——越界（例如拖到列表末尾的 Spacer 上）、原地、
 * 或者**跨档位**（置顶↔未置顶；跨档等于改档位，那是置顶/取消置顶的语义，不是排序）。
 */
internal fun computeReorder(
    size: Int,
    fromIndex: Int,
    toIndex: Int,
    isPinnedAt: (Int) -> Boolean,
): Pair<Int, Int>? {
    if (fromIndex !in 0 until size || toIndex !in 0 until size) return null
    if (fromIndex == toIndex) return null
    if (isPinnedAt(fromIndex) != isPinnedAt(toIndex)) return null
    return fromIndex to toIndex
}

/** 模式内的说明条（设计稿 §四.2 要求把「置顶不改档内位置」讲在前面，而不是让用户拖完才发现）。 */
@Composable
private fun EditOrderHeader(
    onExit: () -> Unit,
    onResetOrder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.conversation_order_editing),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetOrder) {
                Text(stringResource(R.string.conversation_order_reset))
            }
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.conversation_order_done))
            }
        }
        Text(
            text = stringResource(R.string.conversation_order_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
