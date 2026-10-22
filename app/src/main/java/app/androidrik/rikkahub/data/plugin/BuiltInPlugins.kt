package app.androidrik.rikkahub.data.plugin

import android.util.Log
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.plugin.manager.PluginLifecycleObserver
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.data.ai.transformers.TransformerContext
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRegistry

private const val TAG = "BuiltInPlugins"

/**
 * 内置插件清单(源码级原生插件,编译进 APK)。
 *
 * 与 BiliPai 不同,本 fork 一上来只保留**一种**注册形态;形态扩展(JSON 规则插件、
 * JS 沙箱)按设计文档的二期/三期推进。新增内置插件只需在本清单加一行。
 */
val builtInPlugins: List<RikkaPlugin> = listOf(
    WhitespaceTrimPlugin,
)

/**
 * 插件生命周期观察者:把插件自带的工具 UI 渲染器挂到 [ToolUIRegistry]。
 *
 * 之所以由宿主做(而不是插件在 onEnable 里直接注册):插件 API 不依赖宿主 UI 模块,
 * 注册时机与注销时机应由宿主统一保证——禁用后残留的渲染器会让"已关闭的插件"继续影响界面。
 */
private object PluginToolUIObserver : PluginLifecycleObserver {
    override suspend fun onPluginEnabled(plugin: RikkaPlugin) {
        if (plugin is PluginToolUIRendererProvider) {
            plugin.toolRenderers.forEach(ToolUIRegistry::register)
        }
    }

    override suspend fun onPluginDisabled(plugin: RikkaPlugin) {
        if (plugin is PluginToolUIRendererProvider) {
            plugin.toolRenderers.forEach { ToolUIRegistry.unregister(it.toolName) }
        }
    }
}

/**
 * 安装内置插件。
 *
 * 在 Application 启动时调用;内部先等待 [PluginManager.awaitReady],
 * 避免"注册早于持久化状态加载"导致启用状态被默认值覆盖。
 */
suspend fun installBuiltInPlugins(manager: PluginManager) {
    manager.addObserver(PluginToolUIObserver)
    manager.awaitReady()
    builtInPlugins.forEach { plugin ->
        runCatching { manager.register(plugin) }
            .onFailure { Log.e(TAG, "failed to register plugin ${plugin.id}", it) }
    }
}

/**
 * 示例内置插件:生成结束时去掉每行末尾空白。
 *
 * 默认**关闭**——它用于验证输出管线接入是否生效,而不是一个默认改变用户输出的功能。
 * 也是插件作者可参考的最小 [PluginOutputTransformer] 实现。
 */
object WhitespaceTrimPlugin : PluginOutputTransformer {
    override val id = "builtin.whitespace_trim"
    override val displayName = "输出空白裁剪"
    override val description = "生成结束时去掉模型输出每行末尾的空白字符"
    override val version = "1.0.0"
    override val author = "RikkaHub"
    override val enabledByDefault = false
    override val capabilities = setOf(PluginCapability.OUTPUT_TRANSFORM)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val (trimmed, removedChars) = trimTrailingWhitespace(messages)
        // 诊断日志:真机验证"输出管线确实调到插件"及"确实裁剪了字符"。
        // 只在生成结束时打印一次,量级可忽略。
        Log.d(
            TAG,
            "whitespace trim: messages=${messages.size} removed=$removedChars chars",
        )
        return trimmed
    }
}

/**
 * 去掉 assistant 文本每行末尾的空白,返回裁剪后的消息与移除字符数。
 *
 * 抽成纯函数:裁剪逻辑与"是否被管线调用"解耦,JVM 单测可直接覆盖
 * (见 `WhitespaceTrimPluginTest`)。
 */
internal fun trimTrailingWhitespace(messages: List<UIMessage>): Pair<List<UIMessage>, Int> {
    var removedChars = 0
    val trimmed = messages.map { message ->
        if (message.role != MessageRole.ASSISTANT) {
            message
        } else {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        val before = part.text
                        val after = before.lineSequence()
                            .joinToString("\n") { line -> line.trimEnd() }
                        removedChars += before.length - after.length
                        part.copy(text = after)
                    } else {
                        part
                    }
                },
            )
        }
    }
    return trimmed to removedChars
}
