package app.androidrik.rikkahub.data.plugin.js

import app.androidrik.plugin.api.PluginCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 插件清单解析/校验的 JVM 单测(不涉及 WebView)。
 *
 * 沙箱行为(超时、网络拒绝)靠真机验证;这里锁住"坏插件进不来、能力推导不错"。
 */
class JsPluginManifestTest {

    private fun source(manifestJson: String, body: String = "function onOutput({ text }) { return text; }") =
        "/* @rikka-plugin\n$manifestJson\n*/\n$body"

    @Test
    fun `valid output plugin parses and derives output capability`() {
        val result = JsPluginManifest.parse(
            source(
                """{"id":"sample.upper","name":"大写","target":"output"}""",
            ),
        )
        val definition = result.getOrThrow()
        assertEquals("sample.upper", definition.id)
        assertEquals(setOf(JsHook.ON_OUTPUT), definition.manifest.hooks)
        assertEquals(setOf(PluginCapability.OUTPUT_TRANSFORM), definition.manifest.capabilities)
        assertTrue(definition.source.contains("onOutput"))
    }

    @Test
    fun `both target derives both transform capabilities`() {
        val definition = JsPluginManifest.parse(
            source("""{"id":"sample.both","name":"双向","target":"both"}"""),
        ).getOrThrow()
        assertEquals(
            setOf(PluginCapability.PROMPT_TRANSFORM, PluginCapability.OUTPUT_TRANSFORM),
            definition.manifest.capabilities,
        )
    }

    @Test
    fun `network hosts add network capability`() {
        val definition = JsPluginManifest.parse(
            source(
                """{"id":"sample.net","name":"网络","target":"output","networkHosts":["api.github.com","*.example.com"]}""",
            ),
        ).getOrThrow()
        assertEquals(
            setOf(PluginCapability.OUTPUT_TRANSFORM, PluginCapability.NETWORK),
            definition.manifest.capabilities,
        )
    }

    @Test
    fun `missing manifest header is rejected with readable message`() {
        val result = JsPluginManifest.parse("function onOutput({text}) { return text }")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("清单头"))
    }

    @Test
    fun `reserved prefix and bad id are rejected`() {
        val reserved = JsPluginManifest.parse(
            source("""{"id":"builtin.foo","name":"x","target":"output"}"""),
        )
        assertTrue(reserved.isFailure)
        assertTrue(reserved.exceptionOrNull()?.message.orEmpty().contains("保留前缀"))

        val badId = JsPluginManifest.parse(
            source("""{"id":"Bad Id","name":"x","target":"output"}"""),
        )
        assertTrue(badId.isFailure)
    }

    @Test
    fun `bad network host and out of range timeout are rejected`() {
        val badHost = JsPluginManifest.parse(
            source("""{"id":"sample.h","name":"x","target":"output","networkHosts":["https://api.example.com/x"]}"""),
        )
        assertTrue(badHost.isFailure)
        assertTrue(badHost.exceptionOrNull()?.message.orEmpty().contains("networkHosts"))

        val badTimeout = JsPluginManifest.parse(
            source("""{"id":"sample.t","name":"x","target":"output","timeoutMs":60000}"""),
        )
        assertTrue(badTimeout.isFailure)
        assertTrue(badTimeout.exceptionOrNull()?.message.orEmpty().contains("timeoutMs"))
    }

    @Test
    fun `legacy target and capability defaults remain output only`() {
        val manifest = JsPluginManifest.parse(source("""{"id":"legacy","name":"Legacy"}""")).getOrThrow().manifest
        assertEquals(JsTarget.OUTPUT, manifest.target)
        assertEquals(emptyList<JsToolDefinition>(), manifest.tools)
        assertFalse(manifest.storage)
        assertEquals(setOf(PluginCapability.OUTPUT_TRANSFORM), manifest.capabilities)
    }

    @Test
    fun `tool only manifest has no transform hooks and derives explicit capabilities`() {
        val manifest = JsPluginManifest.parse(source("""{
            "id":"tools.only","name":"Tools","target":"none","storage":true,
            "tools":[{"name":"lookup","description":"Look up a value"}]
        }""")).getOrThrow().manifest
        assertTrue(manifest.hooks.isEmpty())
        assertEquals(setOf(PluginCapability.STORAGE, PluginCapability.TOOL_REGISTER), manifest.capabilities)
        assertTrue(manifest.tools.single().validate().isEmpty())
    }

    @Test
    fun `none requires tools even with storage and network`() {
        assertTrue(JsPluginManifest("x", "x", target = JsTarget.NONE, storage = true,
            networkHosts = listOf("example.com")).validate().isNotEmpty())
    }

    @Test
    fun `tool count duplicates and invalid definitions are rejected`() {
        val tool = JsToolDefinition("lookup", "Look up")
        assertTrue(JsPluginManifest("x", "x", tools = List(17) { tool.copy(name = "tool_$it") }).validate().isNotEmpty())
        assertTrue(JsPluginManifest("x", "x", tools = listOf(tool, tool)).validate().isNotEmpty())
        assertTrue(JsPluginManifest("x", "x", tools = listOf(tool.copy(name = "Bad"))).validate().isNotEmpty())
        assertTrue(JsPluginManifest("x", "x", tools = List(16) { tool.copy(name = "tool_$it") }).validate().isEmpty())
        assertTrue(JsPluginManifest("x", "x", storage = true).capabilities.contains(PluginCapability.STORAGE))
    }

    @Test
    fun `oversized source is rejected`() {
        val huge = "x".repeat(JsPluginManifest.MAX_SOURCE_BYTES + 1)
        val result = JsPluginManifest.parse(
            "/* @rikka-plugin\n{\"id\":\"sample.big\",\"name\":\"x\"}\n*/\n$huge",
        )
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().isBlank())
    }
}
