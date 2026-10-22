package app.androidrik.plugin.manager

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 插件管理器:本 fork 唯一的插件注册表(不学 BiliPai 五套管理并存)。
 *
 * 生命周期:
 * 1. 宿主创建实例后调用 [start](惰性加载持久化状态);
 * 2. 宿主调用 [register] 注册内置插件 —— 会先等待状态加载完成,
 *    避免"注册早于读取"导致启用状态被默认值覆盖(对应 BiliPai 的
 *    `pendingEnabledOverrides` 竞态,这里用 `awaitReady` 语义解决);
 * 3. 插件中心调用 [setEnabled] / [setApprovedCapabilities],状态即时落盘。
 *
 * 线程模型:所有读走 [plugins] 的 StateFlow 快照;写操作经 [mutex] 串行化。
 * 插件的 [RikkaPlugin.onEnable]/[onDisable] 在锁内调用,插件实现不得反过来
 * 调用管理器(会死锁)——这是刻意从窄的契约。
 */
class PluginManager(
    private val scope: CoroutineScope,
    private val stateStore: PluginStateStore,
) {
    private val mutex = Mutex()

    /** 已注册插件(保持注册顺序,决定插件中心展示顺序)。 */
    private val registered = LinkedHashMap<String, RikkaPlugin>()

    /** 当前视图,按注册顺序排序。 */
    private val infos = LinkedHashMap<String, PluginInfo>()

    /** 持久化状态快照;读取完成后才有内容。 */
    private val states = LinkedHashMap<String, PluginState>()

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())

    /** 插件列表(含启用状态与已授权能力),供 Compose 与装配点读取。 */
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val observers = CopyOnWriteArrayList<PluginLifecycleObserver>()

    private val ready = CompletableDeferred<Unit>()

    private val loadJob = scope.async(start = CoroutineStart.LAZY) {
        val persisted = runCatching { stateStore.read() }.getOrDefault(emptyMap())
        mutex.withLock {
            states.clear()
            states.putAll(persisted)
            // 注册若抢先发生(理论上不会,register 会先 await),这里补一次解析。
            registered.values.forEach { plugin -> resolveLocked(plugin) }
        }
        ready.complete(Unit)
    }

    /** 启动持久化状态加载(幂等;不调用也会在首次 [awaitReady] 时隐式启动)。 */
    fun start() {
        loadJob.start()
    }

    /** 等待状态加载完成;注册与插件中心操作前隐式等待。 */
    suspend fun awaitReady() {
        // 隐式启动:调用方不必记住先 start(),也避免懒 Deferred 永不启动导致挂起
        loadJob.start()
        loadJob.await()
    }

    /** 注册一个内置插件(重复 id 静默忽略)。 */
    suspend fun register(plugin: RikkaPlugin) {
        awaitReady()
        mutex.withLock {
            if (registered.containsKey(plugin.id)) return
            registered[plugin.id] = plugin
            resolveLocked(plugin)
        }
        val info = infos[plugin.id] ?: return
        if (info.enabled) {
            plugin.onEnable()
            notifyObservers(plugin, enabled = true)
        }
    }

    /** 启用/禁用插件并落盘。 */
    suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        awaitReady()
        val plugin = mutex.withLock {
            val info = infos[pluginId] ?: return
            if (info.enabled == enabled) return
            states[pluginId] = (states[pluginId] ?: PluginState()).copy(enabled = enabled)
            persistLocked()
            publishLocked(pluginId, info.copy(enabled = enabled))
            info.plugin
        }
        if (enabled) plugin.onEnable() else plugin.onDisable()
        notifyObservers(plugin, enabled)
    }

    /**
     * 更新用户批准的能力集合。
     *
     * 传 null 表示恢复到"默认信任声明";传空集即撤销全部能力。
     * 未知插件会抛出异常;写盘成功后才发布授权,失败与协程取消原样传播。
     */
    suspend fun setApprovedCapabilities(pluginId: String, approved: Set<PluginCapability>?) {
        awaitReady()
        mutex.withLock {
            val info = requireNotNull(infos[pluginId]) { "Unknown plugin: $pluginId" }
            persistApprovedCapabilitiesLocked(pluginId, info, approved)
        }
    }

    /**
     * 原子更新单项授权,基于锁内当前有效 grants 而非调用方的 UI 快照。
     * 默认信任声明时同样落成显式集合;未知插件或未声明能力会抛出异常。
     * 写盘失败(包括协程取消)时不提交内存状态,异常原样传播。
     */
    suspend fun setCapabilityApproved(
        pluginId: String,
        capability: PluginCapability,
        approved: Boolean,
    ) {
        awaitReady()
        mutex.withLock {
            val info = requireNotNull(infos[pluginId]) { "Unknown plugin: $pluginId" }
            require(capability in info.plugin.capabilities) {
                "Plugin $pluginId does not declare capability $capability"
            }
            val current = resolveGrants(info.plugin, states[pluginId])
            val updated = if (approved) current + capability else current - capability
            persistApprovedCapabilitiesLocked(pluginId, info, updated)
        }
    }

    /**
     * 从注册表移除插件(用于卸载 JSON 规则插件等动态安装的插件)。
     *
     * 保留其持久化状态(启用/授权):同 id 重新安装后恢复原状态。
     * 返回是否确实移除了一个已注册插件。
     */
    suspend fun unregister(pluginId: String): Boolean {
        awaitReady()
        val (plugin, wasEnabled) = mutex.withLock {
            val info = infos[pluginId] ?: return false
            infos.remove(pluginId)
            registered.remove(pluginId)
            _plugins.value = registered.keys.mapNotNull { id -> infos[id] }
            info.plugin to info.enabled
        }
        if (wasEnabled) {
            runCatching { plugin.onDisable() }
            notifyObservers(plugin, enabled = false)
        }
        return true
    }

    /** 添加生命周期观察者(宿主装配 UI/工具注册用)。 */
    fun addObserver(observer: PluginLifecycleObserver) {
        observers += observer
    }

    fun removeObserver(observer: PluginLifecycleObserver) {
        observers -= observer
    }

    /** 按类型取已启用插件(插件须实现该能力接口,如 `PluginToolProvider`)。 */
    fun <T : RikkaPlugin> enabledPlugins(type: Class<T>): List<T> =
        _plugins.value.filter { it.enabled }.map { it.plugin }.filterIsInstance(type)

    // ---- 内部 ----

    private fun resolveLocked(plugin: RikkaPlugin) {
        val state = states[plugin.id]
        val enabled = state?.enabled ?: plugin.enabledByDefault
        val info = PluginInfo(
            plugin = plugin,
            enabled = enabled,
            grants = resolveGrants(plugin, state),
        )
        publishLocked(plugin.id, info)
    }

    private fun publishLocked(pluginId: String, info: PluginInfo) {
        infos[pluginId] = info
        _plugins.value = registered.keys.mapNotNull { id -> infos[id] }
    }

    private fun resolveGrants(plugin: RikkaPlugin, state: PluginState?): Set<PluginCapability> {
        val declared = plugin.capabilities
        val approved = state?.approvedCapabilities ?: return declared
        return declared.intersect(approved)
    }

    private suspend fun persistApprovedCapabilitiesLocked(
        pluginId: String,
        info: PluginInfo,
        approved: Set<PluginCapability>?,
    ) {
        val updated = (states[pluginId] ?: PluginState(enabled = info.enabled)).copy(
            approvedCapabilities = approved?.toSet(),
        )
        // 不先修改 states:写盘失败或取消时,后续更新也不能携带未提交的授权。
        stateStore.write(states.toMap() + (pluginId to updated))
        states[pluginId] = updated
        publishLocked(pluginId, info.copy(grants = resolveGrants(info.plugin, updated)))
    }

    private suspend fun persistLocked() {
        // 传快照:写盘期间不暴露可变 Map;失败不回滚内存态(下次操作会再写)。
        val snapshot = states.toMap()
        runCatching { stateStore.write(snapshot) }
    }

    private suspend fun notifyObservers(plugin: RikkaPlugin, enabled: Boolean) {
        observers.forEach { observer ->
            runCatching {
                if (enabled) observer.onPluginEnabled(plugin) else observer.onPluginDisabled(plugin)
            }
        }
    }
}
