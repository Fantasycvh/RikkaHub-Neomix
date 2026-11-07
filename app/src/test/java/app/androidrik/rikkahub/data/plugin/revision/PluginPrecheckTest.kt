package app.androidrik.rikkahub.data.plugin.revision

import app.androidrik.rikkahub.data.plugin.js.JsPluginDefinition
import app.androidrik.rikkahub.data.plugin.js.JsPluginManifest
import app.androidrik.rikkahub.data.plugin.js.JsTarget
import app.androidrik.rikkahub.data.plugin.js.JsToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun manifest(
    id: String = "sample.precheck",
    tools: List<JsToolDefinition> = emptyList(),
) = JsPluginManifest(
    id = id,
    name = "预检测试",
    target = if (tools.isEmpty()) JsTarget.OUTPUT else JsTarget.NONE,
    tools = tools,
)

private fun definition(
    source: String = "function onOutput(p) { return p; }",
    manifest: JsPluginManifest = manifest(),
) = JsPluginDefinition(manifest = manifest, source = source)

class PluginPrecheckTest {

    private fun precheck(
        syntaxError: String? = null,
        existingToolNames: Set<String> = emptySet(),
    ) = PluginPrecheck(
        syntaxChecker = { syntaxError },
        existingToolNames = { existingToolNames },
    )

    @Test
    fun `healthy plugin passes all checks`() = kotlinx.coroutines.runBlocking {
        val report = precheck().checkJs(definition())
        assertTrue(report.passed)
        assertTrue(report.items.all { it.passed })
    }

    @Test
    fun `manifest failure is reported and blocks activation`() = kotlinx.coroutines.runBlocking {
        val bad = definition(manifest = manifest(id = "BAD-ID"))
        val report = precheck().checkJs(bad)
        assertFalse(report.passed)
        val item = report.items.first { it.name == "manifest" }
        assertFalse(item.passed)
        // 预检失败文本可定位(面向用户的 failureText 非空)
        assertTrue(report.failureText().contains("manifest"))
    }

    @Test
    fun `syntax failure surfaces checker error`() = kotlinx.coroutines.runBlocking {
        val report = precheck(syntaxError = "Unexpected token }").checkJs(definition())
        assertFalse(report.passed)
        assertEquals("Unexpected token }", report.items.first { it.name == "syntax" }.detail)
    }

    @Test
    fun `intra-plugin duplicate model-visible names fail the gate`() = kotlinx.coroutines.runBlocking {
        // 不同声明名 + 特意构造的 pluginToolName 冲突极难(带 hash);直接用同声明名会被
        // manifest.validate 拦下,这里验证的是「闸门对最终名再查一遍」这层兜底确实在工作:
        // 同一声明名两次 → manifest 项已失败,同时闸门也应报重名
        val dup = manifest(
            tools = listOf(
                JsToolDefinition(
                    name = "note",
                    description = "d",
                    parameters = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) },
                ),
                JsToolDefinition(
                    name = "note",
                    description = "d",
                    parameters = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) },
                ),
            ),
        )
        val report = precheck().checkJs(definition(manifest = dup))
        assertFalse(report.passed)
        assertFalse(report.items.first { it.name == "tool-gate" }.passed)
    }

    @Test
    fun `tool profile is included in report`() = kotlinx.coroutines.runBlocking {
        val withTool = manifest(
            tools = listOf(
                JsToolDefinition(
                    name = "note",
                    description = "d",
                    parameters = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) },
                ),
            ),
        )
        val report = precheck().checkJs(definition(manifest = withTool))
        assertTrue(report.passed)
        assertTrue(report.profile.contains("plugin:sample.precheck 1tools"))
        // 预检从 manifest 静态构造工具（无审批闭包信息）→ 计 0 dyn；真实闭包在运行时装配
        assertTrue(report.profile.contains("appr=plugin-defined(0/1dyn)"))
    }
}
