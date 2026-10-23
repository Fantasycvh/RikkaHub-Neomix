package app.androidrik.rikkahub.ui.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import com.composables.icons.lucide.MoveDiagonal2
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.androidrik.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.tools.local.ASK_USER_TOOL_NAME
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.model.GenerationPhase
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.ui.components.ui.drawAppLogo
import app.androidrik.rikkahub.ui.components.ui.logoMorphShape
import app.androidrik.rikkahub.ui.components.ui.rememberAppLogoLayers
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.uuid.Uuid

// 与 Material3 LoadingIndicator 的节奏一致，放宽阈值让低阻尼弹簧在间隔内结束。
private val MorphSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 200f, visibilityThreshold = 0.1f)
private const val MORPH_INTERVAL_MILLIS = 650L
private const val SPIN_DURATION_MILLIS = 4666

/** 悬浮窗展示的会话状态。pendingTools 仅包含本会话的普通工具，不包含子代理审批。 */
data class FloatingChatUiState(
    val conversationId: Uuid,
    val title: String,
    val generating: Boolean,
    val processingStatus: String?,
    val phase: GenerationPhase,
    val hasReply: Boolean,
    /** 本轮完整正文（Markdown），还没写正文时是思考内容。 */
    val preview: String,
    val previewIsReasoning: Boolean,
    val pendingTools: List<UIMessagePart.Tool>,
    val queuedCount: Int,
    val queuePaused: Boolean,
    /** 仅这一轮出现的错误。 */
    val error: ChatError?,
    val subagentApprovals: List<SubagentApprovalQueue.Request> = emptyList(),
) {
    val awaitingApproval: Boolean get() = pendingTools.isNotEmpty() || subagentApprovals.isNotEmpty()
    val stopEnabled: Boolean get() = generating || awaitingApproval
    val failed: Boolean get() = !generating && error != null
}

/** 收起时的小图标：应用 logo，生成中轮廓不停变形，结束后标出结果。 */
@Composable
fun ChatFloatingBubble(state: FloatingChatUiState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val logoLayers = rememberAppLogoLayers()
    val shapePosition = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val morphing = state.generating && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(morphing) {
        if (!morphing) {
            shapePosition.animateTo(ceil(shapePosition.value), MorphSpring)
            return@LaunchedEffect
        }
        launch {
            spin.animateTo(
                targetValue = spin.value + 360f,
                animationSpec = infiniteRepeatable(tween(SPIN_DURATION_MILLIS, easing = LinearEasing)),
            )
        }
        while (true) {
            val morph = launch { shapePosition.animateTo(floor(shapePosition.value) + 1f, MorphSpring) }
            delay(MORPH_INTERVAL_MILLIS)
            morph.join()
        }
    }
    val logoShape = logoMorphShape(shapePosition.value, extraRotation = spin.value)
    // 不用 Surface，拖动交给 FloatingX；凹轮廓不能用 elevation 阴影以免阻塞 RenderThread。
    Box(modifier = Modifier.padding(6.dp)) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .dropShadow(logoShape, Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp), alpha = 0.3f))
                .clip(logoShape)
                .clickable(onClick = onClick)
                .drawAppLogo(logoLayers)
        )
        when {
            state.awaitingApproval -> StatusBadge(HugeIcons.Wrench01, colors.error, colors.onError)
            state.generating -> {}
            state.error != null -> StatusBadge(HugeIcons.Alert01, colors.error, colors.onError)
            state.pendingTools.isNotEmpty() -> StatusBadge(HugeIcons.Wrench01, colors.error, colors.onError)
            state.hasReply -> StatusBadge(HugeIcons.Tick01, colors.primary, colors.onPrimary)
        }
    }
}

@Composable
private fun BoxScope.StatusBadge(icon: ImageVector, containerColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(20.dp)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(10.dp))
    }
}

/** 展开后的面板：[onToolApproval] 只授权普通会话工具，绝不用于子代理请求。 */
@Composable
fun ChatFloatingPanel(
    state: FloatingChatUiState,
    maxHeight: Dp,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onOpenConversation: () -> Unit,
    onToolApproval: (toolCallId: String, approved: Boolean) -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onResumeQueue: () -> Unit,
    onSubagentApproval: (SubagentApprovalQueue.Request, Boolean) -> Unit = { _, _ -> },
    panelWidth: Dp? = null,
    onResize: ((Float, Float) -> Unit)? = null,
    onResizeFinished: () -> Unit = {},
    onResetSize: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = (if (panelWidth != null) Modifier.size(panelWidth, maxHeight)
                else Modifier.widthIn(max = 560.dp).fillMaxWidth())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelHeader(state, onOpenConversation, onCollapse, onDismiss)
                // 只滚动中段，标题和输入栏始终可见。
                if (state.preview.isNotEmpty() || state.awaitingApproval || state.queuedCount > 0) {
                    Column(
                        modifier = Modifier.weight(1f, fill = panelWidth != null).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.preview.isNotEmpty()) {
                            MessagePreview(state.preview, state.previewIsReasoning, state.generating)
                        }
                        state.pendingTools.forEach { tool ->
                            PendingToolCard(
                                tool = tool,
                                onApproval = { approved -> onToolApproval(tool.toolCallId, approved) },
                                onOpenConversation = onOpenConversation,
                            )
                        }
                        state.subagentApprovals.forEach { request ->
                            Text(request.label, style = MaterialTheme.typography.labelLarge)
                            PendingToolCard(
                                tool = request.tool,
                                onApproval = { approved -> onSubagentApproval(request, approved) },
                                onOpenConversation = onOpenConversation,
                            )
                        }
                        if (state.queuedCount > 0) {
                            QueueRow(state.queuedCount, state.queuePaused, onResumeQueue)
                        }
                    }
                }
                if (panelWidth != null && state.preview.isEmpty() && !state.awaitingApproval && state.queuedCount == 0) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                ReplyBar(state.conversationId, state.stopEnabled, onSend, onStop)
                if (onResize != null) {
                    val density = LocalDensity.current
                    val resize by rememberUpdatedState(onResize)
                    val finished by rememberUpdatedState(onResizeFinished)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onResetSize != null) TextButton(onClick = onResetSize) {
                            Text(stringResource(R.string.floating_chat_reset_size))
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Icon(
                            Lucide.MoveDiagonal2,
                            contentDescription = stringResource(R.string.floating_chat_resize),
                            modifier = Modifier.size(40.dp).pointerInput(density) {
                                detectDragGestures(
                                    onDragEnd = { finished() },
                                    onDragCancel = { finished() },
                                ) { change, delta ->
                                    change.consume()
                                    with(density) { resize?.invoke(delta.x.toDp().value, delta.y.toDp().value) }
                                }
                            }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: FloatingChatUiState,
    onOpenConversation: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.generating) {
            ContainedLoadingIndicator(modifier = Modifier.size(40.dp))
        } else {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (state.failed) colors.errorContainer else colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = state.statusIcon(),
                    contentDescription = null,
                    tint = if (state.failed) colors.onErrorContainer else colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.statusText(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenConversation) {
            Icon(Lucide.Maximize2, contentDescription = stringResource(R.string.floating_chat_open_conversation))
        }
        IconButton(onClick = onCollapse) {
            Icon(HugeIcons.Remove01, contentDescription = stringResource(R.string.floating_chat_collapse))
        }
        IconButton(onClick = onDismiss) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.floating_chat_close))
        }
    }
}

// 本仓完整 Markdown 的表格/链接依赖 Activity，浮窗固定使用其现有安全流式预览分支。
@Composable
private fun MessagePreview(text: String, isReasoning: Boolean, generating: Boolean) {
    val scrollState = rememberScrollState()
    LaunchedEffect(generating) {
        if (generating) snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (isReasoning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    ) {
        // Always use the existing streaming renderer, including after generation completes:
        // full table rendering requires ActivityResultRegistry and full links start an Activity
        // without NEW_TASK. Overlay windows have neither an Activity nor its result registry.
        MarkdownBlock(
            content = text,
            streaming = true,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(scrollState).padding(12.dp),
        )
    }
}

@Composable
private fun PendingToolCard(
    tool: UIMessagePart.Tool,
    onApproval: (approved: Boolean) -> Unit,
    onOpenConversation: () -> Unit,
) {
    val isAskUser = tool.toolName == ASK_USER_TOOL_NAME
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (isAskUser) stringResource(R.string.floating_chat_ask_user)
                else stringResource(R.string.floating_chat_tool_request, tool.toolName.substringAfterLast("__")),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!isAskUser && tool.input.isNotBlank()) {
                Text(
                    // Approval must not conceal arguments beyond a truncated preview.
                    text = tool.input,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (isAskUser) {
                    // 多问题和选项必须在会话里回答，不能当作普通工具批准。
                    Button(onClick = onOpenConversation) { Text(stringResource(R.string.floating_chat_answer)) }
                } else {
                    OutlinedButton(onClick = { onApproval(false) }) { Text(stringResource(R.string.chat_message_tool_deny)) }
                    Button(onClick = { onApproval(true) }) { Text(stringResource(R.string.chat_message_tool_approve)) }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(count: Int, paused: Boolean, onResume: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(if (paused) R.string.chat_message_queue_paused else R.string.chat_message_queue_pending, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (paused) TextButton(onClick = onResume) { Text(stringResource(R.string.chat_message_queue_resume)) }
    }
}

@Composable
private fun ReplyBar(conversationId: Uuid, generating: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable(conversationId) { mutableStateOf("") }
    fun send() {
        val content = text.trim()
        if (content.isEmpty()) return
        onSend(content)
        text = ""
        focusManager.clearFocus()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(stringResource(if (generating) R.string.floating_chat_reply_queued_placeholder else R.string.floating_chat_reply_placeholder))
            },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (generating) {
            FilledTonalIconButton(onClick = onStop) {
                Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.stop))
            }
        }
        FilledIconButton(onClick = ::send, enabled = text.isNotBlank()) {
            Icon(HugeIcons.ArrowUp02, contentDescription = stringResource(R.string.send))
        }
    }
}

private fun FloatingChatUiState.statusIcon(): ImageVector = when {
    error != null -> HugeIcons.Alert01
    awaitingApproval -> HugeIcons.Wrench01
    hasReply -> HugeIcons.Tick01
    else -> HugeIcons.Sparkles
}

@Composable
private fun FloatingChatUiState.statusText(): String {
    if (subagentApprovals.isNotEmpty()) return stringResource(R.string.floating_chat_status_awaiting_approval)
    if (!generating) {
        return when {
            error != null -> error.title ?: error.error.message ?: stringResource(R.string.floating_chat_status_failed)
            pendingTools.any { it.toolName == ASK_USER_TOOL_NAME } -> stringResource(R.string.floating_chat_status_awaiting_answer)
            pendingTools.isNotEmpty() -> stringResource(R.string.floating_chat_status_awaiting_approval)
            hasReply -> stringResource(R.string.floating_chat_status_done)
            else -> stringResource(R.string.floating_chat_status_idle)
        }
    }
    processingStatus?.let { return it }
    return when (phase) {
        is GenerationPhase.RunningTool -> stringResource(R.string.notification_live_update_tool, phase.toolName)
        is GenerationPhase.Thinking -> stringResource(R.string.notification_live_update_thinking)
        is GenerationPhase.Writing -> stringResource(R.string.notification_live_update_writing)
        GenerationPhase.Waiting -> stringResource(R.string.notification_live_update_title)
    }
}
