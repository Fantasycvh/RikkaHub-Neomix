package app.androidrik.rikkahub.ui.pages.promptinspect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.PromptSegment
import app.androidrik.rikkahub.data.ai.PromptSegmentKind
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.ScrollJumperBar
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.utils.plus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Share04
import org.koin.androidx.compose.koinViewModel

@Composable
fun PromptInspectPage(
    conversationId: String,
    vm: PromptInspectVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    LaunchedEffect(conversationId) {
        vm.load(conversationId)
    }

    // 滚动结束后 1.5s 隐藏跳转条（与 ChatList 同款节奏）
    var isRecentScroll by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        isRecentScroll = true
        delay(1500)
        isRecentScroll = false
    }

    // 段落下标 = 概览(0) + 缓存提示(1) + 目录(2) 之后的第 n 项 ⇒ n + SEGMENT_OFFSET。
    // 注意：这三个都是 `item {}` 槽位，即使内容 compose 为空也**仍然占位**，
    // 所以偏移是常量、不随是否渲染而变化（目录卡在段数 ≤1 时返回空，同样占位）。
    val segmentOffset = 3

    Box(modifier = Modifier.fillMaxSize()) {
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding + PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { SummaryCard(inspection) }
                    item { PrefixCacheHint(inspection) }
                    item { SegmentIndexCard(inspection, segmentOffset) {
                        scope.launch { listState.animateScrollToItem(it) }
                    } }
                    // 目录卡恒存在 ⇒ 段落下标 = 目录之后 + n，偏移即 3
                    itemsIndexed(
                        items = inspection.segments,
                        key = { index, _ -> index },
                    ) { index, segment ->
                        SegmentCard(
                            index = index,
                            segment = segment,
                            onCopyContent = {
                                copyToClipboard(context, segment.content)
                                toaster.show(context.getString(R.string.prompt_inspect_copied))
                            },
                            onCopyWithHeader = {
                                copyToClipboard(context, segmentClipboardText(index, segment))
                                toaster.show(context.getString(R.string.prompt_inspect_copied))
                            },
                            onShare = { shareText(context, segmentClipboardText(index, segment)) },
                        )
                    }
                    item { LegendCard() }
                }
            }
        }

        // 段卡可以高到几十屏（system 段含全部工具 schema），跳转条是必需的。
        // 与聊天页一致：只在**刚滚动过**时浮现，静止 1.5s 后隐去——常显会压住正文。
        ScrollJumperBar(
            show = isRecentScroll && !listState.isScrollInProgress && state.inspection != null,
            // 靠右：正文满宽，靠左会叠在文字上（聊天页靠左是因为消息本身左右留白）
            onLeft = false,
            scope = scope,
            state = listState,
            itemLabel = if (state.inspection != null) {
                "${listState.firstVisibleItemIndex + 1}/${listState.layoutInfo.totalItemsCount}"
            } else null,
        )
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", text))
}

private fun shareText(context: android.content.Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, null))
}

/** 复制带表头（段号/来源/角色/token），便于贴到论坛或对比工具里时仍能对上来源。 */
private fun segmentClipboardText(index: Int, segment: PromptSegment): String =
    "#${index + 1} ${segment.label} [${segment.role}, ≈${segment.tokens}]\n\n${segment.content}"

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
 * 段落目录：一行一段（有界高度内滚动），点按跳到该段。
 *
 * 为什么需要：段卡可高达几十屏，光有「上下箭头」只能逐项挪；目录是按来源**随机访问**的入口。
 */
@Composable
private fun SegmentIndexCard(
    inspection: app.androidrik.rikkahub.data.ai.PromptInspection,
    segmentOffset: Int,
    onJump: (Int) -> Unit,
) {
    if (inspection.segments.size <= 1) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.prompt_inspect_index_title),
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                inspection.segments.forEachIndexed { index, segment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(index + segmentOffset) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = segment.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "≈ ${segment.tokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
private fun SegmentCard(
    index: Int,
    segment: PromptSegment,
    onCopyContent: () -> Unit,
    onCopyWithHeader: () -> Unit,
    onShare: () -> Unit,
) {
    // 正文**全文直出、不做自动截断**（2026-09-19 决策）：概览职责已由段落目录卡承担，
    // 截断只会让「读内容」这个页面主用途多点一步，且 SelectionContainer 选不到未渲染的文本。
    // 折叠保留为**手动**动作：把整段正文收起、只留卡头——用来把不关心的大段（如工具 schema）
    // 从浏览路径里挪开。默认全部展开。
    var folded by remember(index) { mutableStateOf(false) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "≈ ${segment.tokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 折叠开关放**卡头**：两个方向都可达（正文可长达几十屏，放正文下方会够不着）
                    Surface(
                        onClick = { folded = !folded },
                        shape = CircleShape,
                        color = Color.Transparent,
                    ) {
                        Icon(
                            imageVector = if (folded) HugeIcons.ArrowDown01 else HugeIcons.ArrowUp01,
                            contentDescription = stringResource(
                                if (folded) R.string.prompt_inspect_expand
                                else R.string.prompt_inspect_collapse
                            ),
                            modifier = Modifier
                                .padding(6.dp)
                                .size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 段级快捷操作：长段无法手动划选（等同于没有），必须给「一键复制」
                    SegmentAction(icon = HugeIcons.Copy01, description = stringResource(R.string.copy), onClick = onCopyContent)
                    SegmentAction(
                        icon = HugeIcons.Copy01,
                        description = stringResource(R.string.prompt_inspect_copy_with_header),
                        onClick = onCopyWithHeader,
                    )
                    SegmentAction(icon = HugeIcons.Share04, description = stringResource(R.string.share), onClick = onShare)
                }
            }
            if (!folded) {
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
}

@Composable
private fun SegmentAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier
                .padding(6.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
