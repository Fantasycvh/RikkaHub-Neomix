package app.androidrik.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.tools.SUBAGENT_DISPATCH_TOOL_NAME
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.pages.agentrun.errorLabel
import app.androidrik.rikkahub.ui.pages.agentrun.statusLabel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Robot01

/**
 * 对话内子代理派发（`dispatch_subagent`）的卡片渲染（设计 §四/§十）。
 *
 * 只做三件小事，且都指向**同一份账本数据**：
 * 1. 标题带调度名——一眼看出派的是哪个组合；
 * 2. 摘要显示 run 状态（复用运行页的 `statusLabel`/`errorLabel`，两处口径不会分叉）；
 * 3. 详情页头部给「查看运行」入口 —— 子代理的中间过程**不进父上下文**（§1.3），
 *    要看过程只能去运行详情页，这里是唯一的路标。
 */
object SubagentToolUI : ToolUIRenderer {
    override val toolName: String = SUBAGENT_DISPATCH_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Robot01

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.arguments.getStringContent("name").orEmpty()
        return stringResource(R.string.subagent_tool_title, name)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("status") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val status = context.content.getStringContent("status") ?: return
        val error = context.content.getStringContent("error")
        val label = errorLabel(error) ?: statusLabel(status)
        val goal = context.arguments.getStringContent("goal")
        Text(
            text = listOfNotNull(label, goal).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val navController = LocalNavController.current
        val runId = context.content.getStringContent("run_id")
        val headerActions: (@Composable () -> Unit)? = if (runId != null) {
            {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        navController.navigate(Screen.AgentRunDetail(runId))
                    }
                ) {
                    Text(stringResource(R.string.agent_run_open_detail))
                }
            }
        } else {
            null
        }
        DefaultToolPreview(context = context, headerActions = headerActions)
    }
}
