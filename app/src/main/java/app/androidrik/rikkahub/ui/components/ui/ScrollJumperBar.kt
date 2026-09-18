package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUpDouble
import app.androidrik.rikkahub.R

/**
 * 长列表的**滚动跳转悬浮条**：顶尖头 / 上一项 / 下一项 / 底尖头。
 *
 * 从 `ChatList` 的私有 `MessageJumper` 抽出（行为保持一致），供拼装预览页这类长只读列表复用——
 * 预览页的段卡可以高到几十屏（system 段含全部工具 schema），没有跳转手段就只能一路滚。
 *
 * 抽共享而非各写一份：这类悬浮条的外观与手势细节一旦分叉，会出现「一个页面能点、另一个不能」
 * 的隐性不一致（本仓同类教训见侧栏/工作区详情页的文件列表）。
 *
 * @param scope 点击滚动用的协程作用域（由调用方提供，通常 `rememberCoroutineScope()`）
 * @param state 目标列表状态；「下一项」越界由 `animateScrollToItem` 自行夹取
 * @param itemLabel 可选：显示「当前项 / 总项数」，便于在长列表里判断进度（null = 不显示）
 */
@Composable
fun BoxScope.ScrollJumperBar(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState,
    itemLabel: String? = null,
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 进度文字：长列表里「第几项」比方向键更有用（点击穿透到列表，不吞手势）
            itemLabel?.let { label ->
                Surface(
                    shape = CircleShape,
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            JumperButton(
                icon = HugeIcons.ArrowUpDouble,
                contentDescription = stringResource(R.string.chat_page_scroll_to_top),
                onClick = { scope.launch { state.scrollToItem(0) } },
            )
            JumperButton(
                icon = HugeIcons.ArrowUp01,
                contentDescription = stringResource(R.string.scroll_up_one),
                onClick = {
                    scope.launch {
                        // 负下标会触发 animateScrollToItem 的 require(index >= 0)，必须显式夹取
                        // （首项时点「上一项」是常见操作，不夹会崩）
                        state.animateScrollToItem((state.firstVisibleItemIndex - 1).coerceAtLeast(0))
                    }
                },
            )
            JumperButton(
                icon = HugeIcons.ArrowDown01,
                contentDescription = stringResource(R.string.scroll_down_one),
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
            )
            JumperButton(
                icon = HugeIcons.ArrowDownDouble,
                contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
            )
        }
    }
}

@Composable
private fun JumperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.padding(4.dp),
        )
    }
}
