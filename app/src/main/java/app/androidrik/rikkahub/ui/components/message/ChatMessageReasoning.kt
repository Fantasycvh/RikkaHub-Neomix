package app.androidrik.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import androidx.compose.runtime.snapshotFlow
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Idea01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantAffectScope
import app.androidrik.rikkahub.data.model.replaceRegexes
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.modifier.onClick
import com.dokar.sonner.ToastType
import app.androidrik.rikkahub.ui.modifier.shimmer
import app.androidrik.rikkahub.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

@Composable
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent)
                state.expandState = ReasoningCardState.Preview
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
            }
        }
    }

    // 贴底跟随**布局**变化而非文本变化（#1662）：思考内容经 MarkdownBlock 异步解析
    // （mapLatest → flowOn(Default) → setData），「文本增长」与「布局增长」差一拍——
    // 在文本变化回调里读 scrollState.maxValue 拿到的是旧布局值，会滚到新文本的中间。
    // maxValue 只在布局完成后更新，跟随它即永远贴住真底部。maxValue==0（未布局或
    // Expanded 态无滚动容器）时不动作。
    LaunchedEffect(scrollState, loading) {
        if (loading) {
            snapshotFlow { scrollState.maxValue }
                .distinctUntilChanged()
                .collect { max ->
                    if (max > 0) scrollState.animateScrollTo(max)
                }
        }
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    return state to loading
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
    loading: Boolean,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val reasoningTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to Color.Black,
                                    (1 - fadeHeight / size.height) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        val reasoningContent = @Composable {
            MarkdownBlock(
                content = reasoning.reasoning.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
                ),
                style = reasoningTextStyle,
                // fillMaxWidth 而非 fillMaxSize：LazyColumn item 内 fillMaxSize 会把
                // 思考卡片撑到整个视口高度——短思考内容后出现整屏空白的根因
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 流式生成期间不启用 SelectionContainer，避免 selectable 列表并发修改导致的
        // ConcurrentModificationException（详见 ChatMessage.kt 文本块同样处理）。
        if (loading) {
            reasoningContent()
        } else {
            SelectionContainer {
                reasoningContent()
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
    /** #1929 编辑思考过程的入口回调；null = 不显示编辑入口（无消息上下文的场景） */
    onEditRequest: (() -> Unit)? = null,
    /**
     * 禁用编辑入口（置灰 + 点击给出原因）：Claude 签名 `thinking` / `redacted_thinking` 与
     * Gemini `thoughtSignature` 必须**原样回传**，改过会被上游拒绝（判据 = `isProviderSigned()`）。
     */
    editDisabled: Boolean = false,
) {
    val (state, loading) = rememberReasoningState(reasoning)
    val thinkingTitle = reasoning.reasoning.extractThinkingTitle()
    val showThinkingTitle = loading && thinkingTitle != null
    val chatFontFamily = LocalTextStyle.current.fontFamily

    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, loading) },
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            if (showThinkingTitle) {
                ReasoningTitle(title = thinkingTitle!!)
            } else {
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            RowOfExtra(
                durationText = if (showThinkingTitle && state.duration > 0.seconds) {
                    state.duration.toString(DurationUnit.SECONDS, 1)
                } else null,
                showEdit = !loading && onEditRequest != null,
                onEditRequest = onEditRequest,
                editDisabled = editDisabled,
                chatFontFamily = chatFontFamily,
            )
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
                loading = loading,
            )
        },
    )
}


/**
 * #1929 编辑思考过程的整屏编辑器（形态照 `TextArea.FullScreenTextEditor`）。
 *
 * **编辑内容不进 saved state**（`remember` 而非 `rememberSaveable`）——推理动辄上万字，
 * 进 saved state 会在切后台时 TransactionTooLargeException 崩溃；进程死亡丢编辑是可接受的。
 *
 * [providerSigned] 为真时多显示一行警示：这类块（Claude 签名 thinking / `redacted_thinking`、Gemini
 * `thoughtSignature`）**必须原样回传**，编辑后可能被上游拒（见 `isProviderSigned`）。**行为不变**，
 * 只是知情提示。
 */
@Composable
internal fun ReasoningEditDialog(
    initialText: String,
    /** 该推理块由上游签名/加密（Claude thinking signature / Gemini thoughtSignature）⇒ 显示警示 */
    providerSigned: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var editingText by remember(initialText) { mutableStateOf(initialText) }
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(
                            onClick = { onConfirm(editingText) },
                        ) {
                            Text(stringResource(R.string.chat_message_reasoning_edit_save))
                        }
                    }
                    if (providerSigned) {
                        // 上游规则：带签名/加密的块必须原样回传，修改过的块会被拒。
                        // 这里只提示、不改变行为（#1929 编辑能力保留）。
                        Text(
                            text = stringResource(R.string.chat_message_reasoning_edit_signed_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                        )
                    }
                    TextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier
                            .imePadding()
                            .fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_message_reasoning_edit))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowOfExtra(
    durationText: String?,
    showEdit: Boolean,
    onEditRequest: (() -> Unit)?,
    editDisabled: Boolean = false,
    chatFontFamily: FontFamily?,
) {
    if (durationText == null && !showEdit) return
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (durationText != null) {
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = false),
            )
        }
        if (showEdit) {
            val toaster = LocalToaster.current
            val editDisabledHint = stringResource(R.string.chat_message_reasoning_edit_disabled_signed)
            // #1929：编辑思考过程。只放表头不放消息 ⋮ 菜单——那里已有「编辑」指代
            // 整条消息，两个"编辑"并排会歧义；流式生成中不显示（会被后续 chunk 覆盖）。
            Icon(
                imageVector = HugeIcons.Edit02,
                contentDescription = stringResource(R.string.chat_message_reasoning_edit),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(14.dp)
                    .onClick(
                        onClick = {
                            if (editDisabled) {
                                // 签名块禁用编辑：只解释原因，不打开编辑器
                                toaster.show(message = editDisabledHint, type = ToastType.Warning)
                            } else {
                                onEditRequest?.invoke()
                            }
                        }
                    ),
                tint = if (editDisabled) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
    }
}

@Composable
private fun ReasoningTitle(title: String) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
