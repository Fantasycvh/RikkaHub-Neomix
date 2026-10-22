package app.androidrik.plugin.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * RikkaHub 插件基础接口(借鉴 BiliPai `Plugin`,但收敛为单一注册表)。
 *
 * 与 BiliPai 五套管理并存不同,本 fork 只保留**一个**注册表([app.androidrik.plugin.manager.PluginManager])
 * 和**一种**状态存储:所有插件形态都收敛到 [RikkaPlugin] 元数据 + 可选的能力接口。
 *
 * 设计约束:
 * - 本接口不得暴露 Android `Context`、账号凭证、宿主仓库或内部单例;
 *   插件需要的能力通过宿主侧窄接口([app.androidrik.rikkahub.data.plugin] 中的契约)注入。
 * - 插件通过 [capabilities] 声明能力,宿主在**调用点**校验是否获授权。
 * - [SettingsContent] 由插件自带 Compose 内容,宿主只提供详情页容器——
 *   这样无需改动设置页路由体系(见设计文档 §2.4)。
 *
 * 生命周期:[onEnable] 在插件启用时调用,[onDisable] 在禁用时调用;
 * 两者都可能被重复调用,实现需幂等。
 */
interface RikkaPlugin {
    /** 全局唯一标识,建议反向域名或 `builtin.` 前缀,如 `builtin.whitespace_trim`。 */
    val id: String

    /** 插件中心展示名称。 */
    val displayName: String

    /** 一句话描述。 */
    val description: String

    /** 语义化版本。 */
    val version: String

    val author: String
        get() = "Unknown"

    /** 插件中心图标(可选)。 */
    val icon: ImageVector?
        get() = null

    /** 是否暂不可用(功能未完成/被禁用),为 true 时插件中心展示 [unavailableReason]。 */
    val unavailable: Boolean
        get() = false

    val unavailableReason: String
        get() = "功能开发中"

    /** 首次注册时的默认启用状态;已有持久化状态时以持久化状态为准。 */
    val enabledByDefault: Boolean
        get() = true

    /** 声明能力集合;只声明当前版本实际使用的能力。 */
    val capabilities: Set<PluginCapability>
        get() = emptySet()

    /** 从插件属性派生的能力清单;通常无需覆盖。 */
    fun manifest(): PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = displayName,
        version = version,
        apiVersion = PLUGIN_API_VERSION,
        entryClassName = this::class.java.name,
        capabilities = capabilities,
    )

    suspend fun onEnable() {}

    suspend fun onDisable() {}

    /** 插件自有配置界面;默认无配置项。 */
    @Composable
    fun SettingsContent() {
    }
}
