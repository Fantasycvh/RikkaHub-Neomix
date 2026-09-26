package app.androidrik.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.tools.SUBAGENT_DISPATCH_TOOL_NAME
import app.androidrik.rikkahub.service.RunProgress
import app.androidrik.rikkahub.service.SubagentRunner
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.pages.agentrun.errorLabel
import app.androidrik.rikkahub.ui.pages.agentrun.statusLabel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
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
        // 跑的时候也要有摘要——这正是"实时进度"显示的地方（结果 JSON 那时还不存在）
        context.loading || context.content.getStringContent("status") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (context.loading) {
            RunningSummary(context)
            return
        }
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

    /**
     * 运行中的实时进度。
     *
     * **数据源是 Runner 的 `progress` 而不是本卡片自己的状态**：子代理的中间过程不进父上下文，
     * 卡片能拿到的东西只有工具入参——所以"跑到第几步"必须从运行侧读。
     *
     * **并行上限 2 之后不能再说"活跃的就是我这一个"**：卡片按 **(预设名, 任务目标)** 认领，
     * 两者都在它的入参里（runner 侧把同一对值写进 `RunProgress`），无需给卡片额外接线。
     * 认领不到时：只有一条就退回那一条（某些 provider 会规整 goal 文本），
     * 多条则只显示聚合态——**宁可少说，也不要把别人的步数当成自己的**。
     */
    @Composable
    private fun RunningSummary(context: ToolUIContext) {
        val runner = koinInject<SubagentRunner>()
        val progressList by runner.progress.collectAsStateWithLifecycle()
        val presets = progressList
        val name = context.arguments.getStringContent("name").orEmpty()
        val goal = context.arguments.getStringContent("goal").orEmpty()
        val mine = presets.firstOrNull { it.label == name && it.goal == goal }
            ?: presets.singleOrNull()
        val elapsedSeconds = rememberElapsedSeconds(mine?.startedAt)

        if (mine == null && presets.size > 1) {
            Text(
                text = stringResource(R.string.subagent_tool_running_parallel, presets.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            return
        }

        val detail = buildString {
            append(stringResource(R.string.subagent_tool_elapsed, elapsedSeconds))
            mine?.lastTool?.let { append(" · ").append(it) }
        }
        Text(
            text = if (mine == null || mine.steps == 0) {
                "${stringResource(R.string.subagent_tool_starting)} · $detail"
            } else {
                stringResource(R.string.subagent_tool_running, mine.steps, detail)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
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

/**
 * 已运行的秒数（每秒一跳）。
 *
 * 只在加载分支里调用：卡片一进结果态就不再走这个 composable，计时器随之取消——
 * 否则一个展开的旧卡片会永远每秒重组一次。
 */
@Composable
private fun rememberElapsedSeconds(startedAt: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    if (startedAt == null) return 0
    return ((now - startedAt) / 1000).coerceAtLeast(0)
}
