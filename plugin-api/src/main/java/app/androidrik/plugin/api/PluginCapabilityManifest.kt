package app.androidrik.plugin.api

/**
 * 插件 API 版本。
 *
 * 与 BiliPai 的 `apiVersion` 同义:插件声明自己按哪个版本的接口编写,
 * 宿主在加载/展示时可据此判断兼容性。破坏性改动时递增。
 */
const val PLUGIN_API_VERSION: Int = 1

/**
 * 插件能力清单(安装/展示/授权用)。
 *
 * 由 [RikkaPlugin.manifest] 从插件属性派生,也可在插件里显式实现以覆盖默认值。
 */
data class PluginCapabilityManifest(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val apiVersion: Int = PLUGIN_API_VERSION,
    val entryClassName: String,
    val capabilities: Set<PluginCapability> = emptySet(),
)
