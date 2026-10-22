package app.androidrik.plugin.manager

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginManager] 纯逻辑单测:启用状态解析、能力授权交集、注册/加载顺序。
 *
 * 用内存 [PluginStateStore] 驱动,不依赖 Android/DataStore。
 */
class PluginManagerTest {

    private class InMemoryStateStore(
        initial: Map<String, PluginState> = emptyMap(),
    ) : PluginStateStore {
        var stored: MutableMap<String, PluginState> = initial.toMutableMap()
        var writeCount = 0

        override suspend fun read(): Map<String, PluginState> = stored.toMap()

        override suspend fun write(states: Map<String, PluginState>) {
            writeCount++
            stored = states.toMutableMap()
        }
    }

    private class FakePlugin(
        override val id: String,
        override val capabilities: Set<PluginCapability> = emptySet(),
        override val enabledByDefault: Boolean = true,
        override val displayName: String = id,
        override val description: String = "",
        override val version: String = "1.0.0",
    ) : RikkaPlugin {
        var enableCount = 0
        var disableCount = 0

        override suspend fun onEnable() {
            enableCount++
        }

        override suspend fun onDisable() {
            disableCount++
        }
    }

    @Test
    fun `plugin uses enabledByDefault when no persisted state`() = runTest {
        val manager = PluginManager(this, InMemoryStateStore())
        manager.start()

        val on = FakePlugin("on", enabledByDefault = true)
        val off = FakePlugin("off", enabledByDefault = false)
        manager.register(on)
        manager.register(off)

        assertEquals(2, manager.plugins.value.size)
        assertTrue(manager.plugins.value.first { it.plugin.id == "on" }.enabled)
        assertFalse(manager.plugins.value.first { it.plugin.id == "off" }.enabled)
        assertEquals(1, on.enableCount)
        assertEquals(0, off.enableCount)
    }

    @Test
    fun `persisted state overrides default and is applied before register resolves`() = runTest {
        val store = InMemoryStateStore(
            mapOf("a" to PluginState(enabled = false)),
        )
        val manager = PluginManager(this, store)
        manager.start()

        val plugin = FakePlugin("a", enabledByDefault = true)
        manager.register(plugin)

        assertFalse(manager.plugins.value.single().enabled)
        assertEquals(0, plugin.enableCount)
    }

    @Test
    fun `declared capabilities are trusted until user revokes them`() = runTest {
        val manager = PluginManager(this, InMemoryStateStore())
        manager.start()

        val plugin = FakePlugin(
            id = "cap",
            capabilities = setOf(PluginCapability.PROMPT_TRANSFORM, PluginCapability.NETWORK),
        )
        manager.register(plugin)

        val info = manager.plugins.value.single()
        assertEquals(
            setOf(PluginCapability.PROMPT_TRANSFORM, PluginCapability.NETWORK),
            info.grants,
        )
        assertTrue(info.hasCapability(PluginCapability.NETWORK))
    }

    @Test
    fun `revoking capabilities intersects with declared set`() = runTest {
        val manager = PluginManager(this, InMemoryStateStore())
        manager.start()

        val plugin = FakePlugin(
            id = "cap",
            capabilities = setOf(PluginCapability.PROMPT_TRANSFORM, PluginCapability.STORAGE),
        )
        manager.register(plugin)
        manager.setApprovedCapabilities("cap", setOf(PluginCapability.STORAGE, PluginCapability.NETWORK))

        val info = manager.plugins.value.single()
        assertEquals(setOf(PluginCapability.STORAGE), info.grants)
        assertFalse(info.hasCapability(PluginCapability.PROMPT_TRANSFORM))
    }

    @Test
    fun `setEnabled toggles plugin, persists state and notifies observers`() = runTest {
        val store = InMemoryStateStore()
        val manager = PluginManager(this, store)
        manager.start()

        val plugin = FakePlugin("toggle")
        manager.register(plugin)

        val observations = mutableListOf<Boolean>()
        manager.addObserver(object : PluginLifecycleObserver {
            override suspend fun onPluginEnabled(plugin: RikkaPlugin) {
                observations += true
            }

            override suspend fun onPluginDisabled(plugin: RikkaPlugin) {
                observations += false
            }
        })

        manager.setEnabled("toggle", false)
        assertFalse(manager.plugins.value.single().enabled)
        assertEquals(1, plugin.disableCount)
        assertFalse(store.stored.getValue("toggle").enabled)
        assertEquals(listOf(false), observations)

        manager.setEnabled("toggle", true)
        assertTrue(manager.plugins.value.single().enabled)
        // 注册时已 onEnable 一次,重新启用后累计两次
        assertEquals(2, plugin.enableCount)
        assertTrue(store.stored.getValue("toggle").enabled)
        assertEquals(listOf(false, true), observations)
    }

    @Test
    fun `register works without explicit start`() = runTest {
        val store = InMemoryStateStore(mapOf("lazy" to PluginState(enabled = false)))
        val manager = PluginManager(this, store)

        // 不调 start():awaitReady 应隐式启动加载,否则 register 会永久挂起
        manager.register(FakePlugin("lazy"))

        assertFalse(manager.plugins.value.single().enabled)
    }

    @Test
    fun `unregister removes plugin disables it and notifies observers`() = runTest {
        val manager = PluginManager(this, InMemoryStateStore())
        manager.start()

        val plugin = FakePlugin("gone")
        manager.register(plugin)

        val observations = mutableListOf<Boolean>()
        manager.addObserver(object : PluginLifecycleObserver {
            override suspend fun onPluginEnabled(plugin: RikkaPlugin) {
                observations += true
            }

            override suspend fun onPluginDisabled(plugin: RikkaPlugin) {
                observations += false
            }
        })

        assertTrue(manager.unregister("gone"))
        assertEquals(0, manager.plugins.value.size)
        assertEquals(1, plugin.disableCount)
        assertEquals(listOf(false), observations)
        // 二次卸载是 no-op
        assertFalse(manager.unregister("gone"))
    }

    @Test
    fun `duplicate registration is ignored`() = runTest {
        val manager = PluginManager(this, InMemoryStateStore())
        manager.start()

        val first = FakePlugin("dup")
        val second = FakePlugin("dup")
        manager.register(first)
        manager.register(second)

        assertEquals(1, manager.plugins.value.size)
        assertEquals(1, first.enableCount)
        assertEquals(0, second.enableCount)
    }

    @Test
    fun `enabledPlugins filters by runtime type and enabled flag`() = runTest {
        val manager = PluginManager(this, InMemoryStateStore())
        manager.start()

        val enabled = FakePlugin("enabled")
        val disabled = FakePlugin("disabled", enabledByDefault = false)
        manager.register(enabled)
        manager.register(disabled)

        val result = manager.enabledPlugins(FakePlugin::class.java)
        assertEquals(listOf("enabled"), result.map { it.id })
    }
}
