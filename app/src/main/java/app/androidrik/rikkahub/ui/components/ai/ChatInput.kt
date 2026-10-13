package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.rikkahub.utils.CONTEXT_USAGE_WARNING_RATIO
import app.androidrik.rikkahub.utils.formatContextLength
import app.androidrik.ai.provider.ModelType
import app.androidrik.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.Zap
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.datastore.getQuickMessagesOfAssistant
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.QuickMessage
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionContext
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionItem
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionList
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import app.androidrik.rikkahub.ui.components.message.MessageQuoteCard
import app.androidrik.rikkahub.ui.components.ui.KeepScreenOn
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalASRState
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.ui.pages.chat.VoicePhase
import app.androidrik.rikkahub.ui.pages.chat.VoiceSessionState
import app.androidrik.rikkahub.service.QueuedMessage
import kotlin.uuid.Uuid
import app.androidrik.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    hazeState: HazeState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    /**
     * **插队发送**：打断当前生成轮次，立刻发这条（用户 2026-10-13 拍板的语义）。
     *
     * 只在与 [onSendClick] 语义分岔时才显示入口——即「生成中 + 输入框非空」：
     * 此时点普通发送是**排队等**，点这里是**让当前轮次让路**。不显示确认弹窗（用户明确），
     * 半截回复会作为可编辑候选留在会话里，不是真丢。
     */
    onInterruptSendClick: () -> Unit = {},
    messageQueue: MessageQueueState = MessageQueueState(),
    onRemoveQueuedMessage: (Uuid) -> Unit = {},
    onBeginEditQueuedMessage: (Uuid) -> QueuedMessage? = { null },
    onFinishEditQueuedMessage: (Uuid, List<UIMessagePart>?) -> Unit = { _, _ -> },
    onResumeMessageQueue: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
    contextUsage: ContextUsage? = null,
    onShowContextSheet: () -> Unit = {},
    pendingQuote: UIMessageAnnotation.QuotedMessage? = null,
    onCancelQuote: () -> Unit = {},
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow
    val inputHazeStyle = HazeBlurStyle.Material3 {
        blurRadius(12.dp)
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 键盘弹出时让底部两角变直角，贴合 IME
    val imeVisible = WindowInsets.isImeVisible
    val containerShape = if (imeVisible) {
        MaterialTheme.shapes.largeIncreased.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
    } else {
        MaterialTheme.shapes.largeIncreased
    }

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = if (imeVisible) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .then(
                        if (settings.displaySetting.enableBlurEffect) Modifier.hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = inputHazeStyle,
                        )
                        else Modifier
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (voiceState.phase != VoicePhase.Off) {
                        VoiceModeRow(
                            state = voiceState,
                            onStop = onStopVoiceMode,
                            onRetry = { onStartVoiceMode?.invoke() },
                        )
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    MessageQueuePanel(
                        state = messageQueue,
                        onRemove = onRemoveQueuedMessage,
                        onBeginEdit = onBeginEditQueuedMessage,
                        onFinishEdit = onFinishEditQueuedMessage,
                        onResume = onResumeMessageQueue,
                    )

                    // #1906：待发引用预览（可取消）。放队列面板之后、输入行之前——
                    // 它描述的是「下一条要发的消息」，与输入行同属一个语义块。
                    pendingQuote?.let { quote ->
                        MessageQuoteCard(
                            quote = quote,
                            onCancel = onCancelQuote,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    TextInputRow(
                        state = state,
                        completionProviders = completionProviders,
                        onSendMessage = { sendMessage() }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Model Picker
                            ModelSelector(
                                modelId = assistant.chatModelId ?: settings.chatModelId,
                                providers = settings.providers,
                                onSelect = {
                                    onUpdateChatModel(it)
                                },
                                type = ModelType.CHAT,
                                onlyIcon = true,
                                modifier = Modifier,
                            )

                            // Search
                            val enableSearchMsg = stringResource(R.string.web_search_enabled)
                            val disableSearchMsg = stringResource(R.string.web_search_disabled)
                            val chatModel = settings.getCurrentChatModel()
                            SearchPickerButton(
                                enableSearch = enableSearch,
                                settings = settings,
                                onToggleSearch = { enabled ->
                                    onToggleSearch(enabled)
                                    toaster.show(
                                        message = if (enabled) enableSearchMsg else disableSearchMsg,
                                        duration = 1.seconds,
                                        type = if (enabled) {
                                            ToastType.Success
                                        } else {
                                            ToastType.Normal
                                        }
                                    )
                                },
                                onUpdateSearchService = onUpdateSearchService,
                                model = chatModel,
                            )

                            // Reasoning
                            val model = settings.getCurrentChatModel()
                            if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                                ReasoningButton(
                                    reasoningLevel = assistant.reasoningLevel,
                                    onUpdateReasoningLevel = {
                                        onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                    },
                                    onlyIcon = true,
                                )
                            }

                            // 已使用上下文占用（usage 优先 + 本地估算兜底，与压缩触发判断同源）
                            contextUsage?.let { usage ->
                                ContextUsageBarButton(
                                    usedTokens = usage.usedTokens,
                                    windowTokens = usage.windowTokens,
                                    // 形态可切换（偏好设置 → 通用）；数值来源与阈值两种形态共用
                                    useRing = settings.displaySetting.useRingContextIndicator,
                                    onClick = onShowContextSheet,
                                )
                            }

                        }

                        ActionIconButton(
                            onClick = onMoreClick
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }

                        if (asrState.isAvailable || asrState.isRecording) {
                            AsrButton(
                                state = asrState,
                                onClick = {
                                    when (asrState.status) {
                                        ASRStatus.Listening -> asr.stop()
                                        ASRStatus.Idle, ASRStatus.Error -> {
                                            if (!asrPermission.allRequiredPermissionsGranted) {
                                                asrPermission.requestPermissions()
                                            } else {
                                                asrBaseText = state.textContent.text.toString()
                                                asr.start { transcript ->
                                                    val spacer =
                                                        if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                    state.setMessageText(asrBaseText + spacer + transcript)
                                                }
                                            }
                                        }

                                        ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                    }
                                }
                            )
                        }

                        // 插队发送：只在「生成中 + 输入框非空」时出现——此时它与普通发送语义不同
                        // （普通发送是排队等，这个是打断并立刻发）。不用长按承载：长按已被
                        // 「发送但不触发回复」占用，且这是个破坏性动作，值得一个看得见的按钮。
                        // 不做二次确认（用户明确）：半截会落成可编辑候选，不是真丢。
                        AnimatedVisibility(
                            visible = loading && !state.isEmpty() && !asrState.isRecording,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            ActionIconButton(
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    onInterruptSendClick()
                                }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = stringResource(R.string.chat_interrupt_and_send),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !asrState.isRecording,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("chat_send_button")
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = loading && state.isEmpty() || !state.isEmpty(),
                                        onClick = {
                                            sendMessage()
                                        }, onLongClick = {
                                            sendMessageWithoutAnswer()
                                        }
                                    )
                            ) {
                                val showStop = loading && state.isEmpty()
                                val containerColor = when {
                                    showStop -> MaterialTheme.colorScheme.errorContainer
                                    state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                val contentColor = when {
                                    showStop -> MaterialTheme.colorScheme.onErrorContainer
                                    state.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else -> MaterialTheme.colorScheme.onPrimary
                                }
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = CircleShape,
                                    color = containerColor,
                                    content = {})
                                if (showStop) {
                                    KeepScreenOn()
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.stop),
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = HugeIcons.ArrowUp02,
                                        contentDescription = stringResource(R.string.send),
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(R.string.chat_input_placeholder))
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
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

/** 会话上下文占用数据：由 ChatPage 用 [app.androidrik.rikkahub.utils.estimateWindowTokens] 组装传入。 */
data class ContextUsage(
    val usedTokens: Int,
    val windowTokens: Int,
)

/**
 * 已使用上下文占用：细进度条 + 右侧百分比；**点击打开上下文容量面板**。
 * 达到窗口 80%（CONTEXT_USAGE_WARNING_RATIO）变红警告——阈值与面板、标题旁读数共用同一常量。
 *
 * 这里刻意**只给状态不给数字**（数字读数在标题旁），四处入口分工见 `docs/roadmap.md` 同日条目。
 */
@Composable
private fun ContextUsageBarButton(
    usedTokens: Int,
    windowTokens: Int,
    useRing: Boolean,
    onClick: () -> Unit,
) {
    val fraction = if (windowTokens > 0) {
        (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isWarning = windowTokens > 0 && fraction >= CONTEXT_USAGE_WARNING_RATIO
    val barColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val textColor = if (isWarning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val percent = (fraction * 100f).roundToInt()
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(450),
        label = "contextUsageFraction",
    )

    // 外层容器高度必须与相邻图标按钮（IconButton 默认 48dp）**一致**：这一行是 Top 对齐的，
    // 各 item 的「中心」由各自高度决定 —— 40dp 容器会比 48dp 的邻居高约 4dp（实测 12px 偏差）。
    Box(
        modifier = Modifier.height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(percent = 50))
                .clickable { onClick() }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (useRing) {
                // 圆环形态：百分比放在环内（空间紧，只给整数；精确数值在标题旁读数与面板里）
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(24.dp)) {
                        val stroke = 3.dp.toPx()
                        val inset = stroke / 2f
                        drawArc(
                            color = trackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                        if (animatedFraction > 0f) {
                            drawArc(
                                color = barColor,
                                startAngle = -90f,
                                sweepAngle = 360f * animatedFraction,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                    Text(
                        text = percent.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = textColor,
                        maxLines = 1,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(modifier = Modifier.size(width = 34.dp, height = 5.dp)) {
                        val radius = size.height / 2f
                        drawRoundRect(
                            color = trackColor,
                            cornerRadius = CornerRadius(radius),
                        )
                        if (animatedFraction > 0f) {
                            drawRoundRect(
                                color = barColor,
                                size = Size(
                                    width = (size.width * animatedFraction).coerceAtLeast(size.height),
                                    height = size.height,
                                ),
                                cornerRadius = CornerRadius(radius),
                            )
                        }
                    }
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = textColor,
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = 28.dp),
                    )
                }
            }
        }
    }

}
