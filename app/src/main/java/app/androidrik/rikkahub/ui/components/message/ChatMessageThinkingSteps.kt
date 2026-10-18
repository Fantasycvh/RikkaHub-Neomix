package app.androidrik.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.ChainOfThought
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope

/**
 * 整轮折叠块：把一条 assistant 回复的**思考 + 工具调用 + 过程解说**收进一个可展开块，
 * 只留最后一次工具调用之后的文本作为最终回答。
 *
 * 与 [ChainOfThought] 的关系：**不改组件本体**。这里在组件外面加一层折叠头，
 * 并用 `collapsedVisibleCount = steps.size` 关掉组件自身的“只显示最后 N 步”，
 * 于是“整轮折叠”与“组件折叠”互不打架。
 *
 * - 决策 2/8：`canCollapse == false` 时不显示折叠头、全部步骤常驻（没有可留的回答，
 *   或存在待审批步骤）；
 * - 决策 3：生成中展开、生成结束收起；
 * - 决策 5：折叠状态用 [rememberSaveable]（`remember` 会被 `LazyColumn` 滚出视口销毁）；
 * - 决策 6/7：折叠头摘要行带步数/工具数/说明数，错误另有常驻红色标记但不强制展开。
 *
 * @param steps 该折叠块内的步骤
 * @param canCollapse 能否整块收起（由 [groupMessageParts] 按决策算好）
 * @param forceExpand 是否强制展开（存在待审批步骤）
 * @param loading 该消息是否仍在生成中
 * @param cardColors 思考链卡片配色
 */
@Composable
internal fun WholeTurnFoldBlock(
    steps: List<ThinkingStep>,
    canCollapse: Boolean,
    forceExpand: Boolean,
    loading: Boolean,
    cardColors: CardColors,
    modifier: Modifier = Modifier,
    collapsedAdaptiveWidth: Boolean = false,
    content: @Composable ChainOfThoughtScope.(ThinkingStep) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(loading || forceExpand) }
    // 决策 3：生成中展开，生成结束收起。只在 loading 从 true → false 的跃迁上收起——
    // 无条件按 loading 赋值会把“滚动回来恢复出来的展开态”立刻抹掉（决策 5 就白做了）。
    var wasLoading by remember { mutableStateOf(loading) }
    LaunchedEffect(loading) {
        if (loading) {
            expanded = true
        } else if (wasLoading) {
            expanded = false
        }
        wasLoading = loading
    }
    // 决策 8：待审批步骤一出现就必须可操作（哪怕此前被手动收起过）
    LaunchedEffect(forceExpand) {
        if (forceExpand) expanded = true
    }

    val summary = remember(steps) { steps.thinkingChainSummary() }
    val hasError = remember(steps) { steps.any { it.hasError() } }
    val folded = canCollapse && !forceExpand && !expanded

    Column(modifier = modifier) {
        if (canCollapse) {
            WholeTurnFoldHeader(
                expanded = !folded,
                summary = summary,
                hasError = hasError,
                onToggle = { expanded = !expanded },
            )
        }
        if (!folded) {
            ChainOfThought(
                modifier = Modifier.animateContentSize(),
                cardColors = cardColors,
                steps = steps,
                // 折叠交给外层：关掉组件自己的 takeLast(n)
                collapsedVisibleCount = steps.size,
                collapsedAdaptiveWidth = collapsedAdaptiveWidth,
                content = content,
            )
        }
    }
}

/**
 * 折叠头：摘要行 `3 步 · 2 工具 · 2 段说明`（决策 6）+ 展开/收起指示。
 *
 * 布局与 [ChainOfThought] 顶部控制条对齐（24.dp 图标位 + 8.dp 间距），
 * 这样展开后文字与步骤标题的视觉左缘一致。
 */
@Composable
private fun WholeTurnFoldHeader(
    expanded: Boolean,
    summary: ThinkingChainSummary,
    hasError: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = stringResource(
                R.string.chain_of_thought_fold_summary,
                summary.stepCount,
                summary.toolCount,
                summary.narrationCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (hasError) {
            // 决策 7：错误常驻红色标记（折叠态也能看见），但不强制展开
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}
