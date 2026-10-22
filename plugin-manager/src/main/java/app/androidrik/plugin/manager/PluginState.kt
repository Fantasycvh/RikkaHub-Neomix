package app.androidrik.plugin.manager

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin
import kotlinx.serialization.Serializable

/**
 * 单个插件的持久化状态(单 key JSON 存储,见 [PluginStateStore])。
 *
 * [approvedCapabilities] 为 null 表示"用户尚未做出决定":源码级内置插件此时
 * 默认信任其 [RikkaPlugin.capabilities] 声明(见 [PluginManager] 的授权解析);
 * 一旦用户撤销过某个能力,就落成显式集合,之后按 `声明 ∩ 已批准` 计算。
 */
@Serializable
data class PluginState(
    val enabled: Boolean = true,
    val approvedCapabilities: Set<PluginCapability>? = null,
)

/**
 * 插件状态持久化通道。
 *
 * 放在本模块而不直接依赖 DataStore:宿主(app)用 DataStore 实现,
 * 单测可用内存实现驱动 [PluginManager] 的纯逻辑。
 */
interface PluginStateStore {
    suspend fun read(): Map<String, PluginState>

    suspend fun write(states: Map<String, PluginState>)
}
