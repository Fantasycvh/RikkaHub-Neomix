package app.androidrik.rikkahub.data.plugin

import app.androidrik.ai.core.Tool
import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.rikkahub.data.ai.transformers.InputMessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.OutputMessageTransformer
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRenderer

/**
 * 宿主侧能力契约。
 *
 * 插件 API 模块(`plugin-api`)刻意不认识宿主内部类型,所以只有插件**同时**实现
 * [RikkaPlugin] 与下面某个窄接口,才会被对应装配点拾取:
 *
 * - [PluginInputTransformer] → ChatService 输入转换管线(`PROMPT_TRANSFORM`)
 * - [PluginOutputTransformer] → ChatService 输出转换管线(`OUTPUT_TRANSFORM`)
 * - [PluginToolProvider] → ChatToolFactory 工具装配点(`TOOL_REGISTER`)
 * - [PluginToolUIRendererProvider] → ToolUIRegistry(启用时注册、禁用时注销)
 *
 * 能力校验在**调用点**完成(见 ChatService / ChatToolFactory),不依赖安装期一次性检查。
 */
interface PluginInputTransformer : RikkaPlugin, InputMessageTransformer

interface PluginOutputTransformer : RikkaPlugin, OutputMessageTransformer

interface PluginToolProvider : RikkaPlugin {
    /** 本插件提供给模型的工具;调用方已确认 [app.androidrik.plugin.api.PluginCapability.TOOL_REGISTER]。 */
    fun tools(): List<Tool>
}

interface PluginToolUIRendererProvider : RikkaPlugin {
    /** 本插件的工具 UI 渲染器;由宿主观察者按启用状态注册/注销。 */
    val toolRenderers: List<ToolUIRenderer>
}
