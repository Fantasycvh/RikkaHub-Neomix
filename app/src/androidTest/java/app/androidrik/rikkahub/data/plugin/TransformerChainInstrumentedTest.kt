package app.androidrik.rikkahub.data.plugin

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginState
import app.androidrik.plugin.manager.PluginStateStore
import app.androidrik.rikkahub.data.ai.transformers.*
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.plugin.js.*
import app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway
import app.androidrik.rikkahub.data.plugin.rules.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Application, temporary plugin storage, no provider credentials or external network. */
@RunWith(AndroidJUnit4::class)
class TransformerChainInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = Model()
    private val assistant = Assistant()
    private val settings = Settings.dummy()

    @Test fun previewAndWarmupDoNotExecuteJsButRequestAndFinishStillDo() = runBlocking {
        val root = File(context.cacheDir, "plugin-chain-${UUID.randomUUID()}")
        val store = PluginKeyValueStore(root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope, object : PluginStateStore {
            override suspend fun read() = emptyMap<String, PluginState>()
            override suspend fun write(states: Map<String, PluginState>) {}
        })
        val id = "instrument.chain"
        lateinit var plugin: JsPlugin
        plugin = JsPlugin(
            context = context,
            definition = JsPluginDefinition(
                JsPluginManifest(id, "脚本试点", target = JsTarget.BOTH, storage = true, timeoutMs = 5000),
                """
                    async function record(text, suffix) {
                        await rikka.store.set('calls', (await rikka.store.get('calls') || 0) + 1);
                        return text + suffix;
                    }
                    async function onInput({text}) { return record(text, ':input'); }
                    async function onOutput({text}) { return record(text, ':output'); }
                """.trimIndent(),
            ),
            gateway = PluginHttpGateway(OkHttpClient()),
            networkGranted = { false },
            keyValueStore = store,
            capabilityGranted = { capability -> manager.plugins.value.any {
                it.plugin === plugin && it.enabled && it.hasCapability(capability)
            } },
        )
        try {
            manager.register(plugin)
            val registry = TransformerChainRegistry(emptyList(), emptyList())
            val user = listOf(UIMessage.user("hello"))
            for (use in listOf(TransformerUse.PREVIEW, TransformerUse.WARMUP)) {
                val chain = registry.snapshot(manager.plugins.value, use)
                assertEquals("hello", user.transforms(chain.inputTransformers, context, model, assistant, settings).single().toText())
                assertNull(store.get(id, "calls"))
            }
            val request = registry.snapshot(manager.plugins.value)
            assertEquals("hello:input", user.transforms(request.inputTransformers, context, model, assistant, settings).single().toText())
            assertEquals(JsonPrimitive(1), store.get(id, "calls"))
            val reply = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))))
            val visual = reply.visualTransforms(request.outputTransformers, context, model, assistant, settings)
            assertEquals("answer", visual.single().toText())
            assertEquals(JsonPrimitive(1), store.get(id, "calls"))
            val finished = reply.onGenerationFinish(request.outputTransformers, context, model, assistant, settings)
            assertEquals("answer:output", finished.single().toText())
            assertEquals(JsonPrimitive(2), store.get(id, "calls"))
        } finally {
            manager.unregister(id)
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun jsonRulesRemainPreviewCompatibleAndRevocationRemovesThem() = runBlocking {
        val plugin = JsonRulePlugin(JsonRuleDefinition(
            id = "instrument.rules", name = "规则试点", target = RuleTarget.INPUT,
            rules = listOf(JsonRule(
                RuleCondition(field = "text", op = RuleOp.CONTAINS, value = JsonPrimitive("hello")),
                RuleAction(type = RuleActionType.APPEND, text = ":rule"),
            )),
        ))
        val info = PluginInfo(plugin, true, plugin.capabilities)
        val registry = TransformerChainRegistry(emptyList(), emptyList())
        val messages = listOf(UIMessage.user("hello"))
        for (use in listOf(TransformerUse.REQUEST, TransformerUse.PREVIEW)) {
            val chain = registry.snapshot(listOf(info), use)
            assertEquals("hello\n:rule", messages.transforms(chain.inputTransformers, context, model, assistant, settings).single().toText())
        }
        assertFalse(registry.snapshot(listOf(info.copy(grants = emptySet()))).inputTransformers.contains(plugin))
        assertFalse(registry.snapshot(listOf(info.copy(enabled = false))).inputTransformers.contains(plugin))
    }

    @Test fun assemblyBenchmarkUsesSameDeviceSamplesAndExecutesNoHooks() {
        val noop = object : InputMessageTransformer {}
        val legacy = defaultTransformerChainRegistry(noop, noop, false)
        val registered = defaultTransformerChainRegistry(noop, noop, true)
        val plugins = (0 until 25).map { index ->
            val plugin = JsonRulePlugin(JsonRuleDefinition("instrument.$index", "$index", target = RuleTarget.INPUT))
            PluginInfo(plugin, true, plugin.capabilities)
        }
        assertEquals(legacy.snapshot(plugins).inputTransformers, registered.snapshot(plugins).inputTransformers)
        repeat(200) { legacy.snapshot(plugins); registered.snapshot(plugins) }
        val oldTimes = LongArray(500)
        val newTimes = LongArray(500)
        fun measure(registry: TransformerChainRegistry): Long {
            val start = SystemClock.elapsedRealtimeNanos()
            registry.snapshot(plugins)
            return SystemClock.elapsedRealtimeNanos() - start
        }
        repeat(500) { index ->
            if (index % 2 == 0) { oldTimes[index] = measure(legacy); newTimes[index] = measure(registered) }
            else { newTimes[index] = measure(registered); oldTimes[index] = measure(legacy) }
        }
        oldTimes.sort(); newTimes.sort()
        Log.i("PluginP1cPerf", "samples=500 plugins=25 legacyMedianNs=${oldTimes[250]} legacyP95Ns=${oldTimes[474]} registeredMedianNs=${newTimes[250]} registeredP95Ns=${newTimes[474]}")
        // Generous device-noise budget fixed before measurement; not a first-token latency claim.
        assertTrue("assembly p95 exceeded max(4x legacy, 2ms)", newTimes[474] <= maxOf(oldTimes[474] * 4, 2_000_000))
    }

    @Test fun cancellingWebViewHookAllowsFreshEvaluationAndClosedRuntimeCannotRestart() = runBlocking {
        val root = File(context.cacheDir, "plugin-cancel-${UUID.randomUUID()}")
        val store = PluginKeyValueStore(root)
        val id = "instrument.cancel"
        val runtime = JsPluginRuntime(context, JsPluginDefinition(
            JsPluginManifest(id, id, target = JsTarget.INPUT, storage = true, timeoutMs = 10000),
            """
                async function onInput({text}) {
                    if (text === 'hang') {
                        await rikka.store.set('started', true);
                        await new Promise(() => {});
                    }
                    return text;
                }
            """.trimIndent(),
        ), PluginHttpGateway(OkHttpClient()), { false }, store, { true })
        val pending = launch { runtime.runHook(JsHook.ON_INPUT, buildJsonObject { put("text", "hang") }) }
        try {
            withTimeout(8000) { while (store.get(id, "started") != JsonPrimitive(true)) delay(20) }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals("fresh", runtime.runHook(JsHook.ON_INPUT, buildJsonObject { put("text", "fresh") }).getOrThrow())
            runtime.close()
            assertTrue(runtime.runHook(JsHook.ON_INPUT, buildJsonObject { put("text", "closed") }).isFailure)
        } finally {
            pending.cancelAndJoin()
            runtime.close()
            root.deleteRecursively()
        }
    }
}
