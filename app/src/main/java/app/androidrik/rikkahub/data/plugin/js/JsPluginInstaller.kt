package app.androidrik.rikkahub.data.plugin.js

import android.content.Context
import android.util.Log
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway
import app.androidrik.rikkahub.data.plugin.revision.PluginPrecheck
import app.androidrik.rikkahub.data.plugin.revision.PluginRevision
import app.androidrik.rikkahub.data.plugin.revision.PluginRevisionStore
import app.androidrik.rikkahub.data.plugin.revision.PrecheckRejectedException
import app.androidrik.rikkahub.data.plugin.revision.RevisionOrigin
import app.androidrik.rikkahub.data.plugin.revision.recordActivatedRevision
import app.androidrik.rikkahub.data.plugin.revision.revertToLastGood
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
    /** 修订通道（R1）：预检器 + 历史存储；生效定义本体的读写仍在 [store]/[manager]。 */
    private val precheck: PluginPrecheck? = null,
    private val revisionStore: PluginRevisionStore? = null,
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

    /**
     * 安装或更新（修订通道 R1，设计稿 §四/§五.3）：
     * 先预检（不过 → [PrecheckRejectedException]，旧定义与修订双指针都不动，last-good 继续服务），
     * 再分派：
     * - 新 id：走 [install] 并记首条修订。
     * - 已存在 id：**代次切换**——unregister + register 产生新 Activation（P1a 代次保证旧实例
     *   句柄/回调不能修改或撤销新实例的注册）；保留既有授权与启停态；新修订声明了既有授权
     *   未覆盖的必需能力时拒绝（R1 不重弹授权框，改授权走卸载重装）。
     * 激活成功后修订历史才落盘（active/last-good 双指针推进，revisionNo = 历史 + 1）。
     */
    suspend fun installOrUpdate(
        definition: JsPluginDefinition,
        approvedCapabilities: Set<PluginCapability>,
        origin: RevisionOrigin = RevisionOrigin.IMPORT,
    ): Result<Unit> = runCatching {
        val checker = requireNotNull(precheck) { "修订通道未接线（precheck 缺失）" }
        val revisions = requireNotNull(revisionStore) { "修订通道未接线（revisionStore 缺失）" }

        val report = checker.checkJs(definition)
        if (!report.passed) throw PrecheckRejectedException(report)

        val existing = manager.plugins.value.firstOrNull { it.plugin.id == definition.id }
        if (existing == null) {
            install(definition, approvedCapabilities).getOrThrow()
        } else {
            val declared = definition.manifest.capabilities
            val mandatory = declared - setOf(PluginCapability.NETWORK, PluginCapability.STORAGE)
            require(mandatory.all { it in existing.grants }) {
                "新修订声明了未授权能力: ${mandatory - existing.grants}（请先卸载再导入以重新授权）"
            }
            val wasEnabled = existing.enabled
            // 先落盘定义再重载（取消安全，同 install 的顺序）
            val current = store.read()
            store.save(current.filterNot { it.id == definition.id } + definition)
            manager.unregister(definition.id)
            manager.register(newPlugin(definition))
            manager.setApprovedCapabilities(definition.id, existing.grants)
            if (!wasEnabled) manager.setEnabled(definition.id, false)
            Log.i(TAG, "updated js plugin (new revision): ${definition.id} enabled=$wasEnabled")
        }

        val state = revisions.load(definition.id)
        val revisionNo = (state.revisions.maxOfOrNull { it.revisionNo } ?: 0L) + 1
        revisions.save(
            recordActivatedRevision(
                state,
                PluginRevision(
                    pluginId = definition.id,
                    revisionNo = revisionNo,
                    kind = "js",
                    source = definition.source,
                    origin = origin,
                    createdAt = System.currentTimeMillis(),
                    precheck = report,
                ),
            ).copy(draftSource = null), // 激活成功即清草稿
        )
    }

    /** 当前生效的 JS 源码（编辑器初始内容）；插件不存在时 null。 */
    suspend fun currentSource(pluginId: String): String? =
        store.read().firstOrNull { it.id == pluginId }?.source

    /** 编辑器草稿读取（null = 无草稿）。 */
    suspend fun loadDraft(pluginId: String): String? =
        revisionStore?.load(pluginId)?.draftSource

    /** 修订历史读取（未接线时 null——release 构建通道未启用）。 */
    suspend fun loadRevisions(pluginId: String): app.androidrik.rikkahub.data.plugin.revision.PluginRevisionState? =
        revisionStore?.load(pluginId)

    /** 保存编辑器草稿（不触发预检/激活；激活成功后由调用方清除）。 */
    suspend fun saveDraft(pluginId: String, source: String?) {
        val revisions = revisionStore ?: return
        val state = revisions.load(pluginId)
        revisions.save(state.copy(draftSource = source))
    }

    /**
     * 回退到 last-good（R2，设计稿 §3.2）：双指针互换（不占新 revisionNo），
     * 用 last-good 修订的源码重建定义并走一次预检（历史通过≠现在通过，环境可能变了），
     * 然后按 [installOrUpdate] 的更新路径重载。
     */
    suspend fun revertToLastGood(pluginId: String): Result<Unit> = runCatching {
        val checker = requireNotNull(precheck) { "修订通道未接线（precheck 缺失）" }
        val revisions = requireNotNull(revisionStore) { "修订通道未接线（revisionStore 缺失）" }
        val state = revisions.load(pluginId)
        val target = app.androidrik.rikkahub.data.plugin.revision.revertToLastGood(state)
        val revision = target.revisions.first { it.revisionNo == target.lastGoodRevisionNo }
        require(revision.kind == "js") { "未知修订载体: ${revision.kind}" }

        val definition = JsPluginManifest.parse(revision.source).getOrThrow()
        // id 一致性：修订历史属于该插件，源码 id 必须一致（防御历史文件被手改）
        require(definition.id == pluginId) { "修订源码 id(${definition.id}) 与插件($pluginId) 不一致" }
        val report = checker.checkJs(definition)
        if (!report.passed) throw PrecheckRejectedException(report)

        val existing = manager.plugins.value.firstOrNull { it.plugin.id == pluginId }
        requireNotNull(existing) { "插件 $pluginId 未安装" }
        val wasEnabled = existing.enabled
        val current = store.read()
        store.save(current.filterNot { it.id == pluginId } + definition)
        manager.unregister(pluginId)
        manager.register(newPlugin(definition))
        manager.setApprovedCapabilities(pluginId, existing.grants)
        if (!wasEnabled) manager.setEnabled(pluginId, false)

        // 双指针互换后落盘（回退语义见 revertToLastGood 纯函数）；清掉草稿
        revisions.save(target.copy(draftSource = null))
        Log.i(TAG, "reverted js plugin: $pluginId active=#${target.activeRevisionNo}")
    }

    /** 卸载:先删持久化定义,再移出注册表(取消安全)。 */    suspend fun uninstall(pluginId: String): Result<Unit> = runCatching {
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
    private fun newPlugin(definition: JsPluginDefinition): JsPlugin {
        lateinit var plugin: JsPlugin
        fun granted(capability: PluginCapability): Boolean = manager.plugins.value.any {
            it.plugin === plugin && it.enabled && it.hasCapability(capability)
        }
        plugin = JsPlugin(
            definition = definition,
            context = context,
            gateway = gateway,
            networkGranted = { granted(PluginCapability.NETWORK) },
            keyValueStore = keyValueStore,
            capabilityGranted = ::granted,
        )
        return plugin
    }

    private companion object {
        const val MAX_FETCH_BYTES = 512 * 1024
    }
}
