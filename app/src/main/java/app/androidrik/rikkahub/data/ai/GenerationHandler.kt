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
import app.androidrik.ai.provider.CustomBody
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
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
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

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

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
                        }
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
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
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
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
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
        action: suspend () -> T,
    ): T {
        val plan = providerSetting.retryOption ?: settings.retrySetting
        if (!plan.enabled || plan.delaysSeconds.isEmpty()) return action()

        var retryIndex = -1 // 已执行的重试序号；首次请求前为 -1
        while (true) {
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenerationPartialFailureException) {
                // #1812：半截输出失败不进重试循环——已流式内容有计费价值，
                // 清空重发必然丢失；直接上抛由 ChatService 保留为草稿
                throw e
            } catch (e: Throwable) {
                val next = retryIndex + 1
                if (next >= plan.delaysSeconds.size || !isRetryableGenerationError(e)) throw e
                retryIndex = next
                val waitSec = plan.delaysSeconds[retryIndex].coerceAtLeast(0)
                Log.w(
                    TAG,
                    "generation failed, retry ${retryIndex + 1}/${plan.delaysSeconds.size} in ${waitSec}s: ${e.message}"
                )
                if (processingStatus != null) {
                    processingStatus.value = context.getString(
                        R.string.generation_retry_waiting,
                        retryIndex + 1,
                        plan.delaysSeconds.size,
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
            addAll(messages.limitContext(assistant.contextMessageLimit))
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
            ) {
                // 快照：流中途失败时回滚已输出的部分内容，重试从干净状态重新生成
                val snapshot = messages
                try {
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params
                    ).collect {
                        messages = messages.handleMessageChunk(chunk = it, model = model)
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
            ) {
                val snapshot = messages
                try {
                    val chunk = providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                    messages = messages.handleMessageChunk(chunk = chunk, model = model)
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

            withGenerationRetry(provider, settings, null) {
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
            val chunk = withGenerationRetry(provider, settings, null) {
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
 */
internal fun isRetryableGenerationError(t: Throwable): Boolean {
    if (t is java.io.IOException) return true
    if (t is kotlinx.serialization.SerializationException) return true
    val code = findHttpStatusCode(t.message) ?: return false
    return code == 408 || code == 425 || code == 429 || code in 500..599
}

/** 从错误 message 提取 HTTP 状态码：优先 "code/status: NNN" 形态，回退到首个独立的 3 位数字。 */
private fun findHttpStatusCode(message: String?): Int? {
    if (message == null) return null
    val prefixed = Regex("""(?:code|status|HTTP)[\s:=]*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (prefixed != null) return prefixed
    // 回退：形如 ": 429"、">502" 的独立三位数（覆盖中转站 HTML 错误页正文）
    return Regex("""[\s:>(](\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()
}
