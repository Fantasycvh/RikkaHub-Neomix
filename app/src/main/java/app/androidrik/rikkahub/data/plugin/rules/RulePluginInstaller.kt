package app.androidrik.rikkahub.data.plugin.rules

import android.util.Log
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "RulePluginInstaller"

/**
 * JSON 规则插件的安装器:URL 抓取 / 文本解析 / 物化注册 / 卸载。
 *
 * 安装期的能力确认由 UI 负责(见 PluginsPage 的导入流程),本类只接收
 * **用户已批准**的能力集合:批准集合必须覆盖 [JsonRuleDefinition.requiredCapability]。
 */
class RulePluginInstaller(
    private val okHttpClient: OkHttpClient,
    private val store: JsonRulePluginStore,
    private val manager: PluginManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 从 URL 抓取并解析(必须 http/https;超时由 OkHttp 配置决定)。 */
    suspend fun fetch(url: String): Result<JsonRuleDefinition> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = url.trim()
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "只支持 http/https 链接"
            }
            val request = Request.Builder().url(trimmed).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "下载失败: HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                parseText(body).getOrThrow()
            }
        }
    }

    /** 解析 JSON 文本(不落盘、不注册)。 */
    fun parseText(text: String): Result<JsonRuleDefinition> = runCatching {
        val definition = json.decodeFromString<JsonRuleDefinition>(text)
        val errors = definition.validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        require(!definition.id.startsWith(JsonRuleDefinition.RESERVED_PREFIX)) {
            "id 不能使用保留前缀 '${JsonRuleDefinition.RESERVED_PREFIX}'"
        }
        definition
    }

    /**
     * 安装(注册到插件管理器并持久化定义)。
     *
     * 顺序刻意是"先落盘、后注册":若安装协程在中途被取消/进程被杀,
     * 定义已在存储里,下次启动 [restoreAll] 会自动补注册,不会留下"半安装"状态。
     *
     * @param approvedCapabilities 用户在导入确认框里批准的能力,必须覆盖插件所需能力。
     */
    suspend fun install(
        definition: JsonRuleDefinition,
        approvedCapabilities: Set<PluginCapability>,
    ): Result<Unit> = runCatching {
        require(definition.requiredCapability in approvedCapabilities) {
            "未批准必需能力: ${definition.requiredCapability}"
        }
        require(manager.plugins.value.none { it.plugin.id == definition.id }) {
            "插件 ${definition.id} 已存在,请先删除再导入"
        }

        val current = store.read()
        store.save(current.filterNot { it.id == definition.id } + definition)

        manager.register(JsonRulePlugin(definition))
        // 物化后再写一次显式批准集合:即便默认"信任声明",外部导入也走显式授权路径
        manager.setApprovedCapabilities(definition.id, approvedCapabilities)
        Log.i(TAG, "installed rule plugin: ${definition.id}")
    }

    /**
     * 卸载:先删持久化定义,再移出注册表。
     *
     * 同理保证"取消安全":即使协程在 unregister 前被取消,重启后也不会复活
     * (定义已消失),最坏情况只是本次会话内仍生效。
     */
    suspend fun uninstall(pluginId: String): Result<Unit> = runCatching {
        val current = store.read()
        store.save(current.filterNot { it.id == pluginId })
        manager.unregister(pluginId)
        Log.i(TAG, "uninstalled rule plugin: $pluginId")
    }

    /** 启动时把已安装规则插件物化回注册表。 */
    suspend fun restoreAll(definitions: List<JsonRuleDefinition>) {
        definitions.forEach { definition ->
            runCatching {
                if (manager.plugins.value.none { it.plugin.id == definition.id }) {
                    manager.register(JsonRulePlugin(definition))
                }
            }.onFailure { Log.w(TAG, "failed to restore rule plugin ${definition.id}", it) }
        }
    }
}
