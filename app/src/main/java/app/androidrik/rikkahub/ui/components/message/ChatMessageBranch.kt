package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 分支（时间线）切换上下文：由 [app.androidrik.rikkahub.ui.pages.chat.ChatList] 提供
 * （全量节点集 + 切换回调），消息组件经 CompositionLocal 消费——避免给 ChatMessage
 * 全链路加参数。null（预览页等非聊天页宿主）= 不渲染时间线切换器。
 *
 * 「候选」（节点内版本）与「时间线」（兄弟分支）两级控件共用同一形态（P1 最简版；
 * 统一控件形态属设计稿 §十二.2，P2 打磨）。
 */
class ChatBranchController(
    /** 全量节点集（含 off-path 兄弟子树）。 */
    val nodes: List<MessageNode>,
    /** 切换到某兄弟子树（服务端落到该子树最近更新叶子）。 */
    val onSwitchBranch: (Uuid) -> Unit,
)

val LocalChatBranchController = androidx.compose.runtime.staticCompositionLocalOf<ChatBranchController?> { null }

@Composable
fun ChatMessageBranchSelector(
    node: MessageNode,
    modifier: Modifier = Modifier,
    onUpdate: (MessageNode) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.messages.size > 1) {
            val actionColor = MaterialTheme.colorScheme.onSurfaceVariant

            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = "Prev",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == 0) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex > 0) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex - 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )

            Text(
                text = "${node.selectIndex + 1}/${node.messages.size}",
                style = MaterialTheme.typography.bodySmall,
                color = actionColor
            )

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = "Next",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == node.messages.lastIndex) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex < node.messages.lastIndex) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex + 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )
        }

        // 时间线切换（兄弟分支）：仅当该节点存在兄弟（含分支点两子树各一）时渲染
        val branch = LocalChatBranchController.current
        val siblings = remember(branch, node.id, node.parentId) {
            branch?.nodes?.filter { it.parentId == node.parentId }.orEmpty()
        }
        if (siblings.size > 1) {
            val actionColor = MaterialTheme.colorScheme.onSurfaceVariant
            val currentIndex = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)

            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = stringResource(R.string.chat_branch_prev_timeline),
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (currentIndex == 0) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (currentIndex > 0) {
                                branch?.onSwitchBranch(siblings[currentIndex - 1].id)
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )

            Text(
                // 「时间线 x/y」——与候选版本计数在视觉上以标签区分（P1 最简；P2 统一控件）
                text = stringResource(R.string.chat_branch_timeline_index, currentIndex + 1, siblings.size),
                style = MaterialTheme.typography.bodySmall,
                color = actionColor
            )

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = stringResource(R.string.chat_branch_next_timeline),
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (currentIndex == siblings.lastIndex) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (currentIndex < siblings.lastIndex) {
                                branch?.onSwitchBranch(siblings[currentIndex + 1].id)
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )
        }
    }
}
