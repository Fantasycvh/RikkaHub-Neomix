package app.androidrik.plugin.manager

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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
 * 生命周期在全局锁外、按注册实例串行执行。启用成功后才发布 enabled；停用立即撤销贡献资格。
 * 回调可查询状态和修改授权；同实例的递归生命周期操作会显式拒绝，避免等待自身死锁。
 */
class PluginManager(
    private val scope: CoroutineScope,
    private val stateStore: PluginStateStore,
) {
    private val mutex = Mutex()

    /** 已注册插件(保持注册顺序,决定插件中心展示顺序)。 */
    private class Entry(val plugin: RikkaPlugin) {
        val lifecycle = Mutex()
        var activation: Activation? = null
    }

    private class Activation {
        val resources = PluginResources()
        var started = false
        var completed = false
        var cleaned = false
        val notified = mutableListOf<PluginLifecycleObserver>()
    }

    private class LifecycleCall(val entry: Entry, val parent: LifecycleCall?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<LifecycleCall>
        fun contains(target: Entry): Boolean = entry === target || parent?.contains(target) == true
    }

    private val registered = LinkedHashMap<String, Entry>()

    /** 当前视图,按注册顺序排序。 */
    private val infos = LinkedHashMap<String, PluginInfo>()

    /** 持久化状态快照;读取完成后才有内容。 */
    private val states = LinkedHashMap<String, PluginState>()

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())

    /** 插件列表(含启用状态与已授权能力),供 Compose 与装配点读取。 */
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val observers = CopyOnWriteArrayList<PluginLifecycleObserver>()

    private val loadJob = scope.async(start = CoroutineStart.LAZY) {
        val persisted = try {
            stateStore.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        mutex.withLock {
            states.clear()
            states.putAll(persisted)
        }
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
    suspend fun register(plugin: RikkaPlugin): PluginRegistration {
        awaitReady()
        val entry = Entry(plugin)
        val activation = mutex.withLock {
            // 重复 id 保持 no-op；返回的句柄不拥有已有注册。
            if (registered.containsKey(plugin.id)) return PluginRegistration { false }
            registered[plugin.id] = entry
            val state = states[plugin.id]
            val activation = if (state?.enabled ?: plugin.enabledByDefault) Activation() else null
            entry.activation = activation
            publishLocked(plugin.id, PluginInfo(plugin, false, resolveGrants(plugin, state)))
            activation
        }
        if (activation != null) activate(entry, activation)
        return PluginRegistration { unregisterEntry(plugin.id, entry) }
    }

    /** 启用/禁用插件并落盘。 */
    suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        awaitReady()
        val (entry, previous, next) = mutex.withLock {
            val info = infos[pluginId] ?: return
            val entry = registered.getValue(pluginId)
            checkNotReentrant(entry)
            if (enabled && entry.activation != null) return
            if (!enabled && entry.activation == null &&
                !(states[pluginId]?.enabled ?: entry.plugin.enabledByDefault)
            ) return
            val updated = (states[pluginId] ?: PluginState()).copy(enabled = enabled)
            stateStore.write(states.toMap() + (pluginId to updated))
            states[pluginId] = updated
            val previous = entry.activation
            previous?.resources?.invalidate()
            val next = if (enabled) Activation() else null
            entry.activation = next
            publishLocked(pluginId, info.copy(enabled = false, failure = null))
            Triple(entry, previous, next)
        }
        if (previous != null) deactivate(entry, previous)
        if (next != null) activate(entry, next)
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
        return unregisterEntry(pluginId, null)
    }

    private suspend fun unregisterEntry(pluginId: String, expected: Entry?): Boolean {
        val (entry, activation) = mutex.withLock {
            val entry = registered[pluginId] ?: return false
            if (expected != null && entry !== expected) return false
            checkNotReentrant(entry)
            val activation = entry.activation
            activation?.resources?.invalidate()
            entry.activation = null
            infos.remove(pluginId)
            registered.remove(pluginId)
            _plugins.value = registered.keys.mapNotNull { id -> infos[id] }
            entry to activation
        }
        if (activation != null) deactivate(entry, activation)
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

    private suspend fun checkNotReentrant(entry: Entry) {
        check(coroutineContext[LifecycleCall]?.contains(entry) != true) {
            "Recursive plugin lifecycle operation: ${entry.plugin.id}"
        }
    }

    private suspend fun activate(entry: Entry, activation: Activation) {
        try {
            entry.lifecycle.withLock {
                withContext(LifecycleCall(entry, coroutineContext[LifecycleCall])) {
                    if (!activation.resources.isActive) return@withContext
                    try {
                        activation.started = true
                        entry.plugin.onEnable()
                        for (observer in observers) {
                            if (!activation.resources.isActive) break
                            activation.notified += observer
                            observer.onPluginEnabled(entry.plugin, activation.resources)
                        }
                        mutex.withLock {
                            if (registered[entry.plugin.id] === entry && entry.activation === activation &&
                                activation.resources.isActive
                            ) {
                                activation.completed = true
                                publishLocked(entry.plugin.id, infos.getValue(entry.plugin.id).copy(enabled = true))
                            }
                        }
                    } catch (failure: Throwable) {
                        activation.resources.invalidate()
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (registered[entry.plugin.id] === entry && entry.activation === activation) {
                                    entry.activation = null
                                    publishLocked(entry.plugin.id, infos.getValue(entry.plugin.id).copy(
                                        enabled = false, failure = failure,
                                    ))
                                }
                            }
                            try { cleanup(entry, activation) } catch (error: Throwable) {
                                if (error !== failure) failure.addSuppressed(error)
                            }
                        }
                        throw failure
                    }
                    if (!activation.resources.isActive) withContext(NonCancellable) { cleanup(entry, activation) }
                }
            }
        } catch (failure: Throwable) {
            // Cancellation may occur while waiting for this instance's previous lifecycle callback.
            activation.resources.invalidate()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (registered[entry.plugin.id] === entry && entry.activation === activation) {
                        entry.activation = null
                        publishLocked(entry.plugin.id, infos.getValue(entry.plugin.id).copy(enabled = false, failure = failure))
                    }
                }
                try { deactivate(entry, activation) } catch (error: Throwable) {
                    if (error !== failure) failure.addSuppressed(error)
                }
            }
            throw failure
        }
    }

    private suspend fun deactivate(entry: Entry, activation: Activation) {
        withContext(NonCancellable) {
            try {
                entry.lifecycle.withLock {
                    withContext(LifecycleCall(entry, coroutineContext[LifecycleCall])) { cleanup(entry, activation) }
                }
            } catch (failure: Throwable) {
                mutex.withLock {
                    if (registered[entry.plugin.id] === entry && entry.activation == null) {
                        publishLocked(entry.plugin.id, infos.getValue(entry.plugin.id).copy(failure = failure))
                    }
                }
                throw failure
            }
        }
        coroutineContext.ensureActive()
    }

    /** 尝试所有清理项，错误汇总后抛出；每次启用的清理最多执行一次。 */
    private suspend fun cleanup(entry: Entry, activation: Activation) {
        if (activation.cleaned) return
        activation.cleaned = true
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { activation.resources.close() }
        if (activation.started) attempt { entry.plugin.onDisable() }
        val targets = if (activation.completed) observers.toList() else activation.notified.toList()
        targets.forEach { observer -> attempt { observer.onPluginDisabled(entry.plugin) } }
        failure?.let { throw it }
    }
}
