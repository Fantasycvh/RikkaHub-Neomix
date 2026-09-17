package app.androidrik.rikkahub.ui.pages.promptinspect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.PromptSegment
import app.androidrik.rikkahub.data.ai.PromptSegmentKind
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ChartLineData01
import org.koin.androidx.compose.koinViewModel

/**
 * 「拼装后提示词」预览页（只读）。
 *
 * 见 `docs/peer-apps-benchmark.md` §6.7 ③：预览与真实请求共用同一条组装 + transformer 链，
 * 因此**不会漂移**。用途是回答「这次到底发了什么」——尤其是
 * ① system 里各固定开销（记忆 / 工具 schema）占多少、
 * ② 世界书命中了几条、
 * ③ 历史是否被上下文上限或压缩截断。
 */
@Composable
fun PromptInspectPage(
    conversationId: String,
    vm: PromptInspectVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(conversationId) {
        vm.load(conversationId)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.prompt_inspect_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val inspection = state.inspection
        when {
            state.loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }

            inspection == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = HugeIcons.Alert02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.prompt_inspect_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SummaryCard(inspection) }
                item { PrefixCacheHint(inspection) }
                itemsIndexed(
                    items = inspection.segments,
                    key = { index, _ -> index },
                ) { index, segment ->
                    SegmentCard(index = index, segment = segment)
                }
                item { LegendCard() }
            }
        }
    }
}

@Composable
private fun SummaryCard(inspection: app.androidrik.rikkahub.data.ai.PromptInspection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_inspect_summary),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            MetaRow(
                label = stringResource(R.string.prompt_inspect_segments),
                value = inspection.segments.size.toString(),
            )
            MetaRow(
                label = stringResource(R.string.prompt_inspect_estimated_tokens),
                value = "≈ ${inspection.totalTokens}",
            )
            MetaRow(
                label = stringResource(R.string.prompt_inspect_message_count_label),
                value = stringResource(
                    R.string.prompt_inspect_message_count,
                    inspection.sentHistoryCount,
                    inspection.totalMessageCount,
                ),
            )
            if (inspection.contextMessageLimit > 0) {
                MetaRow(
                    label = stringResource(R.string.prompt_inspect_context_limit),
                    value = inspection.contextMessageLimit.toString(),
                )
            }
        }
    }
}

/**
 * 前缀缓存提示：system 在位置 0，**任何随会话变化的内容出现在 system 里都会让整段缓存失效**。
 * 这里只在「system 中存在非 systemPrompt 来源的易变内容」时给出解释性提醒。
 */
@Composable
private fun PrefixCacheHint(inspection: app.androidrik.rikkahub.data.ai.PromptInspection) {
    val system = inspection.segments.firstOrNull { it.kind == PromptSegmentKind.SYSTEM } ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.ChartLineData01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.prompt_inspect_cache_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(
                        R.string.prompt_inspect_cache_body,
                        system.tokens,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SegmentCard(index: Int, segment: PromptSegment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${index + 1}  ${segment.label}",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = "≈ ${segment.tokens}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = segment.role,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (segment.isSynthetic) {
                    Text(
                        text = stringResource(R.string.prompt_inspect_synthetic),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = segment.content.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_inspect_legend_title),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.prompt_inspect_legend_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
