package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.Tool
import app.androidrik.ai.core.merge
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.handleMessageChunk
import app.androidrik.ai.ui.limitContext
import app.androidrik.rikkahub.data.ai.prompts.isCompactionCheckpointPreamble
import app.androidrik.rikkahub.data.ai.prompts.partitionCheckpointMessages
import app.androidrik.rikkahub.data.ai.transformers.InputMessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.MessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.OutputMessageTransformer
import app.androidrik.rikkahub.data.files.FileFolders
import java.io.File
import app.androidrik.rikkahub.data.ai.transformers.onGenerationFinish
import app.androidrik.rikkahub.data.ai.transformers.transforms
import app.androidrik.rikkahub.data.ai.transformers.visualTransforms
import app.androidrik.rikkahub.data.ai.tools.buildMemoryTools
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
/** 翻译输入最大字符数：超长翻译文本曾 API 413 */
private const val MAX_TRANSLATE_CHARS = 200_000

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
) {
    /**
     * @param messages 会话**全量**消息（与 messageNodes 对齐），既作回写基线也与 [windowMessages] 一致
     *   时构成发送内容
     * @param windowMessages 发送窗口（压缩时 = 检查点合成消息 + 边界后原文）。默认与 [messages] 相同；
     *   仅用于构建发给 provider 的上下文——**不可**作为回写基线（否则合成消息会被持久化，且与
     *   messageNodes 索引错位）
     */
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        windowMessages: List<UIMessage>? = null,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // 跨 provider 轻量降级（fallback 模型链）：主模型整体不可用时切备用模型重跑整轮。
        // 仅限"零输出失败"（重试耗尽后仍无可见内容）——半截输出（GenerationPartialFailureException）
        // 保留草稿不 fallback（#1812 约束，避免丢已计费内容）；取消不 fallback。
        // 备用模型优先级：助手设置 > 全局默认（与主模型 chatModelId 的两级模式一致）
        // 优先级：助手级 > 全局级。助手级 ID 悬空（模型被删/重建导致 ID 失效）时
        // 回退到全局级——否则悬空的助手引用会遮蔽有效的全局配置（用户实测踩坑）。
        val assistantFallbackModel = assistant.fallbackChatModelId?.let { settings.findModelById(it) }
        if (assistant.fallbackChatModelId != null && assistantFallbackModel == null) {
            Log.w(TAG, "assistant fallback model ${assistant.fallbackChatModelId} not found, trying global fallback")
        }
        val fallbackModel = assistantFallbackModel
            ?: settings.fallbackChatModelId?.let { settings.findModelById(it) }
        if (fallbackModel == null &&
            (assistant.fallbackChatModelId != null || settings.fallbackChatModelId != null)
        ) {
            // 助手级与全局级都解析失败：fallback 不生效，必须可见
            Log.w(TAG, "fallback model not found in providers, fallback disabled")
        }
        val fallbackProvider = fallbackModel?.findProvider(settings.providers)
        val fallbackProviderImpl = fallbackProvider?.let { providerManager.getProviderByType(it) }

        suspend fun runLoop(
            targetModel: Model,
            targetProvider: ProviderSetting,
            targetProviderImpl: Provider<ProviderSetting>,
        ) {
            var messages: List<UIMessage> = messages

            for (stepIndex in 0 until maxSteps) {
                Log.i(TAG, "streamText: start step #$stepIndex (${targetModel.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        },
                        // 分档：create 放行；edit/delete 在开启审批时需用户确认（防未经许可的改/删）
                        needsApproval = { action ->
                            assistant.memoryModifyApproval && action != "create"
                        },
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    windowMessages = windowMessages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = targetModel,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = targetModel,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = targetModel,
                    providerImpl = targetProviderImpl,
                    provider = targetProvider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    // 有 fallback 时压缩主模型重试预算（最多 2 次），快速切备用模型——
                    // 否则连接类故障会先等完整个延迟序列（默认可达 100s+）才降级
                    retryBudgetOverride = if (fallbackModel != null) 2 else null,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = targetModel,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = targetModel,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = try {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            } catch (t: Throwable) {
                                // Throwable：深嵌套工具输入抛 StackOverflowError（Error），runCatching 捕不到
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${t.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = targetModel,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }
        }

        // 主模型整轮失败（重试耗尽、非半截、非取消）时切 fallback 模型重跑整轮。
        // 半截输出（GenerationPartialFailureException）保留草稿不 fallback——#1812 约束。
        try {
            runLoop(model, provider, providerImpl)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (e is GenerationPartialFailureException) throw e
            if (fallbackModel != null && fallbackProviderImpl != null) {
                Log.w(TAG, "primary model failed, fallback to ${fallbackModel.id}: ${e.message}")
                runLoop(fallbackModel, fallbackProvider!!, fallbackProviderImpl)
            } else {
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 生成失败自动重试。
     *
     * 配置解析优先级：per-provider [ProviderSetting.retryOption]（非 null 时完全覆盖）→
     * 全局 [Settings.retrySetting]。未启用或间隔序列为空时直接执行，零开销。
     *
     * 语义：delaysSeconds 长度 = 重试次数（不含首次请求），值 = 每次重试前的等待秒数。
     * 仅对「可重试错误」（网络异常/限流/服务端错误）重试；用户取消（CancellationException）
     * 与不可重试错误（鉴权失败、参数错误等）立即上抛。重试等待状态通过
     * [processingStatus] 反馈到 UI。
     */
    private suspend fun <T> withGenerationRetry(
        providerSetting: ProviderSetting,
        settings: Settings,
        processingStatus: MutableStateFlow<String?>?,
        maxRetries: Int? = null,
        action: suspend (ProviderSetting) -> T,
    ): T {
        val plan = providerSetting.retryOption ?: settings.retrySetting
        if (!plan.enabled || plan.delaysSeconds.isEmpty()) return action(providerSetting)
        // 重试预算：有 fallback 模型时压缩（快速失败切备用，避免长延迟重试拖慢恢复）
        val retryBudget = maxRetries?.coerceAtMost(plan.delaysSeconds.size) ?: plan.delaysSeconds.size

        var currentSetting = providerSetting
        var retryIndex = -1 // 已执行的重试序号；首次请求前为 -1
        while (true) {
            try {
                return action(currentSetting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenerationPartialFailureException) {
                // #1812：半截输出失败不进重试循环——已流式内容有计费价值，
                // 清空重发必然丢失；直接上抛由 ChatService 保留为草稿
                throw e
            } catch (e: Throwable) {
                val next = retryIndex + 1
                // 鉴权失败（401/403）或账户限流（429，多账户场景换 key 可避开）且还有其他 key：
                // 立即换 key 重试，不等延迟。OrderKeyRoulette 固定取首个 key，故轮转 =
                // 从瞬态副本去掉刚失败的首个 key（不落盘），下次请求自然用下一个；
                // 每次换 key 消耗一个 key，耗尽后 hasMultipleEnabledKeys=false → 走正常路径上抛，
                // 不会死循环。安全保证：#1812 分流——流式半截 429 已抛
                // GenerationPartialFailureException 被上方 rethrow（保留草稿不换 key），
                // 能走到这里的一定是零输出，换 key 无计费损失。
                if (isKeySwitchingError(e) && hasMultipleEnabledKeys(currentSetting)) {
                    val rotated = rotateProviderKeys(currentSetting)
                    if (rotated == null) throw e // 理论不可达（hasMultipleEnabledKeys 已保证 ≥2 个）
                    retryIndex = next
                    currentSetting = rotated
                    Log.w(
                        TAG,
                        "key error, switching key (${splitEffectiveKeys(rotated.effectiveApiKeys).size} key(s) left) " +
                            "and retrying: ${e.message}"
                    )
                    continue
                }
                if (next >= retryBudget || !isRetryableGenerationError(e)) throw e
                retryIndex = next
                val waitSec = plan.delaysSeconds[retryIndex].coerceAtLeast(0)
                Log.w(
                    TAG,
                    "generation failed, retry ${retryIndex + 1}/$retryBudget in ${waitSec}s: ${e.message}"
                )
                if (processingStatus != null) {
                    processingStatus.value = context.getString(
                        R.string.generation_retry_waiting,
                        retryIndex + 1,
                        retryBudget,
                        waitSec,
                    )
                }
                try {
                    delay(waitSec * 1000L)
                } finally {
                    if (processingStatus != null) processingStatus.value = null
                }
            }
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        /** 发送窗口（含检查点合成消息）；null = 与 messages 相同。仅用于构建 provider 上下文 */
        windowMessages: List<UIMessage>? = null,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        retryBudgetOverride: Int? = null,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                // TikZ 渲染提示（启用时引导模型以闭合 $$...$$ 块输出图表，触发 MathBlock 渲染）。
                // 注意：必须是原始字符串——普通字符串里 $$ 会被 Kotlin 转义成单个 $，模型将收到错误指令。
                // 强调「闭合」：只写开头 $$ 漏掉结尾 $$ 的块，客户端不会识别为图表（显示为源码）。
                if (settings.displaySetting.enableDiagramRendering) {
                    appendLine()
                    append(
                        """For TikZ diagrams, wrap the ENTIRE environment in a CLOSED $$...$$ block: open with $$ immediately before \begin{tikzpicture} (or \begin{tikzcd} or \begin{axis}), and close with $$ immediately after the matching \end{...}. Never omit the closing $$, and never put anything between the closing \end{...} and the closing $$. Example: $$\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}$$. Full TikZ/pgf is supported (positioning, calc, pgfplots, tikz-cd, all standard libraries) with CJK text — do not simplify or avoid advanced syntax. Diagrams render as inline vector graphics. Colors: use color names (blue, red, gray) optionally with opacity (blue!30); do NOT use #hex colors inside options like fill=#4A90D9 — the # is a TeX parameter token and breaks pgf option parsing (renders as source) PGF Math trigonometry: radians MUST use sin(deg(x)) or sin(x*pi/180) — sin(x rad) and sin(x r) are NOT supported and abort rendering."""
                    )
                }
                // 数学公式提示（启用 LaTeX 渲染时）：引导模型用 \(...\) / $...$ 输出公式并触发 jlatexmath。
                // 注意：定界符内不能有空格（$ x^2 $ 不会被识别为公式）；括号形式 \(x^2\) 更稳妥。
                if (settings.displaySetting.enableLatexRendering) {
                    appendLine()
                    append(
                        """For math formulas, wrap them in \( ... \) (preferred) or $...$ with NO whitespace immediately after the opening delimiter and before the closing delimiter, e.g. \(x^2 + y^2 = r^2\) or $\frac{a}{b}$ or $\sqrt{x}$. They render as inline math symbols."""
                    )
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            // 发给 provider 的上下文取自「发送窗口」（compression 时为 检查点合成消息 + 边界后原文）；
            // 检查点固定在窗口最前且不参与 limitContext 截断（limitContext 保留尾部会截掉开头的检查点）。
            // 注意：回写基线仍是全量 messages（下方 `var messages`），合成消息不会被持久化。
            // design §4.2 与 limitContext 的两层分工。
            val (checkpointMessages, restMessages) =
                (windowMessages ?: messages).partitionCheckpointMessages()
            addAll(checkpointMessages)
            addAll(restMessages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            withGenerationRetry(
                providerSetting = provider,
                settings = settings,
                processingStatus = processingStatus,
                maxRetries = retryBudgetOverride,
            ) { provider ->
                // 快照：流中途失败时回滚已输出的部分内容，重试从干净状态重新生成
                val snapshot = messages
                try {
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params
                    ).collect {
                        messages = messages.handleMessageChunk(
                            chunk = it,
                            model = model,
                            providerName = provider.name,
                        )
                        it.usage?.let { usage ->
                            messages = messages.mapIndexed { index, message ->
                                if (index == messages.lastIndex) {
                                    message.copy(usage = message.usage.merge(usage))
                                } else {
                                    message
                                }
                            }
                        }
                        onUpdateMessages(messages)
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    val plan = provider.retryOption ?: settings.retrySetting
                    // #1812 分流：已流式输出可见内容后断开 → 保留为草稿（不再回滚清空）；
                    // 阈值可配（partialOutputMinChars），防呆下限 1（避免误配 0/负数把零输出也当半截）
                    if (plan.keepPartialOnFailure && !plan.retryAfterPartial &&
                        isPartialOutput(messages, plan.partialOutputMinChars.coerceAtLeast(1))
                    ) {
                        throw GenerationPartialFailureException(e, messages)
                    }
                    messages = snapshot
                    onUpdateMessages(messages)
                    throw e
                }
            }
        } else {
            withGenerationRetry(
                providerSetting = provider,
                settings = settings,
                processingStatus = processingStatus,
                maxRetries = retryBudgetOverride,
            ) { provider ->
                val snapshot = messages
                try {
                    val chunk = providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                    messages = messages.handleMessageChunk(
                        chunk = chunk,
                        model = model,
                        providerName = provider.name,
                    )
                    chunk.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(
                                    usage = message.usage.merge(usage)
                                )
                            } else {
                                message
                            }
                        }
                    }
                    onUpdateMessages(messages)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    messages = snapshot
                    onUpdateMessages(messages)
                    throw e
                }
            }
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        // [FIX] toolCallId 由模型提供（外部可控），直接拼文件名可含 "/" 或 "../" →
        // 逃逸 tool_outputs 目录写入 app 私有目录其他位置（移植自 rikkahub-turbo a9449cc）。
        val safeFileName = toolCallId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "$safeFileName.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText.take(MAX_TRANSLATE_CHARS),
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            withGenerationRetry(provider, settings, null) { provider ->
                val snapshot = messages
                try {
                    providerHandler.streamText(
                        providerSetting = provider,
                        messages = messages,
                        params = TextGenerationParams(
                            model = model,
                            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        ),
                    ).collect { chunk ->
                        messages = messages.handleMessageChunk(chunk)
                        translatedText = messages.lastOrNull()?.toText() ?: ""

                        if (translatedText.isNotBlank()) {
                            onStreamUpdate?.invoke(translatedText)
                            emit(translatedText)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    messages = snapshot
                    throw e
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = withGenerationRetry(provider, settings, null) { provider ->
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                        topP = 0.95f,
                        customBody = listOf(
                            CustomBody(
                                key = "translation_options",
                                value = buildJsonObject {
                                    put("source_lang", JsonPrimitive("auto"))
                                    put(
                                        "target_lang",
                                        JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                    )
                                }
                            )
                        )
                    ),
                )
            }
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * #1812：半截输出失败信号——以异常为载体把「已流式合并的半截消息」交给上层落草稿。
 *
 * [withGenerationRetry] 识别该类型直接上抛（不重试）；ChatService catch 后保留候选草稿。
 */
class GenerationPartialFailureException(
    cause: Throwable,
    val partialMessages: List<UIMessage>,
) : Exception(cause)

/** 半截输出判定默认阈值：过滤 reasoning 后最后一条 assistant 消息可见 Text ≥ 该值视为「已产生有效输出」。 */
private const val PARTIAL_OUTPUT_THRESHOLD_CHAR = 50

/** 是否达到「半截输出」判定线：仅看最后一条 assistant 消息的可见 Text（reasoning 不计入）。 */
internal fun isPartialOutput(
    messages: List<UIMessage>,
    minChars: Int = PARTIAL_OUTPUT_THRESHOLD_CHAR,
): Boolean {
    val last = messages.lastOrNull() ?: return false
    if (last.role != MessageRole.ASSISTANT) return false
    val visibleChars = last.parts.sumOf { part ->
        if (part is UIMessagePart.Text) part.text.length else 0
    }
    return visibleChars >= minChars
}

/**
 * 判断生成错误是否值得自动重试：
 * - 网络层异常（超时/DNS/连接失败/TLS 等 IOException 家族）→ 重试
 * - 响应解析失败（中转站返回 HTML 错误页等非 JSON 内容）→ 重试
 * - HTTP 408/425/429/5xx → 重试；401/403（鉴权）、400（参数）、404（模型不存在）→ 不重试
 *
 * provider 层抛的是字符串异常（如 "Failed to get response: 429 ..."），状态码从 message 提取。
 * **内容风控拦截不重试**：同样内容重发同样被拦（见 [CONTENT_BLOCKED_MARKERS]）。
 */
internal fun isRetryableGenerationError(t: Throwable): Boolean {
    // 内容风控/违禁词：确定性失败，须用户改内容——优先于网络/状态码判定（重发同样被拦）
    if (t.matchesAnyMarker(CONTENT_BLOCKED_MARKERS)) return false
    if (t is java.io.IOException) return true
    if (t is kotlinx.serialization.SerializationException) return true
    // 额度耗尽是确定性失败：余额不会自恢复，等待无意义——快速失败走换 key / fallback
    if (t.matchesAnyMarker(QUOTA_EXHAUSTED_MARKERS)) return false
    // 限流：等窗口重置可解（无状态码的 SSE 流内限流错误在此兜底）
    if (t.matchesAnyMarker(RATE_LIMITED_MARKERS)) return true
    val code = findHttpStatusCode(t.message) ?: return false
    return code == 408 || code == 425 || code == 429 || code in 500..599
}

/**
 * 换 key 可解决的错误：401/403（鉴权/凭证失效）+ 429（账户级限流，多账户场景换 key 可避开），
 * 以及无状态码的 SSE 流内错误文本兜底（中转站 200 + error 事件）——额度耗尽/限流都是账户级问题。
 * 到此处的一定是零输出（流式半截 429 已被 #1812 分流拦截），换 key 无计费损失。
 *
 * **例外（内容风控拦截）**：网关按内容判定违禁并返回 403 时，问题在**请求内容**而非凭证——
 * 所有 key 都会撞同一道防火墙，换 key 必然全败且把额度/时间白耗在轮换上。故先行排除（见
 * [CONTENT_BLOCKED_MARKERS]）。这类错误也不重试（同样内容同样被拦）。
 */
internal fun isKeySwitchingError(t: Throwable): Boolean {
    if (t.matchesAnyMarker(CONTENT_BLOCKED_MARKERS)) return false
    val code = findHttpStatusCode(t.message)
    if (code == 401 || code == 403 || code == 429) return true
    return t.matchesAnyMarker(QUOTA_EXHAUSTED_MARKERS) || t.matchesAnyMarker(RATE_LIMITED_MARKERS)
}

/**
 * 内容风控/违禁词拦截特征：网关按**内容**拒绝，与凭证无关。
 *
 * 实测样例（中转站/网关 403）：「触发网站风控违禁词，无法调用模型：内容命中网关内容防火墙规则
 * [关键词：...]，已被拦截。请修改内容后重试。」——**换 key 无用**（同一防火墙）、**重试无用**
 * （同样内容同样被拦），只能由用户改内容。若不加此判据，会被 403 规则误判为凭证失效而轮换所有 key。
 *
 * 注意：网关多按**子串**匹配（如 `sb`、`sm`、`anal` 会命中 `val sb = StringBuilder()`、`analyze`
 * 等普通标识符），故用户侧报错常显「莫名其妙」；这是网关行为，客户端无法规避，只能如实归类。
 */
private val CONTENT_BLOCKED_MARKERS = listOf(
    // 中文网关/中转站（"风控" 是内容风控惯用词）
    "风控", "违禁词", "内容防火墙", "已被拦截", "命中网关", "请修改内容后重试",
    // 国际厂商内容策略
    "content policy", "content_policy_violation", "content filter", "content moderation",
    "moderation_blocked", "safety system", "flagged by", "responsible ai",
)

/** 账户额度耗尽特征：等待无用（余额不自恢复），换 key（换账户）或 fallback 模型可解 */
private val QUOTA_EXHAUSTED_MARKERS = listOf(
    "quota is not enough",       // new-api 系中转站（实测复制样例）
    "insufficient quota",        // OpenAI 官方系
    "insufficient_quota",
    "exceeded your current quota",
    "quota exceeded",
    "余额不足", "额度不足",
)

/** 限流特征（TPM/RPM/速率）：等窗口重置或换 key（换账户）可解；锚定写法防 "tpm-mini" 之类误伤 */
private val RATE_LIMITED_MARKERS = listOf(
    "rate limit", "rate_limit", "ratelimit",
    "too many requests",
    "tpm/rpm", "exceeds tpm", "exceeds rpm", "tpm limit", "rpm limit",
    "请求过于频繁", "触发限流",
)

private fun Throwable.matchesAnyMarker(markers: List<String>): Boolean {
    val message = this.message?.lowercase() ?: return false
    return markers.any { it in message }
}

/** 该提供商是否配置了多个可用 key（enabled 过滤后） */
internal fun hasMultipleEnabledKeys(providerSetting: ProviderSetting): Boolean {
    val keys = when (providerSetting) {
        is ProviderSetting.OpenAI -> providerSetting.apiKeys
        is ProviderSetting.Google -> providerSetting.apiKeys
        is ProviderSetting.Claude -> providerSetting.apiKeys
    }
    return keys.count { it.enabled && it.key.isNotBlank() } > 1
}

/**
 * 换 key 重试的轮转：去掉首个生效 key（OrderKeyRoulette 固定取首个 = 刚失败的那个），
 * 剩余 key 写回瞬态副本（apiKeys + 旧 apiKey 字段同时覆盖，兼容旧字符串多 key）。
 * 副本仅用于本次重试循环内传给 provider，不落盘。只剩 ≤1 个 key 时返回 null（无可切换）。
 */
internal fun rotateProviderKeys(setting: ProviderSetting): ProviderSetting? {
    val keys = splitEffectiveKeys(setting.effectiveApiKeys)
    if (keys.size <= 1) return null
    val remaining = keys.drop(1)
    val entries = remaining.map { ApiKeyEntry(it) }
    val legacy = remaining.joinToString("\n")
    return when (setting) {
        is ProviderSetting.OpenAI -> setting.copy(apiKeys = entries, apiKey = legacy)
        is ProviderSetting.Google -> setting.copy(apiKeys = entries, apiKey = legacy)
        is ProviderSetting.Claude -> setting.copy(apiKeys = entries, apiKey = legacy)
    }
}

/** 拆分 effectiveApiKeys 字符串（与 KeyRoulette.splitKey 同规则：空白/逗号分隔 + 去重） */
internal fun splitEffectiveKeys(keys: String): List<String> =
    keys.split("[\\s,]+".toRegex()).map { it.trim() }.filter { it.isNotBlank() }.distinct()

/** 从错误 message 提取 HTTP 状态码：优先 "code/status: NNN" 形态，回退到首个独立的 3 位数字。 */
private fun findHttpStatusCode(message: String?): Int? {
    if (message == null) return null
    val prefixed = Regex("""(?:code|status|HTTP)[\s:=]*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (prefixed != null) return prefixed
    // 回退：形如 ": 429"、">502"、"#401" 的独立三位数（覆盖中转站 HTML 错误页正文与搜索服务 "#${code}" 形态）
    return Regex("""[\s:>(#](\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()
}
