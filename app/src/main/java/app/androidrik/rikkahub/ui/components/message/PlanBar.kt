package app.androidrik.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.TaskDone01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.tools.local.TodoEntry
import app.androidrik.rikkahub.data.ai.tools.local.TODO_STATUS_COMPLETED
import app.androidrik.rikkahub.data.ai.tools.local.TODO_STATUS_IN_PROGRESS

/**
 * 计划细条（借鉴 Yuihub 最终形态，方案 E）：常驻在输入栏正上方，收起时一行高度。
 *
 * 收起态显示「当前进行中的步骤 + 进度」，点击展开完整清单；再点收起。
 * 没有计划时不渲染任何内容（由调用方根据 todos 是否为空决定）。
 */
@Composable
fun PlanBar(
    todos: List<TodoEntry>,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return

    val completed = todos.count { it.status == TODO_STATUS_COMPLETED }
    val total = todos.size
    val allDone = completed == total
    // 当前进行中的那一步；全部完成时退化为完成状态
    val current = todos.firstOrNull { it.status == TODO_STATUS_IN_PROGRESS }
        ?: todos.lastOrNull { it.status != TODO_STATUS_COMPLETED }
    var expanded by remember { mutableStateOf(false) }

    // 箭头随展开状态旋转，与「下拉」的方向语义一致
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "planBarArrow",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---- 收起态：一行摘要（点击整行切换展开） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    imageVector = if (allDone) HugeIcons.TaskDone01 else HugeIcons.CheckList,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = if (allDone) {
                            stringResource(R.string.chat_message_todo_all_done)
                        } else {
                            current?.content ?: stringResource(R.string.chat_message_todo_title)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.plan_bar_progress, completed, total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "$completed/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 进度条 ----
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else completed.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            // ---- 展开态：完整清单 ----
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    todos.forEach { todo ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = when (todo.status) {
                                    TODO_STATUS_COMPLETED -> "✓"
                                    TODO_STATUS_IN_PROGRESS -> "▸"
                                    else -> "○"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (todo.status == TODO_STATUS_COMPLETED) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = if (todo.status == TODO_STATUS_COMPLETED) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
