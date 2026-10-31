package app.androidrik.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.isContentFilterTruncation
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isProviderSigned
import app.androidrik.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.model.AssistantAffectScope
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.replaceRegexes
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.richtext.ZoomableAsyncImage
import app.androidrik.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import app.androidrik.rikkahub.ui.components.webview.WebViewContentCache
import app.androidrik.rikkahub.ui.components.ui.ChainOfThought
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope
import app.androidrik.rikkahub.ui.components.charts.ChartCard
import app.androidrik.rikkahub.ui.components.charts.ChartSpec
import app.androidrik.rikkahub.ui.components.ui.Favicon
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalWorkspaceId
import app.androidrik.rikkahub.ui.modifier.shimmer
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.theme.LocalChatFontFamily
import app.androidrik.rikkahub.ui.theme.rememberChatFontFamily
import app.androidrik.rikkahub.ui.theme.extendColors
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.openUrl
import app.androidrik.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    /** 多文本段消息按段编辑（工具轮续跑形状「文本-工具卡-文本」）；null 时动作面板只给整体编辑入口 */
    onEditSegment: ((UIMessage, Int) -> Unit)? = null,
    /** 手动「继续生成」：把被截断的消息接着写下去（仅末条消息显示该按钮）。 */
    onContinue: () -> Unit = {},
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit = {},
    onQuoteClick: ((Uuid) -> Unit)? = null,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolSkip: ((toolCallId: String) -> Unit)? = null,
    onApproveAll: (() -> Unit)? = null,
    /** #1929 编辑思考过程（原地改推理文本）；null = 不显示编辑入口 */
    onEditReasoning: ((messageId: Uuid, reasoning: UIMessagePart.Reasoning, text: String) -> Unit)? = null,
) {
    val message = node.messages[node.safeSelectIndex]
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    // #1929 正在编辑的推理 part。**remember 而非 rememberSaveable**：编辑器内容不进
    // saved state（大文本切后台 TransactionTooLargeException，上游 ed3569c7 同款）。
    var editingReasoning by remember { mutableStateOf<UIMessagePart.Reasoning?>(null) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalWorkspaceId provides assistant?.workspaceId?.toString()) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // #1812：半截生成失败的草稿横幅——放置在消息上方，重试复用现有 regenerate
        if (message.annotations.any { it is UIMessageAnnotation.PartialFailure }) {
            PartialFailureBanner(
                onRegenerate = onRegenerate,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 输出不完整提示（本批）：只挂在**末条**消息上——只有它还能「接着写」。
        // 三档成因分开表述（成本/可续性不同）：裸断流与撞上限可续写，内容拦截不提供续写按钮。
        if (lastMessage && message.role == MessageRole.ASSISTANT) {
            when {
                message.finishIncomplete -> IncompleteOutputBanner(
                    text = stringResource(R.string.message_incomplete_output_stream_cut),
                    onContinue = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                )

                isOutputLimitTruncation(message.finishReason) -> IncompleteOutputBanner(
                    text = stringResource(R.string.message_incomplete_output_limit),
                    onContinue = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                )

                isContentFilterTruncation(message.finishReason) -> IncompleteOutputBanner(
                    text = stringResource(R.string.message_incomplete_output_filtered),
                    onContinue = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // #1906：引用块——显示这条消息发出时引用的历史消息快照（不随原消息后续编辑而变）。
        // 点击跳转原消息；onQuoteClick 为 null 时（如下拉预览）卡片不可点。
        message.annotations.filterIsInstance<UIMessageAnnotation.QuotedMessage>()
            .forEach { quote ->
                MessageQuoteCard(
                    quote = quote,
                    onClick = onQuoteClick?.let { jump -> { jump(quote.messageId) } },
                )
            }

        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                assistant = assistant,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToolSkip = onToolSkip,
                onApproveAll = onApproveAll,
                onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                onReasoningEditRequest = if (onEditReasoning != null) {
                    { editingReasoning = it }
                } else null,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message, loading = loading)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onEditSegment = onEditSegment,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            onQuote = onQuote,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }

    if (editingReasoning != null) {
        val target = editingReasoning!!
        ReasoningEditDialog(
            initialText = target.reasoning,
            providerSigned = target.isProviderSigned(),
            onDismissRequest = { editingReasoning = null },
            onConfirm = { text ->
                editingReasoning = null
                onEditReasoning?.invoke(message.id, target, text)
            },
        )
    }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolSkip: ((toolCallId: String) -> Unit)? = null,
    onApproveAll: (() -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    onReasoningEditRequest: ((UIMessagePart.Reasoning) -> Unit)? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    // 整轮折叠（docs/design/design-whole-turn-folding.md）：过程解说并进思考链、
    // 只留最后一次工具调用之后的文本作最终回答；关闭时保持旧行为。
    val wholeTurnFolding = settings.displaySetting.wholeTurnFolding
    val groupedParts = remember(parts, role, wholeTurnFolding) {
        parts.groupMessageParts(role = role, wholeTurnFolding = wholeTurnFolding)
    }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    // 纯“思考 + 解说”链仍是自适应宽度（加入解说步骤后这个判断不修会恒为 false）
                    val isReasoningOnlyBlock = block.steps.fastAll {
                        it is ThinkingStep.ReasoningStep || it is ThinkingStep.NarrationStep
                    }
                    // 折叠块与回答同色时看起来就是一段正文 ⇒ 折叠块用低一档的容器色
                    val cardColors = CardDefaults.cardColors(
                        containerColor = (if (block.canCollapse) {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }).copy(alpha = settings.displaySetting.bubbleOpacity),
                    )
                    val stepContent: @Composable ChainOfThoughtScope.(ThinkingStep) -> Unit = { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                        onEditRequest = onReasoningEditRequest?.let { cb ->
                                            { cb(step.reasoning) }
                                        },
                                        // 签名块（Claude thinking / Gemini thoughtSignature）禁用编辑
                                        editDisabled = step.reasoning.isProviderSigned(),
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onToolSkip = onToolSkip,
                                        onApproveAll = onApproveAll,
                                        workspaceId = assistant?.workspaceId?.toString(),
                                        memoryStoreId = assistant?.memoryId(),
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.tool.toolName }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }

                            is ThinkingStep.NarrationStep -> {
                                key("narration_${step.index}") {
                                    ChatMessageNarrationStep(text = step.text)
                                }
                            }
                        }
                    }
                    // key 让折叠/展开后的槽位不串位（步骤级状态按位置错配）
                    key(block.steps.first().stableKey()) {
                        if (block.canCollapse || block.forceExpand) {
                            WholeTurnFoldBlock(
                                steps = block.steps,
                                canCollapse = block.canCollapse,
                                forceExpand = block.forceExpand,
                                loading = loading,
                                cardColors = cardColors,
                                collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                content = stepContent,
                            )
                        } else {
                            ChainOfThought(
                                modifier = Modifier.animateContentSize(),
                                steps = block.steps,
                                collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                cardColors = cardColors,
                                content = stepContent,
                            )
                        }
                    }
                }
            }

            is MessagePartBlock.ChartBlock -> key(block.index) {
                val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                spec?.let { ChartCard(spec = it) }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (role == MessageRole.USER) {
                                Surface(
                                    modifier = Modifier.animateContentSize(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        MarkdownBlock(
                                            content = part.text.renderSafe().replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation,
                                            streaming = loading,
                                        )
                                    }
                                }
                            } else {
                                if (settings.displaySetting.showAssistantBubble) {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = part.text.renderSafe().replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                                streaming = loading,
                                            )
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = part.text.renderSafe().replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        streaming = loading,
                                        modifier = Modifier.animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    context.normalizeLocalFileUrl(part.url).toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    context.normalizeLocalFileUrl(part.url).toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = context.normalizeLocalFileUrl(part.url),
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    context.normalizeLocalFileUrl(part.url).toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // 仅 URL 引用进入尾部折叠列表（PartialFailure 横幅已在消息顶部渲染，不算入引文数）
    val urlAnnotations = annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()

    // Annotations (always rendered at the end)
    if (urlAnnotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        urlAnnotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }

                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, urlAnnotations.size))
            }
        }
    }
}

/**
 * #1812：半截生成失败草稿的警示横幅。
 *
 * 提示「生成中断，已保留部分内容」，提供「重新生成」入口（复用现有 regenerate 管道，
 * 成功后原草稿成为候选 #0、新回复成为候选 #1，无需新 UI 分支）。
 */
@Composable
private fun PartialFailureBanner(
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.message_partial_failure_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRegenerate) {
                Text(stringResource(R.string.message_partial_failure_regenerate))
            }
        }
    }
}


/**
 * 输出不完整横幅（本批）：裸断流 / 撞输出上限 / 内容拦截三种成因共用一套外观，文案由调用方给。
 *
 * 与 [PartialFailureBanner] 的区别：后者是「生成**失败**留下的草稿」（#1812 异常路径），
 * 这里是「生成**正常收尾但内容不完整**」——不会自动重发，用户可选择接着写（续写追加到同一条消息，
 * 不新建候选，故按钮文案是「继续生成」而非「重新生成」）。
 */
@Composable
private fun IncompleteOutputBanner(
    text: String,
    onContinue: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (onContinue != null) {
                TextButton(onClick = onContinue) {
                    Text(stringResource(R.string.message_incomplete_output_continue))
                }
            }
        }
    }
}

/** 渲染前保护：超长消息文本截断，避免 Compose 测量高度溢出 Int.MAX（vet 图极长空白消息曾触发） */
private const val MAX_RENDER_TEXT_LENGTH = 1_100_000
private fun String.renderSafe(): String {
    if (length <= MAX_RENDER_TEXT_LENGTH) return this
    // 按码点截断（UTF-16 length 会劈开 emoji/代理对/组合字符）
    val sb = StringBuilder()
    var i = 0
    var count = 0
    while (i < length && count < MAX_RENDER_TEXT_LENGTH) {
        val cp = codePointAt(i)
        sb.appendCodePoint(cp)
        i += Character.charCount(cp)
        count++
    }
    return sb.append("\n\n[内容过长，已截断显示前 ${MAX_RENDER_TEXT_LENGTH} 字符]").toString()
}
