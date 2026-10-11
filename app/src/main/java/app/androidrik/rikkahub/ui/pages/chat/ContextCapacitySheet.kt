package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.utils.ContextCapacity
import app.androidrik.rikkahub.utils.MIN_ROUNDS_FOR_ESTIMATE
import app.androidrik.rikkahub.utils.formatContextLength
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Database02

/** 状态点的常态色：浅蓝（用户 2026-10-02 指定），达警戒阈值时才切红。 */
internal val ContextStatusDotNormal = Color(0xFF7FC4E8)

/**
 * 会话上下文容量面板。
 *
 * 口径（与 私有仓记录·规划 2026-10-02 条目一致）：
 * - **组成明细用差值法**：「系统设定与记忆」= 总占用 − 单列项合计，故**各项相加恒等于总占用**
 *   （总占用常来自服务端 usage，而分段只有本地估算，逐项独立求和一定加不平）。
 * - **轮数预测**：分母问答对、分子只含历史正文、每轮取中位数、样本 < [MIN_ROUNDS_FOR_ESTIMATE] 轮不预测。
 * - 次要行（工具 schema / 工具输出 / 压缩检查点）默认折叠，避免面板变长；超阈值时自动展开。
 *
 * @param capacity null 表示分段还在计算（只读组装要跑一遍 transformer 链）；此时指标照常显示，
 *   只在明细处给加载态。
 */
@Composable
fun ContextCapacitySheet(
    assistantName: String,
    modelName: String,
    capacity: ContextCapacity?,
    onCompress: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showItemizedOverride by remember { mutableStateOf<Boolean?>(null) }
    val hasItemized = capacity?.itemizedTokens?.let { it > 0 } == true
    // 超阈值自动展开（用户此刻最需要知道"大在哪"），其余情况默认折叠
    val showItemized = showItemizedOverride ?: (hasItemized && capacity?.isWarning == true)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.context_capacity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = listOf(modelName, assistantName).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        UsageCard(capacity)

        capacity?.let { EstimateCard(it) }

        Text(
            text = stringResource(R.string.context_capacity_breakdown_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (capacity == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.context_capacity_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BreakdownCard(
                capacity = capacity,
                showItemized = showItemized,
                onToggleItemized = { showItemizedOverride = !showItemized },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                onClick = onCompress,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HugeIcons.Database02, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.context_capacity_compress), style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
            ) {
                Text(stringResource(R.string.context_capacity_confirm))
            }
        }
    }
}

@Composable
private fun UsageCard(capacity: ContextCapacity?) {
    val fraction = capacity?.fraction ?: 0f
    val isWarning = capacity?.isWarning == true
    val dotColor = if (isWarning) MaterialTheme.colorScheme.error else ContextStatusDotNormal
    val percentText = if (capacity?.windowTokens ?: 0 > 0) {
        "%.1f%%".format(fraction * 100f)
    } else {
        "—"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(
                        if (isWarning) {
                            R.string.context_capacity_status_warning
                        } else {
                            R.string.context_capacity_status_ok
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = percentText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (capacity?.usesLocalFloor == true) {
                Text(
                    text = stringResource(R.string.context_capacity_local_floor_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = if (isWarning) MaterialTheme.colorScheme.error else ContextStatusDotNormal,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Metric(
                    label = stringResource(R.string.context_capacity_remaining_label),
                    value = formatContextLength(capacity?.remainingTokens),
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = stringResource(R.string.context_capacity_used_label),
                    value = formatContextLength(capacity?.usedTokens ?: 0),
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = stringResource(R.string.context_capacity_window_label),
                    value = formatContextLength(capacity?.windowTokens),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.context_capacity_tokens_unit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EstimateCard(capacity: ContextCapacity) {
    val rounds = capacity.estimatedRounds
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Clock02,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (rounds != null) {
                    stringResource(
                        R.string.context_capacity_estimate_rounds,
                        rounds,
                        formatContextLength(capacity.medianTokensPerRound),
                    )
                } else {
                    stringResource(
                        R.string.context_capacity_estimate_insufficient,
                        MIN_ROUNDS_FOR_ESTIMATE,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BreakdownCard(
    capacity: ContextCapacity,
    showItemized: Boolean,
    onToggleItemized: () -> Unit,
) {
    val used = capacity.usedTokens

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            BreakdownRow(
                icon = HugeIcons.ChartColumn,
                title = stringResource(R.string.context_capacity_breakdown_history),
                detail = stringResource(
                    R.string.context_capacity_breakdown_count,
                    capacity.sentHistoryCount,
                    capacity.totalMessageCount,
                ),
                tokens = capacity.historyTokens,
                used = used,
            )
            BreakdownRow(
                icon = HugeIcons.Database02,
                title = stringResource(R.string.context_capacity_breakdown_system_memory),
                detail = null,
                tokens = capacity.systemMemoryTokens,
                used = used,
            )

            if (capacity.itemizedTokens > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleItemized() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.context_capacity_breakdown_itemized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatContextLength(capacity.itemizedTokens),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (showItemized) "  ▾" else "  ▸",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showItemized) {
                    SubRow(stringResource(R.string.context_capacity_breakdown_tool_schema), capacity.toolSchemaTokens)
                    SubRow(stringResource(R.string.context_capacity_breakdown_tool_result), capacity.toolResultTokens)
                    SubRow(stringResource(R.string.context_capacity_breakdown_checkpoint), capacity.checkpointTokens)
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.context_capacity_breakdown_total),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatContextLength(used)} ${stringResource(R.string.context_capacity_tokens_unit)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String?,
    tokens: Int,
    used: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatContextLength(tokens),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (used > 0) "%.1f%%".format(tokens.toFloat() / used * 100f) else "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubRow(title: String, tokens: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatContextLength(tokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
