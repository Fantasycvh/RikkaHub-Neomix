package app.androidrik.rikkahub.data.plugin.js

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real WebView bridge tests: no provider credentials or external network required. */
@RunWith(AndroidJUnit4::class)
class JsPluginRuntimeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun definition(id: String, source: String, storage: Boolean = true) = JsPluginDefinition(
        manifest = JsPluginManifest(id = id, name = id, storage = storage, timeoutMs = 5000),
        source = source,
    )

    @Test
    fun storageSurvivesFreshWebViewsAndRemainsPluginPrivate() = runBlocking {
        val root = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}")
        val store = PluginKeyValueStore(root)
        val source = """
            async function onOutput({text}) {
                const n = (await rikka.store.get('count') || 0) + 1;
                await rikka.store.set('count', n);
                return text + ':' + n;
            }
        """.trimIndent()
        fun runtime(id: String) = JsPluginRuntime(context, definition(id, source),
            PluginHttpGateway(OkHttpClient()), { false }, store, { true })
        val a = runtime("instrument.a")
        val b = runtime("instrument.b")
        try {
            val payload = buildJsonObject { put("text", "hello") }
            assertEquals("hello:1", a.runHook(JsHook.ON_OUTPUT, payload).getOrThrow())
            assertEquals("hello:2", a.runHook(JsHook.ON_OUTPUT, payload).getOrThrow())
            assertEquals("hello:1", b.runHook(JsHook.ON_OUTPUT, payload).getOrThrow())
            assertEquals(JsonPrimitive(2), PluginKeyValueStore(root).get("instrument.a", "count"))
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test
    fun storageRevocationRejectsAndPreservesSavedValue() = runBlocking {
        val root = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}")
        val store = PluginKeyValueStore(root)
        var granted = true
        val runtime = JsPluginRuntime(context, definition("instrument.revoke", """
            async function onOutput() {
                await rikka.store.set('value', 7);
                return 'saved';
            }
        """), PluginHttpGateway(OkHttpClient()), { false }, store, { granted })
        try {
            assertEquals("saved", runtime.runHook(JsHook.ON_OUTPUT, buildJsonObject {}).getOrThrow())
            granted = false
            val failure = runtime.runHook(JsHook.ON_OUTPUT, buildJsonObject {})
            assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("STORAGE"))
            assertEquals(JsonPrimitive(7), store.get("instrument.revoke", "value"))
            granted = true
            assertEquals("saved", runtime.runHook(JsHook.ON_OUTPUT, buildJsonObject {}).getOrThrow())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun toolReturnsJsonAndSupportsAllStoreOperations() = runBlocking {
        val root = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}")
        val runtime = JsPluginRuntime(context, definition("instrument.tool", """
            async function onTool({name, arguments: args}) {
                await rikka.store.set('a', args);
                await rikka.store.set('nullable', null);
                const value = await rikka.store.get('a');
                const keys = await rikka.store.keys();
                await rikka.store.remove('a');
                const missing = await rikka.store.get('a');
                await rikka.store.clear();
                return {name, value, missing, keys, after: await rikka.store.keys()};
            }
        """), PluginHttpGateway(OkHttpClient()), { false }, PluginKeyValueStore(root), { true })
        try {
            val result = runtime.runTool("echo", buildJsonObject { put("x", 1) }).getOrThrow()
            assertEquals(Json.parseToJsonElement("""{"name":"echo","value":{"x":1},"missing":null,"keys":["a","nullable"],"after":[]}"""), result)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun sourceIsSafelyEmbeddedAndLessThanOperatorStillWorks() = runBlocking {
        val root = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}")
        val runtime = JsPluginRuntime(context, definition("instrument.escape", """
            function onOutput({text}) { return 1 < 2 ? text + '</script>' : 'bad'; }
        """, storage = false), PluginHttpGateway(OkHttpClient()), { false }, PluginKeyValueStore(root), { false })
        try {
            assertEquals("ok</script>", runtime.runHook(JsHook.ON_OUTPUT, buildJsonObject { put("text", "ok") }).getOrThrow())
            assertTrue(runtime.runTool("missing", JsonNull).isFailure)
        } finally { runtime.close(); root.deleteRecursively() }
    }
}
