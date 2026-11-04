package app.androidrik.rikkahub.data.ai.transformers

import android.app.Application
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginState
import app.androidrik.plugin.manager.PluginStateStore
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.plugin.PluginInputTransformer
import app.androidrik.rikkahub.data.plugin.PluginOutputTransformer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TransformerChainRegistryTest {
    private val template = object : InputMessageTransformer {}
    private val workspace = object : InputMessageTransformer {}

    private open class Plugin(override val id: String) : PluginInputTransformer, PluginOutputTransformer {
        override val displayName = id
        override val description = ""
        override val version = "1"
        override val capabilities = setOf(PluginCapability.PROMPT_TRANSFORM, PluginCapability.OUTPUT_TRANSFORM)
    }

    private fun info(plugin: Plugin, enabled: Boolean = true, grants: Set<PluginCapability> = plugin.capabilities) =
        PluginInfo(plugin, enabled, grants)

    @Test fun `default chains preserve the pre-refactor object order including plugin registration order`() {
        val z = Plugin("z")
        val a = Plugin("a")
        val plugins = listOf(info(z), info(a))
        val expectedInput = listOf(
            TimeReminderTransformer, PromptInjectionTransformer, TavernMacroTransformer, PlaceholderTransformer,
            DocumentAsPromptTransformer, OcrTransformer, QuoteTransformer, template, workspace, z, a,
            ToolResultBalancingTransformer,
        )
        val expectedOutput = listOf(ThinkTagTransformer, Base64ImageToLocalFileTransformer, RegexOutputTransformer, z, a)
        for (registered in listOf(false, true)) {
            val chain = defaultTransformerChainRegistry(template, workspace, registered).snapshot(plugins)
            assertEquals(expectedInput, chain.inputTransformers)
            assertEquals(expectedOutput, chain.outputTransformers)
            assertEquals("builtin.time_reminder", chain.inputs.first().id)
            assertEquals(TransformerStage.FINALIZER, chain.inputs.last().stage)
        }
        assertEquals(expectedOutput.take(3), defaultOutputTransformers)
    }

    @Test fun `preview and warmup omit unreviewed plugins OCR and workspace probing`() {
        val registry = defaultTransformerChainRegistry(template, workspace)
        val plugin = Plugin("effects")
        val plugins = listOf(info(plugin))
        val request = registry.snapshot(plugins)
        val preview = registry.snapshot(plugins, TransformerUse.PREVIEW)
        val warmup = registry.snapshot(plugins, TransformerUse.WARMUP)
        assertEquals(request.inputTransformers.filterNot {
            it === plugin || it === OcrTransformer || it === workspace
        }, preview.inputTransformers)
        assertEquals(preview.inputs, warmup.inputs)
        assertTrue(preview.outputs.isEmpty())
        assertTrue(warmup.outputs.isEmpty())
        assertEquals(listOf("builtin.ocr", "builtin.workspace_reminder", "plugin:effects:input"), preview.omittedInputs.map { it.id })
        assertSame(ToolResultBalancingTransformer, warmup.inputTransformers.last())
    }

    @Test fun `new snapshots reflect capability revocation disable and unregister without mutating old snapshots`() = runTest {
        val store = object : PluginStateStore {
            override suspend fun read() = emptyMap<String, PluginState>()
            override suspend fun write(states: Map<String, PluginState>) {}
        }
        val manager = PluginManager(this, store)
        val registry = defaultTransformerChainRegistry(template, workspace)
        val plugin = Plugin("live")
        val handle = manager.register(plugin)
        val original = registry.snapshot(manager.plugins.value)
        manager.setCapabilityApproved(plugin.id, PluginCapability.PROMPT_TRANSFORM, false)
        val revoked = registry.snapshot(manager.plugins.value)
        assertFalse(revoked.inputTransformers.contains(plugin))
        assertTrue(revoked.outputTransformers.contains(plugin))
        assertTrue(original.inputTransformers.contains(plugin))
        manager.setEnabled(plugin.id, false)
        val disabled = registry.snapshot(manager.plugins.value)
        assertFalse(disabled.inputTransformers.contains(plugin))
        assertFalse(disabled.outputTransformers.contains(plugin))
        handle.unregister()
        val removed = registry.snapshot(manager.plugins.value)
        assertEquals(disabled.inputs, removed.inputs)
        assertEquals(disabled.outputs, removed.outputs)
    }

    @Test fun `host stages sort stably and added registrations cannot move protocol finalizer`() {
        val first = object : InputMessageTransformer {}
        val second = object : InputMessageTransformer {}
        val registry = TransformerChainRegistry(listOf(
            TransformerContribution("workspace", TransformerStage.WORKSPACE, workspace),
            TransformerContribution("first", TransformerStage.CORE, first),
            TransformerContribution("template", TransformerStage.TEMPLATE, template),
            TransformerContribution("second", TransformerStage.CORE, second),
        ))
        val plugin = Plugin("attempt.finalizer")
        val result = registry.snapshot(listOf(info(plugin)))
        assertEquals(listOf(first, second, template, workspace, plugin, ToolResultBalancingTransformer), result.inputTransformers)
        assertEquals("plugin:attempt.finalizer:input", result.inputs[result.inputs.lastIndex - 1].id)
    }

    @Test fun `reserved stages duplicate ids and finalizer id collision fail deterministically`() {
        for (stage in listOf(TransformerStage.PLUGIN, TransformerStage.FINALIZER)) {
            assertThrows(IllegalArgumentException::class.java) {
                TransformerChainRegistry(listOf(TransformerContribution("reserved", stage, template)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransformerChainRegistry(listOf(
                TransformerContribution("same", TransformerStage.CORE, template),
                TransformerContribution("same", TransformerStage.TEMPLATE, workspace),
            ))
        }
        val registry = TransformerChainRegistry(listOf(
            TransformerContribution("builtin.tool_result_balancing", TransformerStage.CORE, template),
        ))
        assertThrows(IllegalArgumentException::class.java) { registry.snapshot(emptyList()) }
    }

    @Test fun `building snapshots executes no hooks and warmup eligibility is explicit`() {
        val neverExecute = object : InputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
                error("assembly must not execute transformers")
        }
        val registry = TransformerChainRegistry(listOf(
            TransformerContribution("side_effect", TransformerStage.CORE, neverExecute, allowWarmup = false),
        ))
        assertSame(neverExecute, registry.snapshot(emptyList()).inputTransformers.first())
        assertEquals(listOf(ToolResultBalancingTransformer), registry.snapshot(emptyList(), TransformerUse.WARMUP).inputTransformers)
    }

    @Test fun `time reminder registration preserves execution and volatile annotations`() = runTest {
        val registry = TransformerChainRegistry(listOf(
            TransformerContribution("builtin.time_reminder", TransformerStage.CORE, TimeReminderTransformer),
        ))
        val messages = listOf(UIMessage.user("ordinary input"))
        val context = Application()
        val model = Model()
        val assistant = Assistant(enableTimeReminder = true)
        val settings = Settings.dummy()
        // These are pure transforms: no model, network, OCR, or storage is invoked for comparison.
        val old = messages.transformsCollectingVolatileSpans(
            listOf(TimeReminderTransformer, ToolResultBalancingTransformer), context, model, assistant, settings,
        )
        val registered = messages.transformsCollectingVolatileSpans(
            registry.snapshot(emptyList()).inputTransformers, context, model, assistant, settings,
        )
        assertEquals(old.messages.map { it.role to it.toText() }, registered.messages.map { it.role to it.toText() })
        assertEquals(old.volatileSpans, registered.volatileSpans)
        assertTrue(registered.messages.first().isSynthetic)
        assertEquals(MessageRole.USER, registered.messages.last().role)
        assertEquals(messages.single().id, registered.messages.last().id)
    }

    @Test fun `output storage visual and finish hooks keep separate ordered execution`() = runTest {
        val trace = mutableListOf<String>()
        fun recorder(name: String) = object : OutputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                trace += "$name.store"; return messages
            }
            override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                trace += "$name.visual"; return messages
            }
            override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                trace += "$name.finish"; return messages
            }
        }
        val first = recorder("first")
        val second = recorder("second")
        val plugin = object : Plugin("output") {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                trace += "plugin.store"; return messages
            }
            override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                trace += "plugin.visual"; return messages
            }
            override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                trace += "plugin.finish"; return messages
            }
        }
        val registry = TransformerChainRegistry(emptyList(), listOf(
            TransformerContribution("first", TransformerStage.CORE, first),
            TransformerContribution("second", TransformerStage.CORE, second),
        ))
        val chain = registry.snapshot(listOf(info(plugin))).outputTransformers
        val context = Application()
        val model = Model()
        val assistant = Assistant()
        val settings = Settings.dummy()
        val messages = listOf(UIMessage.user("unchanged"))
        messages.transforms(chain, context, model, assistant, settings)
        messages.visualTransforms(chain, context, model, assistant, settings)
        messages.onGenerationFinish(chain, context, model, assistant, settings)
        assertEquals(listOf(
            "first.store", "second.store", "plugin.store",
            "first.visual", "second.visual", "plugin.visual",
            "first.finish", "second.finish", "plugin.finish",
        ), trace)
    }

    @Test fun `only host reviewed rule plugins can join preview and cannot join warmup`() {
        val rule = app.androidrik.rikkahub.data.plugin.rules.JsonRulePlugin(
            app.androidrik.rikkahub.data.plugin.rules.JsonRuleDefinition(
                id = "rules.preview", name = "规则", target = app.androidrik.rikkahub.data.plugin.rules.RuleTarget.INPUT,
            ),
        )
        val registry = defaultTransformerChainRegistry(template, workspace)
        val plugins = listOf(PluginInfo(rule, true, rule.capabilities))
        assertTrue(registry.snapshot(plugins, TransformerUse.PREVIEW).inputTransformers.contains(rule))
        assertFalse(registry.snapshot(plugins, TransformerUse.WARMUP).inputTransformers.contains(rule))
        assertFalse(registry.snapshot(plugins, TransformerUse.PREVIEW).omittedInputs.any { it.id.contains("rules.preview") })
    }

    @Test fun `omission relevance distinguishes text vision local image and bound workspace`() {
        val text = listOf(UIMessage.user("text"))
        val image = listOf(UIMessage(role = MessageRole.USER, parts = listOf(app.androidrik.ai.ui.UIMessagePart.Image("file:///test.png"))))
        val ocr = TransformerOmission("builtin.ocr", "OCR")
        val textModel = Model(inputModalities = listOf(app.androidrik.ai.provider.Modality.TEXT))
        assertFalse(ocr.appliesTo(textModel, Assistant(), text))
        assertTrue(ocr.appliesTo(textModel, Assistant(), image))
        assertFalse(ocr.appliesTo(Model(inputModalities = listOf(app.androidrik.ai.provider.Modality.IMAGE)), Assistant(), image))
        val workspaceOmission = TransformerOmission("builtin.workspace_reminder", "Workspace")
        assertFalse(workspaceOmission.appliesTo(textModel, Assistant(), text))
        assertTrue(workspaceOmission.appliesTo(textModel, Assistant(workspaceId = kotlin.uuid.Uuid.random()), text))
    }
}
