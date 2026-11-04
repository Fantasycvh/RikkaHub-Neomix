package app.androidrik.plugin.manager

import app.androidrik.plugin.api.RikkaPlugin
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginLifecycleTest {
    private class Store : PluginStateStore {
        var values = emptyMap<String, PluginState>()
        var failure: Exception? = null
        override suspend fun read() = values
        override suspend fun write(states: Map<String, PluginState>) {
            failure?.let { throw it }
            values = states
        }
    }

    private class Plugin(
        override val id: String = "test",
        val enable: suspend () -> Unit = {},
        val disable: suspend () -> Unit = {},
    ) : RikkaPlugin {
        override val displayName = id
        override val description = ""
        override val version = "1"
        var stops = 0
        override suspend fun onEnable() = enable()
        override suspend fun onDisable() { stops++; disable() }
    }

    @Test fun `pending enable is not callable and old registration cannot remove replacement`() = runTest {
        val manager = PluginManager(this, Store())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val old = Plugin(enable = { entered.complete(Unit); resume.await() })
        val mounting = async { manager.register(old) }
        entered.await()
        assertFalse(manager.plugins.value.single().enabled)
        val removal = async { manager.unregister("test") }
        runCurrent()
        val replacement = Plugin()
        val handle = manager.register(replacement)
        resume.complete(Unit)
        val oldHandle = mounting.await()
        assertTrue(removal.await())
        assertFalse(oldHandle.unregister())
        assertSame(replacement, manager.plugins.value.single().plugin)
        assertTrue(manager.plugins.value.single().enabled)
        assertEquals(1, old.stops)
        assertTrue(handle.unregister())
        assertFalse(handle.unregister())
    }

    @Test fun `late observer cannot install contributions after same id reinstall`() = runTest {
        val manager = PluginManager(this, Store())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val old = Plugin()
        var installed = 0
        var released = 0
        manager.addObserver(object : PluginLifecycleObserver {
            override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
                if (plugin === old) { entered.complete(Unit); resume.await() }
                resources.register { installed++; AutoCloseable { released++ } }
            }
        })
        val mounting = async { manager.register(old) }
        entered.await()
        val removal = async { manager.unregister("test") }
        runCurrent()
        val replacement = manager.register(Plugin())
        assertEquals(1, installed)
        resume.complete(Unit)
        mounting.await()
        removal.await()
        assertEquals(1, installed)
        assertEquals(0, released)
        replacement.unregister()
        assertEquals(1, released)
    }

    @Test fun `observer failure rolls back contributions and retry succeeds`() = runTest {
        val manager = PluginManager(this, Store())
        val plugin = Plugin()
        var fail = true
        var count = 0
        manager.addObserver(object : PluginLifecycleObserver {
            override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
                resources.register { count++; AutoCloseable { count-- } }
                if (fail) throw IOException("mount failed")
            }
        })
        try { manager.register(plugin); fail("Expected mount failure") } catch (_: IOException) { }
        assertEquals(0, count)
        assertFalse(manager.plugins.value.single().enabled)
        assertTrue(manager.plugins.value.single().failure is IOException)
        assertEquals(1, plugin.stops)
        fail = false
        manager.setEnabled("test", true)
        assertEquals(1, count)
        assertTrue(manager.plugins.value.single().enabled)
        assertNull(manager.plugins.value.single().failure)
        manager.setEnabled("test", false)
        assertEquals(0, count)
    }

    @Test fun `cancelled enable releases resources and propagates cancellation`() = runTest {
        val manager = PluginManager(this, Store())
        val entered = CompletableDeferred<Unit>()
        var released = false
        val plugin = Plugin()
        manager.addObserver(object : PluginLifecycleObserver {
            override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
                resources.register { AutoCloseable { released = true } }
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        })
        val mounting = launch { manager.register(plugin) }
        entered.await()
        mounting.cancelAndJoin()
        assertTrue(released)
        assertFalse(manager.plugins.value.single().enabled)
        assertEquals(1, plugin.stops)
        assertTrue(manager.plugins.value.single().failure is CancellationException)
    }

    @Test fun `disable then reenable while starting cleans old activation before new enable`() = runTest {
        val manager = PluginManager(this, Store())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var starts = 0
        val plugin = Plugin(enable = { if (++starts == 1) { entered.complete(Unit); resume.await() } })
        val first = async { manager.register(plugin) }
        entered.await()
        val stop = async { manager.setEnabled("test", false) }
        runCurrent()
        val start = async { manager.setEnabled("test", true) }
        runCurrent()
        resume.complete(Unit)
        first.await(); stop.await(); start.await()
        assertEquals(2, starts)
        assertEquals(1, plugin.stops)
        assertTrue(manager.plugins.value.single().enabled)
    }

    @Test fun `cleanup failure still releases every resource and disables plugin`() = runTest {
        val manager = PluginManager(this, Store())
        val order = mutableListOf<Int>()
        val plugin = Plugin(disable = { order += 3 })
        manager.addObserver(object : PluginLifecycleObserver {
            override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
                resources.register { AutoCloseable { order += 1 } }
                resources.register { AutoCloseable { order += 2; throw IOException("close") } }
            }
            override suspend fun onPluginDisabled(plugin: RikkaPlugin) { order += 4 }
        })
        manager.register(plugin)
        try { manager.setEnabled("test", false); fail("Expected close failure") } catch (_: IOException) { }
        assertEquals(listOf(2, 1, 3, 4), order)
        assertFalse(manager.plugins.value.single().enabled)
        manager.setEnabled("test", false)
        assertEquals(4, order.size)
    }

    @Test fun `duplicate handle owns nothing and failed persistence does not disable`() = runTest {
        val store = Store()
        val manager = PluginManager(this, store)
        manager.register(Plugin())
        assertFalse(manager.register(Plugin()).unregister())
        store.failure = IOException("disk full")
        try { manager.setEnabled("test", false); fail("Expected write failure") } catch (_: IOException) { }
        assertTrue(manager.plugins.value.single().enabled)
    }

    @Test fun `recursive lifecycle fails explicitly while other plugin registration works`() = runTest {
        val manager = PluginManager(this, Store())
        val plugin = Plugin(enable = {
            manager.register(Plugin("other"))
            manager.setEnabled("test", false)
        })
        try { manager.register(plugin); fail("Expected recursive lifecycle rejection") } catch (_: IllegalStateException) { }
        assertTrue(manager.plugins.value.first { it.plugin.id == "other" }.enabled)
        assertFalse(manager.plugins.value.first { it.plugin.id == "test" }.enabled)
    }

    @Test fun `resource disposal is idempotent and late install never runs`() {
        val resources = PluginResources()
        var count = 0
        resources.register { AutoCloseable { count++ } }
        resources.close()
        resources.close()
        assertEquals(1, count)
        assertFalse(resources.register { error("stale installation") })
    }

    @Test fun `onEnable failure is not published and can be retried`() = runTest {
        val manager = PluginManager(this, Store())
        var broken = true
        val plugin = Plugin(enable = { if (broken) throw IOException("startup") })
        try { manager.register(plugin); fail("Expected startup failure") } catch (_: IOException) { }
        assertFalse(manager.plugins.value.single().enabled)
        assertEquals(1, plugin.stops)
        broken = false
        manager.setEnabled("test", true)
        assertTrue(manager.plugins.value.single().enabled)
    }

    @Test fun `disable cancellation waits for cleanup and remains cancellation`() = runTest {
        val manager = PluginManager(this, Store())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var finished = false
        val plugin = Plugin(disable = { entered.complete(Unit); resume.await(); finished = true })
        manager.register(plugin)
        val stopping = launch { manager.setEnabled("test", false) }
        entered.await()
        stopping.cancel()
        assertFalse(manager.plugins.value.single().enabled)
        resume.complete(Unit)
        stopping.join()
        assertTrue(stopping.isCancelled)
        assertTrue(finished)
        assertEquals(1, plugin.stops)
    }

    @Test fun `failed plugin can be persistently disabled before restart`() = runTest {
        val store = Store()
        val manager = PluginManager(this, store)
        try { manager.register(Plugin(enable = { throw IOException("startup") })) } catch (_: IOException) { }
        manager.setEnabled("test", false)
        assertFalse(store.values.getValue("test").enabled)
        val restarted = PluginManager(this, store)
        restarted.register(Plugin(enable = { error("disabled plugin must not start") }))
        assertFalse(restarted.plugins.value.single().enabled)
    }

    @Test fun `nested callbacks cannot wait for an ancestor lifecycle`() = runTest {
        val manager = PluginManager(this, Store())
        val plugin = Plugin(enable = {
            manager.register(Plugin("nested", enable = { manager.unregister("test") }))
        })
        try { manager.register(plugin); fail("Expected lifecycle cycle rejection") } catch (_: IllegalStateException) { }
        assertTrue(manager.plugins.value.none { it.enabled })
    }
}
