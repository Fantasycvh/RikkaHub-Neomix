package app.androidrik.rikkahub.data.plugin.js

import android.content.Context
import android.util.Log
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "JsPluginInstaller"

/**
 * JS 插件的安装器:URL 抓取 / 解析 / 物化注册 / 卸载 / 启动恢复。
 *
 * 与规则插件的安装器同构(见 `RulePluginInstaller`),差异只在来源是 `.js` 源码
 * 和它带来的能力(输入/输出/网络)。
 */
class JsPluginInstaller(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val store: JsPluginStore,
    private val manager: PluginManager,
    private val gateway: PluginHttpGateway,
    private val keyValueStore: PluginKeyValueStore,
) {
    /** 从 URL 抓取(必须 http/https);限制 512 KB,避免拉一个巨文件卡住安装。 */
    suspend fun fetch(url: String): Result<JsPluginDefinition> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = url.trim()
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "只支持 http/https 链接"
            }
            val request = Request.Builder().url(trimmed).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "下载失败: HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                require(body.isNotEmpty()) { "下载内容为空" }
                require(body.toByteArray(Charsets.UTF_8).size <= MAX_FETCH_BYTES) {
                    "JS 文件超过 ${MAX_FETCH_BYTES / 1024} KB 上限"
                }
                JsPluginManifest.parse(body).getOrThrow()
            }
        }
    }

    /** 解析源码文本(不落盘、不注册)。 */
    fun parseText(text: String): Result<JsPluginDefinition> = JsPluginManifest.parse(text)

    /**
     * 安装:先落盘、后注册(取消安全,同规则插件)。
     *
     * @param approvedCapabilities 用户批准的能力,必须覆盖变换/工具能力，网络和存储可选。
     *   网络能力被拒 → 插件仍可安装,但 `rikka.http()` 会立即失败(而不是偷偷放行)。
     */
    suspend fun install(
        definition: JsPluginDefinition,
        approvedCapabilities: Set<PluginCapability>,
    ): Result<Unit> = runCatching {
        val errors = definition.manifest.validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val declared = definition.manifest.capabilities
        // 变换/工具能力是插件的存在意义,必须批准;NETWORK/STORAGE 是可选能力,
        // 用户可以不勾选安装(此时 rikka.http 在运行期被调用点校验拒绝)。
        val mandatory = declared - setOf(PluginCapability.NETWORK, PluginCapability.STORAGE)
        require(mandatory.all { it in approvedCapabilities }) {
            "未批准必需能力: ${mandatory - approvedCapabilities}"
        }
        require(approvedCapabilities.all { it in declared }) {
            "批准了未声明的能力: ${approvedCapabilities - declared}"
        }
        require(manager.plugins.value.none { it.plugin.id == definition.id }) {
            "插件 ${definition.id} 已存在,请先删除再导入"
        }

        val current = store.read()
        store.save(current.filterNot { it.id == definition.id } + definition)

        manager.register(newPlugin(definition))
        manager.setApprovedCapabilities(definition.id, approvedCapabilities)
        Log.i(TAG, "installed js plugin: ${definition.id} capabilities=$declared")
    }

    /** 卸载:先删持久化定义,再移出注册表(取消安全)。 */
    suspend fun uninstall(pluginId: String): Result<Unit> = runCatching {
        val current = store.read()
        store.save(current.filterNot { it.id == pluginId })
        manager.unregister(pluginId)
        // 与授权状态一致保留同 id 的 KV，重装可恢复；插件可通过 store.clear 显式清除。
        Log.i(TAG, "uninstalled js plugin: $pluginId")
    }

    /** 启动时把已安装 JS 插件物化回注册表。 */
    suspend fun restoreAll(definitions: List<JsPluginDefinition>) {
        definitions.forEach { definition ->
            runCatching {
                if (manager.plugins.value.none { it.plugin.id == definition.id }) {
                    manager.register(newPlugin(definition))
                }
            }.onFailure { Log.w(TAG, "failed to restore js plugin ${definition.id}", it) }
        }
    }

    /**
     * 网络能力用**闭包**在调用点查询:撤销 NETWORK 后立即失效,
     * 不必等插件重启(设计教训 #1:「每个能力在调用点校验」)。
     */
    private fun newPlugin(definition: JsPluginDefinition) = JsPlugin(
        definition = definition,
        context = context,
        gateway = gateway,
        networkGranted = {
            manager.plugins.value
                .firstOrNull { it.plugin.id == definition.id }
                ?.let { it.enabled && it.hasCapability(PluginCapability.NETWORK) } == true
        },
        keyValueStore = keyValueStore,
        capabilityGranted = { capability ->
            manager.plugins.value.firstOrNull { it.plugin.id == definition.id }
                ?.let { it.enabled && it.hasCapability(capability) } == true
        },
    )

    private companion object {
        const val MAX_FETCH_BYTES = 512 * 1024
    }
}
