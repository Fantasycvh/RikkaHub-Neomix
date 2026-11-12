package app.androidrik.rikkahub.ui.pages.assistant.detail

import app.androidrik.rikkahub.ui.components.ui.appSkinTextSize
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh03
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.key
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import me.rerere.hugeicons.stroke.DragDropVertical
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.transformers.DefaultPlaceholderProvider
import app.androidrik.rikkahub.data.ai.transformers.TemplateTransformer
import app.androidrik.rikkahub.data.ai.transformers.TransformerContext
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.data.model.cardPartsDivergeFromSystemPrompt
import app.androidrik.rikkahub.data.model.effectiveCardSlotOrder
import app.androidrik.rikkahub.data.model.hasCardParts
import app.androidrik.rikkahub.data.model.moveCardSlot
import app.androidrik.rikkahub.data.model.rebuildSystemPromptFromCardParts
import app.androidrik.rikkahub.data.model.AssistantAffectScope
import app.androidrik.rikkahub.data.model.AssistantRegex
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.RegexDiagnosisIssue
import app.androidrik.rikkahub.data.model.diagnoseRegexRule
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.ui.components.message.ChatMessage
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TextArea
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.ui.theme.ChatFontProvider
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.utils.UiState
import app.androidrik.rikkahub.utils.insertAtCursor
import app.androidrik.rikkahub.utils.onError
import app.androidrik.rikkahub.utils.onSuccess
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun AssistantPromptPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_prompt))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantPromptContent(
            innerPadding = innerPadding,
            assistant = assistant,
            settings = settings,
            onUpdate = { vm.update(it) },
            // 文本域走字段级 transform（去抖提交 / 离页补写在 VM 的 AppScope 上，
            // 不用 `vm.update(assistant.copy(...))` 这种整对象写）
            onSystemPromptChange = vm::updateSystemPrompt,
            onMessageTemplateChange = vm::updateMessageTemplate,
            onSystemMessageTemplateChange = vm::updateSystemMessageTemplate,
            onPresetMessageTextChange = vm::updatePresetMessageText,
            onCardSlotTextChange = vm::updateCardSlotValue,
            onRegexNameChange = vm::updateRegexName,
            onRegexFindRegexChange = vm::updateRegexFindRegex,
            onRegexReplaceStringChange = vm::updateRegexReplaceString,
        )
    }
}

@Composable
private fun AssistantPromptContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onMessageTemplateChange: (String) -> Unit,
    onSystemMessageTemplateChange: (String) -> Unit,
    onPresetMessageTextChange: (Uuid, String) -> Unit,
    onCardSlotTextChange: (CardSlot, String) -> Unit,
    onRegexNameChange: (Uuid, String) -> Unit,
    onRegexFindRegexChange: (Uuid, String) -> Unit,
    onRegexReplaceStringChange: (Uuid, String) -> Unit,
) {
    val context = LocalContext.current
    val templateTransformer = koinInject<TemplateTransformer>()

    // 系统提示词：本地权威编辑态 + 去抖单向写出（字段级 transform）。旧写法用
    // `snapshotFlow` 逐键 `onUpdate(assistant.copy(systemPrompt = ...))` —— 每键重写整份
    // Settings，且带「整对象写丢更新」的读-改-写竞态。提到最外层是为了让下面角色卡的
    // 「回到卡片原文」按钮能同步本地编辑态（程序化回填只发生在用户点击时，允许）。
    val systemPromptValue = rememberPersistedTextFieldState(
        externalValue = assistant.systemPrompt,
        resetKey = assistant.id,
        onCommit = onSystemPromptChange,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            // imePadding 在 verticalScroll 之前（视口侧）：键盘弹起先收缩视口，
            // 内容不再随 IME insets 逐帧改高度（否则"滚动到光标"每帧改落点 ⇒ 整页抖动）。
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextArea(
                    state = systemPromptValue,
                    label = stringResource(R.string.assistant_page_system_prompt),
                    minLines = 5,
                    maxLines = 10
                )

                Column {
                    Text(
                        text = stringResource(R.string.assistant_page_available_variables),
                        style = MaterialTheme.typography.labelSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        DefaultPlaceholderProvider.placeholders.forEach { (k, info) ->
                            Tag(
                                onClick = {
                                    systemPromptValue.insertAtCursor("{{$k}}")
                                }
                            ) {
                                info.displayName()
                                Text(": {{$k}}")
                            }
                        }
                    }
                }
            }
        }

        // 角色卡分区：导入时保留的原始字段（此前被压成一条不可分的 systemPrompt 而永久丢失）。
        // 仅作查看/单独编辑的「零件」；实际发送的仍是上面的 System Prompt。
        if (assistant.hasCardParts) {
            CardPartsCard(
                assistant = assistant,
                onUpdate = onUpdate,
                onCardSlotTextChange = onCardSlotTextChange,
                onRebuildPrompt = {
                    // 显式用户动作，允许回填活跃编辑框（AGENTS 第 4 条：先比较再回填）。
                    // rebuild 只改 systemPrompt（见 Assistant.rebuildSystemPromptFromCardParts），
                    // 所以不再整对象 onUpdate 写，走字段级 transform。
                    val rebuilt = assistant.rebuildSystemPromptFromCardParts()
                    if (rebuilt.systemPrompt != systemPromptValue.text.toString()) {
                        systemPromptValue.setTextAndPlaceCursorAtEnd(rebuilt.systemPrompt)
                    }
                    onSystemPromptChange(rebuilt.systemPrompt)
                },
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_system_prompt))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_system_prompt_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.allowConversationSystemPrompt,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    allowConversationSystemPrompt = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_prompt_injection))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_prompt_injection_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.allowConversationPromptInjection,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    allowConversationPromptInjection = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            // 本地权威编辑态 + 去抖单向写出（AGENTS「TextField Host Rules」第 4 条）：
            // 旧写法 `value = assistant.messageTemplate` 是持久态投影，落盘后旧值回灌活跃
            // 编辑框 ⇒ Compose 重置内部编辑态（中文 IME 组合被打断 / 光标回跳）。
            val messageTemplateState = rememberPersistedTextFieldState(
                externalValue = assistant.messageTemplate,
                resetKey = assistant.id,
                onCommit = onMessageTemplateChange,
            )
            val messageTemplateText = messageTemplateState.text
            val missingMessage = "{{ message }}" !in messageTemplateText
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.assistant_page_message_template))
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                // 程序化回填只发生在用户点「重置」时（不是持久态回灌）：
                                // 直接改本地编辑态，写出仍交给 hook 的去抖/离页补写。
                                messageTemplateState.setTextAndPlaceCursorAtEnd("{{ message }}")
                            },
                            enabled = messageTemplateText.toString() != "{{ message }}",
                        ) {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = null,
                            )
                        }
                    }
                },
                content = {
                    OutlinedTextField(
                        state = messageTemplateState,
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 5,
                            maxHeightInLines = 15,
                        ),
                        isError = missingMessage,
                        supportingText = if (missingMessage) {
                            { Text(stringResource(R.string.assistant_page_message_template_missing_message)) }
                        } else null,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = appSkinTextSize(12.sp),
                            fontFamily = JetbrainsMono,
                            lineHeight = appSkinTextSize(16.sp)
                        )
                    )
                },
                description = {
                    Text(stringResource(R.string.assistant_page_message_template_desc))
                    Text(buildAnnotatedString {
                        append(stringResource(R.string.assistant_page_template_variables_label))
                        append(" ")
                        append(stringResource(R.string.assistant_page_template_variable_role))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ role }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_message))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ message }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_time))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ time }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_date))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ date }}")
                        }
                    })
                }
            )
            // 同 messageTemplate：本地权威 + 去抖单向写出，重置按钮只改本地编辑态。
            val systemMessageTemplateState = rememberPersistedTextFieldState(
                externalValue = assistant.systemMessageTemplate,
                resetKey = assistant.id,
                onCommit = onSystemMessageTemplateChange,
            )
            val systemMessageTemplateText = systemMessageTemplateState.text
            val systemMessageMissingMessage =
                systemMessageTemplateText.isNotBlank() && "{{ message }}" !in systemMessageTemplateText
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.assistant_page_system_message_template))
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                systemMessageTemplateState.setTextAndPlaceCursorAtEnd("")
                            },
                            enabled = systemMessageTemplateText.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = null,
                            )
                        }
                    }
                },
                content = {
                    OutlinedTextField(
                        state = systemMessageTemplateState,
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 3,
                            maxHeightInLines = 10,
                        ),
                        isError = systemMessageMissingMessage,
                        supportingText = if (systemMessageMissingMessage) {
                            { Text(stringResource(R.string.assistant_page_system_message_template_missing_message)) }
                        } else null,
                        placeholder = {
                            Text("<|{{ role }}|>\n{{ message }}")
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = appSkinTextSize(12.sp),
                            fontFamily = JetbrainsMono,
                            lineHeight = appSkinTextSize(16.sp)
                        )
                    )
                },
                description = {
                    Text(stringResource(R.string.assistant_page_system_message_template_desc))
                    Text(stringResource(R.string.assistant_page_system_message_template_variables))
                }
            )
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_template_preview),
                    style = MaterialTheme.typography.titleSmall
                )
                val rawMessages = buildList {
                    add(UIMessage.user(stringResource(R.string.assistant_prompt_preview_sample_user)))
                    add(UIMessage.assistant(stringResource(R.string.assistant_prompt_preview_sample_assistant)))
                    // 设了「系统内容模板」时把**合成**样本也放进预览：否则用户看不到该模板的效果，
                    // 也无从发现「system 与注入默认不走任何模板」这件事（预览盲区）。
                    if (assistant.systemMessageTemplate.isNotBlank()) {
                        add(UIMessage.system(stringResource(R.string.assistant_prompt_preview_sample_system)).copy(isSynthetic = true))
                    }
                }
                val preview by produceState<UiState<List<UIMessage>>>(
                    UiState.Success(rawMessages),
                    assistant
                ) {
                    value = runCatching {
                        UiState.Success(
                            templateTransformer.transform(
                                ctx = TransformerContext(
                                    context = context,
                                    model = Model(modelId = "gpt-4o", displayName = "GPT-4o"),
                                    assistant = assistant,
                                    settings = settings
                                ),
                                messages = rawMessages
                            )
                        )
                    }.getOrElse {
                        UiState.Error(it)
                    }
                }
                preview.onError {
                    Text(
                        text = it.message ?: it.javaClass.name,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                preview.onSuccess {
                    ChatFontProvider(displaySetting = settings.displaySetting) {
                        it.fastForEach { message ->
                            ChatMessage(
                                node = message.toMessageNode(),
                                onFork = {},
                                onRegenerate = {},
                                onEdit = {},
                                onShare = {},
                                onDelete = {},
                                onUpdate = {},
                                lastMessage = false,
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_preset_messages))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_preset_messages_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                assistant.presetMessages.fastForEachIndexed { index, presetMessage ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Select(
                                options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                                selectedOption = presetMessage.role,
                                onOptionSelected = { role ->
                                    onUpdate(
                                        assistant.copy(
                                            presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                                if (i == index) {
                                                    msg.copy(role = role)
                                                } else {
                                                    msg
                                                }
                                            }
                                        )
                                    )
                                },
                                modifier = Modifier.width(160.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    onUpdate(
                                        assistant.copy(
                                            presetMessages = assistant.presetMessages.filterIndexed { i, _ ->
                                                i != index
                                            }
                                        )
                                    )
                                }
                            ) {
                                Icon(HugeIcons.Cancel01, null)
                            }
                        }
                        // 预设消息正文同样是持久态投影（`presetMessage.toText()`）：本地权威 +
                        // 去抖写出；resetKey 用消息 id（列表增删/换序时下标会漂移）。
                        val presetMessageState = rememberPersistedTextFieldState(
                            externalValue = presetMessage.toText(),
                            resetKey = presetMessage.id,
                            onCommit = { text -> onPresetMessageTextChange(presetMessage.id, text) },
                        )
                        OutlinedTextField(
                            state = presetMessageState,
                            modifier = Modifier.fillMaxWidth(),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        )
                    }
                }
                Button(
                    onClick = {
                        val lastRole = assistant.presetMessages.lastOrNull()?.role ?: MessageRole.ASSISTANT
                        val nextRole = when (lastRole) {
                            MessageRole.USER -> MessageRole.ASSISTANT
                            MessageRole.ASSISTANT -> MessageRole.USER
                            else -> MessageRole.USER
                        }
                        onUpdate(
                            assistant.copy(
                                presetMessages = assistant.presetMessages + UIMessage(
                                    role = nextRole,
                                    parts = listOf(UIMessagePart.Text(""))
                                )
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(HugeIcons.Add01, null)
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_regex_title))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_regex_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                val haptic = LocalHapticFeedback.current
                ReorderableColumn(
                    list = assistant.regexes,
                    onSettle = { fromIndex, toIndex ->
                        val regexes = assistant.regexes.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        onUpdate(assistant.copy(regexes = regexes))
                    },
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { index, regex, isDragging ->
                    key(regex.id) {
                        ReorderableItem(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AssistantRegexCard(
                                regex = regex,
                                onUpdate = onUpdate,
                                assistant = assistant,
                                index = index,
                                onRegexNameChange = onRegexNameChange,
                                onRegexFindRegexChange = onRegexFindRegexChange,
                                onRegexReplaceStringChange = onRegexReplaceStringChange,
                                modifier = Modifier.scale(if (isDragging) 0.95f else 1f),
                                dragHandleModifier = Modifier
                                    .size(48.dp)
                                    .longPressDraggableHandle(
                                        enabled = assistant.regexes.size > 1,
                                        onDragStarted = {
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.GestureThresholdActivate
                                            )
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    )
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes + AssistantRegex(
                                    id = Uuid.random()
                                )
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(HugeIcons.Add01, null)
                }
            }
        }
    }
}

@Composable
private fun CardPartsCard(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onCardSlotTextChange: (CardSlot, String) -> Unit,
    onRebuildPrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val diverged = assistant.cardPartsDivergeFromSystemPrompt()

    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_card_parts),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.assistant_page_card_parts_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 漂移提示：字段改了但 systemPrompt 还没重建 —— 不提示的话用户会以为改了没生效
            if (diverged) {
                Text(
                    text = stringResource(R.string.assistant_page_card_parts_diverged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 槽位视图：顺序 + 开关 + 内容，对齐 ST 的 prompt_order 槽位概念。
                    // 用 ReorderableColumn（与本页正则区同款）——该组件已解决嵌套滚动问题。
                    ReorderableColumn(
                        list = assistant.effectiveCardSlotOrder,
                        onSettle = { fromIndex, toIndex ->
                            onUpdate(assistant.moveCardSlot(fromIndex, toIndex))
                        },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { index, slot, isDragging ->
                        key(slot) {
                            ReorderableItem(modifier = Modifier.fillMaxWidth()) {
                                CardSlotRow(
                                    slot = slot,
                                    index = index,
                                    assistant = assistant,
                                    onUpdate = onUpdate,
                                    onCardSlotTextChange = onCardSlotTextChange,
                                    modifier = Modifier.scale(if (isDragging) 0.95f else 1f),
                                    dragHandleModifier = Modifier
                                        .size(40.dp)
                                        .longPressDraggableHandle(
                                            enabled = assistant.effectiveCardSlotOrder.size > 1,
                                            onDragStarted = {
                                                haptic.performHapticFeedback(
                                                    HapticFeedbackType.GestureThresholdActivate
                                                )
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            }
                                        ),
                                )
                            }
                        }
                    }

                    // 零件 → 发送文本的单向重建：零件是源，systemPrompt 是产物。
                    // 用户手改 systemPrompt 后想要「回到卡片原文」时用。
                    Button(
                        onClick = onRebuildPrompt,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Refresh03, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.assistant_page_rebuild_prompt))
                    }
                }
            }
        }
    }
}

/** 角色卡槽位的展示名。 */
@Composable
private fun CardSlot.label(): String = when (this) {
    CardSlot.GREETING -> stringResource(R.string.assistant_page_card_slot_greeting)
    CardSlot.SYSTEM_PROMPT -> stringResource(R.string.assistant_page_card_system_prompt)
    CardSlot.DESCRIPTION -> stringResource(R.string.assistant_page_card_description)
    CardSlot.PERSONALITY -> stringResource(R.string.assistant_page_card_personality)
    CardSlot.SCENARIO -> stringResource(R.string.assistant_page_card_scenario)
    CardSlot.EXAMPLE -> stringResource(R.string.assistant_page_card_example)
}

/**
 * 单个槽位：拖拽手柄 + 开关 + （开关打开时）内容编辑框。
 *
 * 开场白（[CardSlot.GREETING]）内容由姓名插值，故只给开关、不给编辑框。
 */
@Composable
private fun CardSlotRow(
    slot: CardSlot,
    index: Int,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onCardSlotTextChange: (CardSlot, String) -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val enabled = slot !in assistant.disabledCardSlots

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.DragDropVertical,
                contentDescription = stringResource(R.string.assistant_page_card_slot_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier,
            )
            Text(
                text = "${index + 1}. ${slot.label()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    onUpdate(
                        assistant.copy(
                            disabledCardSlots = if (on) {
                                assistant.disabledCardSlots - slot
                            } else {
                                assistant.disabledCardSlots + slot
                            }
                        )
                    )
                },
            )
        }

        // 内容编辑：开场白无独立内容（由姓名生成），其余槽位按类型读写对应字段
        CardSlotValueField(
            slot = slot,
            assistant = assistant,
            enabled = enabled,
            onCardSlotTextChange = onCardSlotTextChange,
        )
    }
}

/**
 * 槽位内容编辑框；[CardSlot.GREETING] 渲染为只读说明（内容由姓名插值）。
 */
@Composable
private fun CardSlotValueField(
    slot: CardSlot,
    assistant: Assistant,
    enabled: Boolean,
    onCardSlotTextChange: (CardSlot, String) -> Unit,
) {
    if (slot == CardSlot.GREETING) {
        Text(
            text = stringResource(R.string.assistant_page_card_slot_greeting_value, assistant.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val value = when (slot) {
        CardSlot.SYSTEM_PROMPT -> assistant.cardSystemPrompt
        CardSlot.DESCRIPTION -> assistant.cardDescription
        CardSlot.PERSONALITY -> assistant.cardPersonality
        CardSlot.SCENARIO -> assistant.cardScenario
        CardSlot.EXAMPLE -> assistant.cardExampleDialogue
        CardSlot.GREETING -> null
    }

    // 关掉的槽位不要求编辑；但仍允许查看内容（灰显），避免「关了以后看不到原先写了什么」
    CardPartField(
        slot = slot,
        label = slot.label(),
        value = value,
        onCommit = { text -> onCardSlotTextChange(slot, text) },
        enabled = enabled,
    )
}

@Composable
private fun CardPartField(
    slot: CardSlot,
    label: String,
    value: String?,
    onCommit: (String) -> Unit,
    enabled: Boolean = true,
) {
    // 旧写法 `var text by remember(value)` 把持久态当 key：每次落盘回灌就重建缓冲
    // （打字中被重置）。改成本地权威 + 去抖写出；resetKey 用槽位本身（唯一且稳定）。
    val state = rememberPersistedTextFieldState(
        externalValue = value.orEmpty(),
        resetKey = slot,
        onCommit = onCommit,
    )

    OutlinedTextField(
        state = state,
        label = { Text(label) },
        enabled = enabled,
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AssistantRegexCard(
    regex: AssistantRegex,
    onUpdate: (Assistant) -> Unit,
    assistant: Assistant,
    index: Int,
    onRegexNameChange: (Uuid, String) -> Unit,
    onRegexFindRegexChange: (Uuid, String) -> Unit,
    onRegexReplaceStringChange: (Uuid, String) -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    ElevatedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = dragHandleModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.DragDropVertical,
                        contentDescription = null,
                    )
                }
                Text(
                    text = regex.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 200.dp)
                )
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(enabled = enabled)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
                IconButton(
                    onClick = {
                        expanded = !expanded
                    }
                ) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null
                    )
                }
            }

            if (expanded) {

                // 三个文本域同属持久态投影：本地权威 + 去抖单向写出，resetKey 用正则 id
                // （拖拽排序 / 增删时下标会漂移，禁用值本身当 key）。
                val nameState = rememberPersistedTextFieldState(
                    externalValue = regex.name,
                    resetKey = regex.id,
                    onCommit = { name -> onRegexNameChange(regex.id, name) },
                )
                val findRegexState = rememberPersistedTextFieldState(
                    externalValue = regex.findRegex,
                    resetKey = regex.id,
                    onCommit = { findRegex -> onRegexFindRegexChange(regex.id, findRegex) },
                )
                val replaceStringState = rememberPersistedTextFieldState(
                    externalValue = regex.replaceString,
                    resetKey = regex.id,
                    onCommit = { replaceString -> onRegexReplaceStringChange(regex.id, replaceString) },
                )

                OutlinedTextField(
                    state = nameState,
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    label = { Text(stringResource(R.string.assistant_page_regex_name)) }
                )

                OutlinedTextField(
                    state = findRegexState,
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
                    label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                    placeholder = { Text("e.g., \\b\\w+@\\w+\\.\\w+\\b") },
                )

                OutlinedTextField(
                    state = replaceStringState,
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
                    label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                    placeholder = { Text("e.g., [EMAIL]") },
                )

                // 配置体检（只在编辑期提示，不改运行时行为）：这三类问题的共同点是
                // 「看起来配好了、实际要么不生效、要么越用越长」，光看正则文本看不出来。
                RegexDiagnosisWarnings(regex)

                Column {
                    Text(
                        text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AssistantAffectScope.entries.forEach { scope ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Checkbox(
                                    checked = scope in regex.affectingScope,
                                    onCheckedChange = { checked ->
                                        val newScopes = if (checked) {
                                            regex.affectingScope + scope
                                        } else {
                                            regex.affectingScope - scope
                                        }
                                        onUpdate(
                                            assistant.copy(
                                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                                    if (i == index) {
                                                        reg.copy(affectingScope = newScopes)
                                                    } else {
                                                        reg
                                                    }
                                                }
                                            )
                                        )
                                    }
                                )
                                Text(
                                    text = scope.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = regex.visualOnly,
                        onCheckedChange = { visualOnly ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(visualOnly = visualOnly)
                                        } else {
                                            reg
                                        }
                                    }
                                )
                            )
                        }
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_regex_visual_only),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                TextButton(
                    onClick = {
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.filterIndexed { i, _ ->
                                    i != index
                                }
                            )
                        )
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(HugeIcons.Delete01, null)
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

/**
 * 一条正则规则的**编辑期提示**（见 [diagnoseRegexRule]）。
 *
 * 为什么只提示、不改行为：这三类问题里只有「会累积」那条真的会改到消息内容，而修它
 * （每步从干净基线重算而不是在已替换的文本上再替换）属于运行时改动，风险与收益都不该
 * 由一个 UI 提示顺手带上——这里先把「你配的这条有问题」讲清楚。
 *
 * 只有展开编辑时才组合（调用点在 `if (expanded)` 内）；没有问题时整块不占位。
 */
@Composable
private fun RegexDiagnosisWarnings(regex: AssistantRegex) {
    val issues = diagnoseRegexRule(regex)
    if (issues.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 固定顺序展示：同一份配置每次都报同样的顺序，便于对照截图/复述
        listOf(
            RegexDiagnosisIssue.INVALID_PATTERN to R.string.assistant_page_regex_issue_invalid,
            RegexDiagnosisIssue.NO_SCOPE to R.string.assistant_page_regex_issue_no_scope,
            RegexDiagnosisIssue.AMPLIFIES_OUTPUT to R.string.assistant_page_regex_issue_amplifies,
        ).forEach { (issue, textRes) ->
            if (issue !in issues) return@forEach
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Alert02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(textRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
