package app.androidrik.plugin.manager

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin

/**
 * 插件运行时视图:插件实例 + 当前启用状态 + 已授权能力。
 *
 * 调用方(消息管线/工具装配/插件中心)一律从 [PluginManager.plugins] 读取本类型,
 * 并用 [hasCapability] 做**调用点**能力校验。
 */
data class PluginInfo(
    val plugin: RikkaPlugin,
    val enabled: Boolean,
    val grants: Set<PluginCapability>,
) {
    fun hasCapability(capability: PluginCapability): Boolean = capability in grants
}

/**
 * 插件生命周期观察者。
 *
 * 宿主用它把"能力接口"挂到宿主实现上,例如:
 * - 启用时把插件自带的 [app.androidrik.rikkahub.ui.components.message.tools.ToolUIRenderer] 注册进 UI 注册表;
 * - 禁用时注销。
 *
 * 观察者只应做同步注册/注销;耗时工作交给插件自己的 [RikkaPlugin.onEnable]。
 */
interface PluginLifecycleObserver {
    suspend fun onPluginEnabled(plugin: RikkaPlugin) {}

    suspend fun onPluginDisabled(plugin: RikkaPlugin) {}
}
