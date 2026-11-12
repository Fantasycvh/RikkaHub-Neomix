package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.core.reasoningPresets
import app.androidrik.ai.util.reasoningOverrideKeys
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.ai.provider.ModelType
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.ui.components.ai.ModelSelector
import app.androidrik.rikkahub.ui.components.ai.ReasoningButton
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.components.ui.TagsInput
import app.androidrik.rikkahub.ui.components.ui.UIAvatar
import app.androidrik.rikkahub.ui.hooks.heroAnimation
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import app.androidrik.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
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
        AssistantBasicContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            tags = tags,
            workspaces = workspaces,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<app.androidrik.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // imePadding 必须落在 verticalScroll **之外/之前**（视口侧）：键盘弹起时先收缩
            // 可滚动视口。反序（scroll → ime）会把 IME 高度当**滚动内容的底部 padding**，
            // 视口不收缩、内容高度随键盘动画逐帧变化，"滚动到光标"落点每帧都在动 ⇒ 整页抖动。
            // 范本与真机旧案：ScheduledTaskDetailPage / ScheduledTaskCreatePage。
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

                ) {
                // 本地权威编辑态 + 去抖单向写出（AGENTS「TextField Host Rules」第 4 条）。
                // 旧写法 `value = assistant.name` 是异步持久态投影：每键整份 Settings 落盘后
                // 旧值才回灌，Compose 会重置内部编辑态（组合被打断 / 光标回跳）——真机表现为
                // 「每敲一个字符抖一下、丢字」。2026-11-12 定案：120ms/字符注入 abcdefghij
                // 得到 bcdefghija；同设备用 rememberTextFieldState 的提示词框则完整。
                val nameState = rememberPersistedTextFieldState(
                    externalValue = assistant.name,
                    resetKey = assistant.id,
                    onCommit = vm::updateName,
                )
                OutlinedTextField(
                    state = nameState,
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_workspace_desc))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdate(
                            assistant.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace ->
                        workspace?.name ?: stringResource(R.string.workspace_no_binding)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
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
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_fallback_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_fallback_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.fallbackChatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    fallbackChatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_temperature_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = temperature
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var topPInput by remember(assistant.id) {
                        mutableStateOf(topP.toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdate(
                                    assistant.copy(
                                        topP = nextTopP
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_limit))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_desc),
                    )
                }
            ) {
                Slider(
                    value = assistant.contextMessageLimit.toFloat(),
                    onValueChange = { value ->
                        onUpdate(
                            assistant.copy(
                                contextMessageLimit = snapContextMessageLimit(value)
                            )
                        )
                    },
                    valueRange = 0f..512f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (assistant.contextMessageLimit > 0) stringResource(
                        R.string.assistant_page_context_message_limit_count,
                        assistant.contextMessageLimit
                    ) else stringResource(R.string.assistant_page_context_message_limit_unlimited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )

                if (assistant.contextMessageLimit > 0) {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                val reasoningModel = providers.flatMap { it.models }.find { it.id == assistant.chatModelId }
                val overrideKeys = remember(reasoningModel?.id, reasoningModel?.customBodies, assistant.customBodies) {
                    (assistant.customBodies + reasoningModel?.customBodies.orEmpty()).reasoningOverrideKeys()
                }
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    presets = reasoningModel?.let { reasoningPresets(it, it.findProvider(providers)) }.orEmpty(),
                    selectedPresetId = assistant.reasoningPresetId,
                    onSelectPreset = { preset ->
                        onUpdate(assistant.copy(
                            reasoningLevel = preset?.level ?: assistant.reasoningLevel,
                            reasoningPresetId = preset?.id,
                        ))
                    },
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level, reasoningPresetId = null))
                    },
                    overrideKeys = overrideKeys,
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                // 数值投影同属「写穿回灌」家族（附录 D1）：每键把 Int? 投影回 value，旧值回灌
                // 会把正在输入的 "4096" 截成 "4" 再逐位补回——同样表现为抖动/吞字。
                val maxTokensState = rememberPersistedTextFieldState(
                    externalValue = assistant.maxTokens?.toString() ?: "",
                    resetKey = assistant.id,
                    onCommit = { text ->
                        // 空串 = 不限制；非数字 / 非正的中间态留在输入框里让用户继续改，不落库
                        if (text.isBlank()) {
                            vm.updateMaxTokens(null)
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }?.let(vm::updateMaxTokens)
                        }
                    },
                )
                val typedTokens = maxTokensState.text.toString().toIntOrNull()?.takeIf { it > 0 }
                OutlinedTextField(
                    state = maxTokensState,
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        // 两分支都渲染 Text：高度恒定，避免逐键出现/消失引起的布局跳动
                        if (typedTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, typedTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_gradient_background))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_gradient_background_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useGradientBackground,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useGradientBackground = it
                                )
                            )
                        }
                    )
                }
            )

            if (!assistant.useGradientBackground) {
                HorizontalDivider()

                BackgroundPicker(
                    modifier = Modifier.padding(8.dp),
                    background = assistant.background,
                    backgroundOpacity = assistant.backgroundOpacity,
                    onUpdate = { background ->
                        onUpdate(
                            assistant.copy(
                                background = background
                            )
                        )
                    }
                )
            }

            if (!assistant.useGradientBackground && assistant.background != null) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_opacity_desc))
                    }
                ) {
                    Slider(
                        value = backgroundOpacity,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (backgroundOpacity * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * 上下文限制的最小有效值
 *
 * 低于此值时截断点几乎每轮都在移动, 提示词缓存命中率跌破 90%,
 * 且保留的上下文通常达不到可缓存的最小长度, 限制本身失去意义
 */
private const val MIN_CONTEXT_MESSAGE_LIMIT = 20

/**
 * 把滑块取值吸附到 0(不限制) 或不低于 [MIN_CONTEXT_MESSAGE_LIMIT] 的有效档位
 */
private fun snapContextMessageLimit(value: Float): Int {
    val raw = value.roundToInt()
    return when {
        raw < MIN_CONTEXT_MESSAGE_LIMIT / 2 -> 0
        raw < MIN_CONTEXT_MESSAGE_LIMIT -> MIN_CONTEXT_MESSAGE_LIMIT
        else -> raw
    }
}
