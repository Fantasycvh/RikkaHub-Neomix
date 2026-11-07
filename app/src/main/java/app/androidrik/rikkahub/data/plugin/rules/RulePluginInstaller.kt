package app.androidrik.rikkahub.data.plugin.rules

import android.util.Log
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.data.plugin.revision.PluginRevision
import app.androidrik.rikkahub.data.plugin.revision.PluginRevisionStore
import app.androidrik.rikkahub.data.plugin.revision.PrecheckRejectedException
import app.androidrik.rikkahub.data.plugin.revision.RevisionOrigin
import app.androidrik.rikkahub.data.plugin.revision.precheckRule
import app.androidrik.rikkahub.data.plugin.revision.recordActivatedRevision
import app.androidrik.rikkahub.data.plugin.revision.revertToLastGood
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
    /** 修订通道（R3）：预检历史存储；生效定义本体的读写仍在 [store]/[manager]。 */
    private val revisionStore: PluginRevisionStore? = null,
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

    // ---- 修订通道（R3，与 JsPluginInstaller 同口径）----

    /** 当前生效的规则 JSON（序列化形态，供历史记录 source）；插件不存在时 null。 */
    suspend fun currentSource(pluginId: String): String? =
        store.read().firstOrNull { it.id == pluginId }?.let { json.encodeToString(JsonRuleDefinition.serializer(), it) }

    suspend fun loadRevisions(pluginId: String): app.androidrik.rikkahub.data.plugin.revision.PluginRevisionState? =
        revisionStore?.load(pluginId)

    /**
     * 安装或更新（R3 同口径）：预检（结构+正则）→ 新 id 走 [install]；已有 id 走
     * 代次切换（unregister/register，保留授权与启停态）→ 激活成功后修订历史落盘。
     */
    suspend fun installOrUpdate(
        definition: JsonRuleDefinition,
        approvedCapabilities: Set<PluginCapability>,
        origin: RevisionOrigin = RevisionOrigin.IMPORT,
        /** source 用原始导入文本记录（无原文时回落序列化形态）；仅影响历史可读性 */
        sourceOverride: String? = null,
    ): Result<Unit> = runCatching {
        val revisions = requireNotNull(revisionStore) { "修订通道未接线（revisionStore 缺失）" }

        val report = precheckRule(definition)
        if (!report.passed) throw PrecheckRejectedException(report)

        val existing = manager.plugins.value.firstOrNull { it.plugin.id == definition.id }
        if (existing == null) {
            install(definition, approvedCapabilities).getOrThrow()
        } else {
            require(definition.requiredCapability in existing.grants) {
                "新修订声明了未授权能力: ${definition.requiredCapability}（请先卸载再导入以重新授权）"
            }
            val wasEnabled = existing.enabled
            val current = store.read()
            store.save(current.filterNot { it.id == definition.id } + definition)
            manager.unregister(definition.id)
            manager.register(JsonRulePlugin(definition))
            manager.setApprovedCapabilities(definition.id, existing.grants)
            if (!wasEnabled) manager.setEnabled(definition.id, false)
            Log.i(TAG, "updated rule plugin (new revision): ${definition.id} enabled=$wasEnabled")
        }

        val state = revisions.load(definition.id)
        val revisionNo = (state.revisions.maxOfOrNull { it.revisionNo } ?: 0L) + 1
        val source = sourceOverride
            ?: currentSource(definition.id) ?: json.encodeToString(JsonRuleDefinition.serializer(), definition)
        revisions.save(
            recordActivatedRevision(
                state,
                PluginRevision(
                    pluginId = definition.id,
                    revisionNo = revisionNo,
                    kind = "rules",
                    source = source,
                    origin = origin,
                    createdAt = System.currentTimeMillis(),
                    precheck = report,
                ),
            ).copy(draftSource = null),
        )
    }

    /** 回退到 last-good（同 JS 口径：预检→重载→双指针互换→清草稿）。 */
    suspend fun revertToLastGood(pluginId: String): Result<Unit> = runCatching {
        val revisions = requireNotNull(revisionStore) { "修订通道未接线（revisionStore 缺失）" }
        val state = revisions.load(pluginId)
        val target = revertToLastGood(state)
        val revision = target.revisions.first { it.revisionNo == target.lastGoodRevisionNo }
        require(revision.kind == "rules") { "未知修订载体: ${revision.kind}" }

        val definition = parseText(revision.source).getOrThrow()
        require(definition.id == pluginId) { "修订源码 id(${definition.id}) 与插件($pluginId) 不一致" }
        val report = precheckRule(definition)
        if (!report.passed) throw PrecheckRejectedException(report)

        val existing = manager.plugins.value.firstOrNull { it.plugin.id == pluginId }
        requireNotNull(existing) { "插件 $pluginId 未安装" }
        val wasEnabled = existing.enabled
        val current = store.read()
        store.save(current.filterNot { it.id == pluginId } + definition)
        manager.unregister(pluginId)
        manager.register(JsonRulePlugin(definition))
        manager.setApprovedCapabilities(pluginId, existing.grants)
        if (!wasEnabled) manager.setEnabled(pluginId, false)

        revisions.save(target.copy(draftSource = null))
        Log.i(TAG, "reverted rule plugin: $pluginId active=#${target.activeRevisionNo}")
    }
}
