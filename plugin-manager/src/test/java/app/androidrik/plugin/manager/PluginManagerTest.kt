package app.androidrik.plugin.manager

import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.api.RikkaPlugin
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
        var writeFailure: Exception? = null
        var beforeWrite: (suspend () -> Unit)? = null

        override suspend fun read(): Map<String, PluginState> = stored.toMap()

        override suspend fun write(states: Map<String, PluginState>) {
            writeCount++
            beforeWrite?.invoke()
            writeFailure?.let { throw it }
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
    fun `single capability revocation starts from default trust and can revoke all and reapprove`() = runTest {
        val store = InMemoryStateStore()
        val manager = PluginManager(this, store)
        val plugin = FakePlugin("cap", setOf(PluginCapability.NETWORK, PluginCapability.STORAGE))
        manager.register(plugin)

        manager.setCapabilityApproved("cap", PluginCapability.NETWORK, false)
        assertEquals(setOf(PluginCapability.STORAGE), manager.plugins.value.single().grants)
        manager.setCapabilityApproved("cap", PluginCapability.STORAGE, false)
        assertEquals(emptySet<PluginCapability>(), manager.plugins.value.single().grants)
        assertEquals(emptySet<PluginCapability>(), store.stored.getValue("cap").approvedCapabilities)

        val restarted = PluginManager(this, store)
        restarted.register(plugin)
        assertEquals(emptySet<PluginCapability>(), restarted.plugins.value.single().grants)
        restarted.setCapabilityApproved("cap", PluginCapability.NETWORK, true)
        assertEquals(setOf(PluginCapability.NETWORK), restarted.plugins.value.single().grants)
        assertEquals(setOf(PluginCapability.NETWORK), store.stored.getValue("cap").approvedCapabilities)
    }

    @Test
    fun `capability updates preserve disabled state and do not invoke lifecycle`() = runTest {
        for (persisted in listOf(false, true)) {
            val store = InMemoryStateStore(
                if (persisted) mapOf("cap" to PluginState(enabled = false)) else emptyMap(),
            )
            val manager = PluginManager(this, store)
            val plugin = FakePlugin("cap", setOf(PluginCapability.NETWORK), enabledByDefault = false)
            manager.register(plugin)
            manager.setCapabilityApproved("cap", PluginCapability.NETWORK, false)
            manager.setCapabilityApproved("cap", PluginCapability.NETWORK, true)
            manager.setApprovedCapabilities("cap", emptySet())
            assertFalse(manager.plugins.value.single().enabled)
            assertFalse(store.stored.getValue("cap").enabled)
            val restarted = PluginManager(this, store)
            restarted.register(plugin)
            assertFalse(restarted.plugins.value.single().enabled)
            assertEquals(0, plugin.enableCount)
            assertEquals(0, plugin.disableCount)
        }
    }

    @Test
    fun `single capability update rejects unknown plugins and undeclared capabilities`() = runTest {
        val store = InMemoryStateStore()
        val manager = PluginManager(this, store)
        manager.register(FakePlugin("cap", setOf(PluginCapability.NETWORK)))
        for (approved in listOf(false, true)) {
            assertFailsWith<IllegalArgumentException> {
                manager.setCapabilityApproved("missing", PluginCapability.NETWORK, approved)
            }
            assertFailsWith<IllegalArgumentException> {
                manager.setCapabilityApproved("cap", PluginCapability.STORAGE, approved)
            }
        }
        assertEquals(0, store.writeCount)
        assertEquals(setOf(PluginCapability.NETWORK), manager.plugins.value.single().grants)
    }

    @Test
    fun `concurrent single updates serialize against current grants without stale snapshot loss`() = runTest {
        val store = InMemoryStateStore()
        val manager = PluginManager(this, store)
        manager.register(FakePlugin("cap", setOf(PluginCapability.NETWORK, PluginCapability.STORAGE)))
        manager.setApprovedCapabilities("cap", emptySet())
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        store.beforeWrite = {
            writeStarted.complete(Unit)
            releaseWrite.await()
        }
        val network = async { manager.setCapabilityApproved("cap", PluginCapability.NETWORK, true) }
        writeStarted.await()
        val storage = async { manager.setCapabilityApproved("cap", PluginCapability.STORAGE, true) }
        // No candidate grants may become visible while persistence is suspended.
        assertEquals(emptySet<PluginCapability>(), manager.plugins.value.single().grants)
        releaseWrite.complete(Unit)
        network.await()
        storage.await()
        val all = setOf(PluginCapability.NETWORK, PluginCapability.STORAGE)
        assertEquals(all, manager.plugins.value.single().grants)
        assertEquals(all, store.stored.getValue("cap").approvedCapabilities)

        val revokeNetwork = async { manager.setCapabilityApproved("cap", PluginCapability.NETWORK, false) }
        val revokeStorage = async { manager.setCapabilityApproved("cap", PluginCapability.STORAGE, false) }
        revokeNetwork.await()
        revokeStorage.await()
        assertEquals(emptySet<PluginCapability>(), manager.plugins.value.single().grants)
        assertEquals(emptySet<PluginCapability>(), store.stored.getValue("cap").approvedCapabilities)
    }

    @Test
    fun `failed or cancelled grant persistence propagates without publishing or leaking pending state`() = runTest {
        for (bulk in listOf(false, true)) {
            for (failure in listOf(IOException("write failed"), CancellationException("write cancelled"))) {
                val store = InMemoryStateStore()
                val manager = PluginManager(this, store)
                manager.register(FakePlugin("cap", setOf(PluginCapability.NETWORK, PluginCapability.STORAGE)))
                val before = manager.plugins.value
                store.writeFailure = failure
                val caught = assertFailsWith<Exception> {
                    if (bulk) manager.setApprovedCapabilities("cap", emptySet())
                    else manager.setCapabilityApproved("cap", PluginCapability.NETWORK, false)
                }
                assertTrue(caught === failure)
                assertTrue(before === manager.plugins.value)
                assertTrue(store.stored.isEmpty())
                store.writeFailure = null
                // A subsequent successful update must start from the last committed grants.
                manager.setCapabilityApproved("cap", PluginCapability.STORAGE, false)
                assertEquals(setOf(PluginCapability.NETWORK), manager.plugins.value.single().grants)
                assertEquals(setOf(PluginCapability.NETWORK), store.stored.getValue("cap").approvedCapabilities)
            }
        }
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class}, got $failure", failure)
        }
        throw AssertionError("Expected ${T::class} to be thrown")
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
