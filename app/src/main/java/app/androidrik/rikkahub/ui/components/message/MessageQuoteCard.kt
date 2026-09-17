package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.utils.toMessageTimeString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

/**
 * #1906 引用卡片：同一组件服务两处显示——输入框上方的待发引用预览（可取消）、
 * 已发送消息上方的引用来源。两者的信息完全相同（角色 + 时间 + 快照摘要），
 * 抽一个组件是为了避免两处样式漂移。
 *
 * 快照是**发送时固定的副本**：原消息之后被编辑/删除，这里显示的仍是当时引用的内容。
 *
 * @param onCancel 非空时显示取消按钮（仅输入框预览需要）
 */
@Composable
fun MessageQuoteCard(
    quote: UIMessageAnnotation.QuotedMessage,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // stringResource 是 @Composable，必须先取出再进普通 lambda 拼接
    val roleLabel = stringResource(
        when (quote.role) {
            MessageRole.USER -> R.string.chat_message_quote_user
            MessageRole.ASSISTANT -> R.string.chat_message_quote_assistant
            MessageRole.SYSTEM, MessageRole.TOOL -> R.string.chat_message_quote_context
        }
    )
    val header = quote.createdAt
        ?.let { "$roleLabel · ${it.toJavaLocalDateTime().toMessageTimeString()}" }
        ?: roleLabel
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 左侧竖条：引用块的视觉标识（无需文字也能看出是引用）
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = quote.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.chat_message_quote_cancel),
                    )
                }
            }
        }
    }
}
