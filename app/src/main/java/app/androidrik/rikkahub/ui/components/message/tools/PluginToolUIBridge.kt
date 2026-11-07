package app.androidrik.rikkahub.ui.components.message.tools

import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.plugin.manager.PluginLifecycleObserver
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginResources

/**
 * 插件工具 UI 扩展点（批次 11 从 `data/plugin` 迁来，消掉 data→ui 反向依赖）。
 *
 * 渲染器注册的本质是「UI 层向插件系统订阅」：插件 API 模块不认识宿主 UI，
 * 而注册目标 [ToolUIRegistry] 与契约类型 [ToolUIRenderer] 都住在 UI 侧——
 * 因此桥接 Observer 与扩展点接口一起放在 UI 包，data/plugin 不再 import ui。
 * 装配点在 Application（组合根），与 `installBuiltInPlugins` 并排各装各的。
 */

/**
 * 插件侧的「工具 UI 渲染器」扩展点：插件同时实现 [RikkaPlugin] 与本接口，
 * 启用后由宿主把渲染器注册进 [ToolUIRegistry]、禁用时注销。
 */
interface PluginToolUIRendererProvider : RikkaPlugin {
    /** 本插件的工具 UI 渲染器;由宿主观察者按启用状态注册/注销。 */
    val toolRenderers: List<ToolUIRenderer>
}

/**
 * 插件生命周期观察者:把插件自带的工具 UI 渲染器挂到 [ToolUIRegistry]。
 *
 * 之所以由宿主做(而不是插件在 onEnable 里直接注册):插件 API 不依赖宿主 UI 模块,
 * 注册时机与注销时机应由宿主统一保证——禁用后残留的渲染器会让"已关闭的插件"继续影响界面。
 */
private object PluginToolUIObserver : PluginLifecycleObserver {
    override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
        if (plugin is PluginToolUIRendererProvider) {
            plugin.toolRenderers.forEach { renderer ->
                resources.register { ToolUIRegistry.register(renderer) }
            }
        }
    }
}

/** 在 Application 启动时装桥（与 [app.androidrik.rikkahub.data.plugin.installBuiltInPlugins] 并排调用）。 */
fun installPluginToolUIBridge(manager: PluginManager) {
    manager.addObserver(PluginToolUIObserver)
}
