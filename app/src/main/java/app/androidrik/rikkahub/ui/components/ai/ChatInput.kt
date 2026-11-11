package app.androidrik.rikkahub.ui.components.ai

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.AppTextField as TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
import dev.chrisbanes.haze.HazeState
import app.androidrik.rikkahub.ui.components.haze.chatInputBackgroundEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.ai.core.reasoningPresets
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.util.reasoningOverrideKeys
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
import app.androidrik.rikkahub.data.ai.openai.SubscriptionProviderLock
import app.androidrik.rikkahub.data.ai.openai.allowsSendingWith
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
import app.androidrik.rikkahub.ui.components.ai.completion.completionContext
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
    /**
     * 本对话是否「不使用记忆」（隐身模式）。用于会话内记忆入口的状态显示
     * （见 `MemoryButton` 与 docs/design/design-memory-injection-tiering.md §8.3）。
     */
    memoryDisabled: Boolean = false,
    onToggleMemoryDisabled: (Boolean) -> Unit = {},
    /** 会话级「固化记忆」：本对话采纳当前锚点集合 */
    onFreezeMemory: () -> Unit = {},
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
     *
     * [hasPendingInteraction]：AI 反问/工具审批待答（此时生成已结束、loading=false），
     * 入口同样显示、语义变为「跳过提问并发送」（design-ask-user-interrupt §3.2）。
     */
    onInterruptSendClick: () -> Unit = {},
    onRetainTasksSendClick: () -> Unit = {},
    hasPendingInteraction: Boolean = false,
    messageQueue: MessageQueueState = MessageQueueState(),
    onRemoveQueuedMessage: (Uuid) -> Unit = {},
    onBeginEditQueuedMessage: (Uuid) -> QueuedMessage? = { null },
    onFinishEditQueuedMessage: (Uuid, List<UIMessagePart>?) -> Unit = { _, _ -> },
    /** 把某条待发消息置顶（下一条就发它）；只改顺序，不打断当前轮次。 */
    onMoveQueuedMessageToFront: (Uuid) -> Unit = {},
    onResumeMessageQueue: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
    contextUsage: ContextUsage? = null,
    onShowContextSheet: () -> Unit = {},
    pendingQuote: UIMessageAnnotation.QuotedMessage? = null,
    onCancelQuote: () -> Unit = {},
    /** Non-null for a frozen conversation; null retains the global model setting behavior. */
    onUpdateBuiltInSearch: ((Boolean) -> Unit)? = null,
    /**
     * 订阅对话的提供商锁定（非空 = 本对话已使用 ChatGPT 订阅模型）。
     *
     * Codex 后端的推理是它自家专属的加密块，换 provider 后既解不开也回传不了，
     * 因此锁定期间模型选择器只列出该提供商的模型，选中别家模型会被拦下并提示。
     */
    modelProviderLock: SubscriptionProviderLock? = null,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    // 订阅对话锁定：选择器只列同一提供商的模型；选中别家模型的兜底提示文案先取好
    // （stringResource 不能在 onSelect 回调里调用）
    val lockNotice = if (modelProviderLock != null) {
        stringResource(R.string.chat_input_subscription_provider_locked)
    } else {
        null
    }
    val lockSendBlockedNotice = if (modelProviderLock != null) {
        stringResource(R.string.chat_input_subscription_provider_send_blocked)
    } else {
        null
    }
    val modelPickerProviders = remember(settings.providers, modelProviderLock) {
        modelProviderLock?.filterProviders(settings.providers) ?: settings.providers
    }
    // 开启背景效果时容器透明，铺底/着色由效果负责（BLUR/GLASS 两条路线都如此）
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow

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

    /**
     * 订阅对话锁定的发送兜底：助手模型可能在别处（如助手设置页）被改成别家 provider，
     * 此时这条对话已含订阅模型生成的加密内容，不能再发给别家——拦住并提示切回。
     */
    fun providerLockAllowsSend(): Boolean {
        if (modelProviderLock == null) return true
        val allowed = modelProviderLock.allowsSendingWith(
            model = settings.getCurrentChatModel(),
            providers = settings.providers,
        )
        if (allowed) return true
        toaster.show(
            message = lockSendBlockedNotice.orEmpty(),
            duration = 3.seconds,
            type = ToastType.Error,
        )
        return false
    }

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) {
            onCancelClick()
            return
        }
        if (!providerLockAllowsSend()) return
        onSendClick()
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
            // 输入栏背景图（轻量个性化，design-light-personalization.md 决策 4/5）：
            // 与玻璃/模糊互斥——模糊采样的是页面背景，与输入框内叠图语义冲突；
            // 生效时底色转透明、跳过 blur modifier，图放内容层之下。
            val inputBackgroundImage = settings.displaySetting.chatInputBackgroundImage
            val useInputBackground = inputBackgroundImage.isNotBlank()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .then(
                        if (useInputBackground) {
                            Modifier
                        } else {
                            Modifier.chatInputBackgroundEffect(
                                hazeState = hazeState,
                                displaySetting = settings.displaySetting,
                                containerColor = hazeTintColor,
                                shape = containerShape,
                            )
                        }
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect || useInputBackground) {
                    Color.Transparent
                } else {
                    hazeTintColor
                },
            ) {
                Box {
                    if (useInputBackground) {
                        AsyncImage(
                            model = inputBackgroundImage,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(settings.displaySetting.chatInputBackgroundOpacity.coerceIn(0f, 1f)),
                        )
                    }
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
                        // 待答（ask_user/审批）时队列被 pending 闸堵着不派发，给出可见提示
                        // （design-ask-user-interrupt §3.3；此前文本模式静默滞留，
                        // 与语音模式的显式报错不对称）
                        waitingForInteraction = hasPendingInteraction,
                        onRemove = onRemoveQueuedMessage,
                        onBeginEdit = onBeginEditQueuedMessage,
                        onFinishEdit = onFinishEditQueuedMessage,
                        onResume = onResumeMessageQueue,
                        onMoveToFront = onMoveQueuedMessageToFront,
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

                    AnimatedVisibility(visible = (loading || hasPendingInteraction) && !state.isEditing() && !state.isEmpty() && !asrState.isRecording) {
                        TextButton(
                            modifier = Modifier.testTag("chat_retain_tasks_send"),
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                onRetainTasksSendClick()
                            },
                        ) { Text("保留任务并立即发送") }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            // CenterVertically：行内 item 高度不一（ModelSelector IconButton 48dp、
                            // 其余 ToggleSurface 40dp），顶对齐会让矮 item 的中心整体偏上——
                            // 上下文圆环因此显得位置偏高；按中心对齐后全部落在同一水平线上
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Model Picker
                            ModelSelector(
                                modelId = assistant.chatModelId ?: settings.chatModelId,
                                providers = modelPickerProviders,
                                notice = lockNotice,
                                onSelect = { model ->
                                    if (modelProviderLock != null && !modelProviderLock.allows(model, settings.providers)) {
                                        // 兜底（列表已过滤）：锁定期间换到别家 provider 会被拦下
                                        lockNotice?.let { toaster.show(message = it, duration = 3.seconds) }
                                    } else {
                                        onUpdateChatModel(model)
                                    }
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
                                onUpdateBuiltInSearch = onUpdateBuiltInSearch,
                            )

                            // Reasoning
                            val model = settings.getCurrentChatModel()
                            if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                                val overrideKeys = remember(model.id, model.customBodies, assistant.customBodies) {
                                    (assistant.customBodies + model.customBodies).reasoningOverrideKeys()
                                }
                                ReasoningButton(
                                    reasoningLevel = assistant.reasoningLevel,
                                    presets = reasoningPresets(model, model.findProvider(settings.providers)),
                                    selectedPresetId = assistant.reasoningPresetId,
                                    onSelectPreset = { preset ->
                                        onUpdateAssistant(assistant.copy(
                                            reasoningLevel = preset?.level ?: assistant.reasoningLevel,
                                            reasoningPresetId = preset?.id,
                                        ))
                                    },
                                    onUpdateReasoningLevel = {
                                        onUpdateAssistant(assistant.copy(reasoningLevel = it, reasoningPresetId = null))
                                    },
                                    onlyIcon = true,
                                    overrideKeys = overrideKeys,
                                )
                            }

                            // 已使用上下文占用（usage 优先 + 本地估算兜底，与压缩触发判断同源）
                            contextUsage?.let { usage ->
                                ContextUsageRingButton(
                                    usedTokens = usage.usedTokens,
                                    windowTokens = usage.windowTokens,
                                    onClick = onShowContextSheet,
                                )
                            }

                            // 记忆（会话内入口）：**排在上下文占用读数的右侧** —— 两者回答的是
                            // 同一个问题的两半（"已经用了多少" / "记忆这块用不用、要不要冻结"），
                            // 摆在一起读起来才连得上。状态做在图标上，点开是两行选择器
                            // （隐身开关 / 固化本对话）。助手不用记忆时不占位。
                            if (assistant.enableMemory) {
                                // 两条文案都在 composable 作用域先取好：toast 的文案取决于**切换后**的
                                // 值，而 stringResource 不能在回调里调用
                                val memoryOffMsg = stringResource(R.string.chat_memory_disabled_toast_on)
                                val memoryOnMsg = stringResource(R.string.chat_memory_disabled_toast_off)
                                MemoryButton(
                                    memoryDisabled = memoryDisabled,
                                    onToggleMemoryDisabled = { disabled ->
                                        onToggleMemoryDisabled(disabled)
                                        toaster.show(
                                            message = if (disabled) memoryOffMsg else memoryOnMsg,
                                            duration = 1.seconds,
                                        )
                                    },
                                    onFreezeMemory = onFreezeMemory,
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
                        // ask_user/审批待答（hasPendingInteraction，loading=false）时同样出现，
                        // 语义变为「跳过提问并发送」（design-ask-user-interrupt §3.2）——
                        // 待答期间普通发送会被 pending 闸静默滞留，没有这个入口用户就没有出路。
                        AnimatedVisibility(
                            visible = (loading || hasPendingInteraction) && !state.isEmpty() && !asrState.isRecording,
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
                                    contentDescription = stringResource(
                                        if (!loading && hasPendingInteraction) R.string.chat_skip_question_and_send
                                        else R.string.chat_interrupt_and_send
                                    ),
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
                shape = appSkinRoundedShape(16f),
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
        val inlineScrollState = rememberScrollState()
        val fullScreenScrollState = rememberScrollState()
        val inputFocusRequester = remember { FocusRequester() }
        var restoreInputFocus by remember { mutableStateOf(false) }
        var inlineHeightPx by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val focusManager = LocalFocusManager.current
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        var completionRequest by remember { mutableStateOf<ChatCompletionContext?>(null) }
        val attachmentToaster = LocalToaster.current
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
                                        listOf(uri), onRejected = { attachmentToaster.show(it) },
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

        LaunchedEffect(completionProviders, isFocused, isFullScreen) {
            if (!isFocused || isFullScreen || completionProviders.isEmpty()) {
                completionList = null
                completionRequest = null
                return@LaunchedEffect
            }

            snapshotFlow {
                completionContext(state.textContent.text, state.textContent.selection)
            }.collectLatest { context ->
                completionList = null
                completionRequest = null
                if (context == null) return@collectLatest
                val lists = withContext(Dispatchers.Default) {
                    completionProviders.mapNotNull { provider ->
                        try {
                            provider.complete(context)
                                ?.takeIf { it.items.isNotEmpty() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val primary = lists.firstOrNull()
                completionRequest = context
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
            val request = completionRequest
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    if (request != null && request == completionContext(state.textContent.text, state.textContent.selection)) {
                        state.applyCompletion(list.replacementRange, item)
                    }
                    completionList = null
                },
            )
        }

        LaunchedEffect(isFullScreen, restoreInputFocus) {
            if (!isFullScreen && restoreInputFocus) {
                inputFocusRequester.requestFocus()
                restoreInputFocus = false
            }
        }
        // Both hosts share TextFieldState, but only one may observe/layout it at a time.
        // Keep each host's scroll state outside this branch so switching does not reset it.
        if (!isFullScreen) TextField(
            state = state.textContent,
            scrollState = inlineScrollState,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .focusRequester(inputFocusRequester)
                .onSizeChanged { inlineHeightPx = it.height }
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
                        modifier = Modifier.testTag("chat_input_expand"),
                        onClick = {
                            focusManager.clearFocus()
                            completionList = null
                            isFullScreen = true
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
        ) else Spacer(Modifier.height(with(density) { inlineHeightPx.toDp() }))
        if (isFullScreen) {
            FullScreenEditor(state = state, scrollState = fullScreenScrollState) {
                isFullScreen = false
                restoreInputFocus = true
            }
        }
    }
}
