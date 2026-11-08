package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.rikkahub.data.plugin.PluginInputTransformer
import app.androidrik.rikkahub.data.plugin.PluginOutputTransformer
import app.androidrik.rikkahub.data.plugin.PreviewSafePluginInputTransformer
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant

/** 阶段由宿主决定；插件只能进入 PLUGIN，协议配平始终由宿主追加。 */
internal enum class TransformerStage { CORE, TEMPLATE, WORKSPACE, PLUGIN, FINALIZER }

internal enum class TransformerUse { REQUEST, PREVIEW, WARMUP }

internal data class TransformerContribution<T : MessageTransformer>(
    val id: String,
    val stage: TransformerStage,
    val transformer: T,
    val allowWarmup: Boolean = true,
    val allowPreview: Boolean = false,
    val previewLabel: String = id,
)

/** 预览只报告对当前请求可能有影响的省略项，不把纯文本请求标成缺少 OCR。 */
data class TransformerOmission(val id: String, val label: String) {
    fun appliesTo(model: Model, assistant: Assistant, messages: List<UIMessage>): Boolean = when (id) {
        "builtin.ocr" -> Modality.IMAGE !in model.inputModalities && messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        "builtin.workspace_reminder" -> assistant.workspaceId != null
        else -> true
    }
}

/** 一次装配只读取一份插件状态；构建快照不执行任何转换器。 */
internal class TransformerChains(
    val inputs: List<TransformerContribution<InputMessageTransformer>>,
    val outputs: List<TransformerContribution<OutputMessageTransformer>>,
    val omittedInputs: List<TransformerOmission> = emptyList(),
) {
    val inputTransformers = inputs.map { it.transformer }
    val outputTransformers = outputs.map { it.transformer }
}

/**
 * 仓内装配契约，不对外暴露 TransformerContext，也不引入动态服务图。
 * 同阶段按声明顺序稳定装配；插件保留 PluginManager 注册顺序，避免按 id 排序改变旧行为。
 */
internal class TransformerChainRegistry(
    inputs: List<TransformerContribution<InputMessageTransformer>>,
    outputs: List<TransformerContribution<OutputMessageTransformer>> = defaultOutputContributions,
    // P1 临时宿主回滚开关：false 使用旧链的声明顺序；两条路径都只执行所选链一次。
    private val registeredAssembly: Boolean = true,
) {
    private val inputs = inputs.toList()
    private val outputs = outputs.toList()

    init {
        require(this.inputs.all { it.stage < TransformerStage.PLUGIN }) { "Reserved input stage" }
        require(this.outputs.all { it.stage == TransformerStage.CORE }) { "Reserved output stage" }
        validateIds(this.inputs)
        validateIds(this.outputs)
    }

    fun snapshot(plugins: List<PluginInfo>, use: TransformerUse = TransformerUse.REQUEST): TransformerChains {
        val pluginInputs = mutableListOf<TransformerContribution<InputMessageTransformer>>()
        val pluginOutputs = mutableListOf<TransformerContribution<OutputMessageTransformer>>()
        if (use != TransformerUse.WARMUP) {
            plugins.filter { it.enabled }.forEach { info ->
                val plugin = info.plugin
                if (plugin is PluginInputTransformer && info.hasCapability(PluginCapability.PROMPT_TRANSFORM)) {
                    pluginInputs += TransformerContribution(
                        "plugin:${plugin.id}:input", TransformerStage.PLUGIN, plugin, false,
                        allowPreview = plugin is PreviewSafePluginInputTransformer, previewLabel = plugin.displayName,
                    )
                }
                if (plugin is PluginOutputTransformer && info.hasCapability(PluginCapability.OUTPUT_TRANSFORM)) {
                    pluginOutputs += TransformerContribution("plugin:${plugin.id}:output", TransformerStage.PLUGIN, plugin, false)
                }
            }
        }
        val omitted = (inputs + pluginInputs).filter { use != TransformerUse.REQUEST && !it.allowPreview }
            .map { TransformerOmission(it.id, it.previewLabel) }
        val inputChain = ordered(inputs, use) + ordered(pluginInputs, use) + TransformerContribution<InputMessageTransformer>(
            "builtin.tool_result_balancing", TransformerStage.FINALIZER, ToolResultBalancingTransformer,
        )
        val outputChain = ordered(outputs, use) + ordered(pluginOutputs, use)
        validateIds(inputChain)
        validateIds(outputChain)
        return TransformerChains(inputChain, outputChain, omitted)
    }

    private fun <T : MessageTransformer> ordered(
        contributions: List<TransformerContribution<T>>,
        use: TransformerUse,
    ): List<TransformerContribution<T>> {
        val selected = contributions.filter {
            when (use) {
                TransformerUse.REQUEST -> true
                TransformerUse.PREVIEW -> it.allowPreview
                TransformerUse.WARMUP -> it.allowWarmup && it.allowPreview
            }
        }
        return if (registeredAssembly) selected.sortedBy { it.stage } else selected
    }

    private fun <T : MessageTransformer> validateIds(contributions: List<TransformerContribution<T>>) {
        require(contributions.all { it.id.isNotBlank() }) { "Blank transformer contribution id" }
        require(contributions.map { it.id }.distinct().size == contributions.size) { "Duplicate transformer contribution id" }
    }
}

private val defaultOutputContributions: List<TransformerContribution<OutputMessageTransformer>> by lazy {
    listOf(
        TransformerContribution("builtin.think_tag", TransformerStage.CORE, ThinkTagTransformer),
        TransformerContribution("builtin.base64_image", TransformerStage.CORE, Base64ImageToLocalFileTransformer),
        TransformerContribution("builtin.regex_output", TransformerStage.CORE, RegexOutputTransformer),
    )
}

/** 子代理继续只使用内置输出链，不隐式继承聊天插件。 */
internal val defaultOutputTransformers: List<OutputMessageTransformer> by lazy {
    defaultOutputContributions.map { it.transformer }
}

// 只在回滚窗口内保留；P1 验收后删除旧序装配分支和此开关。
private const val USE_REGISTERED_TRANSFORMER_ASSEMBLY = true

/** 新增/替换内置转换器只改此注册定义，ChatService 只消费装配结果。 */
internal fun defaultTransformerChainRegistry(
    template: InputMessageTransformer,
    workspaceReminder: InputMessageTransformer,
    registeredAssembly: Boolean = USE_REGISTERED_TRANSFORMER_ASSEMBLY,
): TransformerChainRegistry = TransformerChainRegistry(
    inputs = listOf(
        TransformerContribution("builtin.time_reminder", TransformerStage.CORE, TimeReminderTransformer, allowPreview = true),
        TransformerContribution("builtin.prompt_injection", TransformerStage.CORE, PromptInjectionTransformer, allowPreview = true),
        TransformerContribution("builtin.tavern_macro", TransformerStage.CORE, TavernMacroTransformer, allowPreview = true),
        TransformerContribution("builtin.placeholder", TransformerStage.CORE, PlaceholderTransformer, allowPreview = true),
        TransformerContribution("builtin.document_prompt", TransformerStage.CORE, DocumentAsPromptTransformer, allowPreview = true),
        TransformerContribution("builtin.ocr", TransformerStage.CORE, OcrTransformer, false, previewLabel = "图片文字识别（OCR）"),
        TransformerContribution("builtin.context_image_eviction", TransformerStage.CORE, ContextImageEvictionTransformer, allowPreview = true),
        TransformerContribution("builtin.quote", TransformerStage.CORE, QuoteTransformer, allowPreview = true),
        TransformerContribution("builtin.template", TransformerStage.TEMPLATE, template, allowPreview = true),
        TransformerContribution("builtin.workspace_reminder", TransformerStage.WORKSPACE, workspaceReminder, false, previewLabel = "工作区环境提醒"),
    ),
    registeredAssembly = registeredAssembly,
)
