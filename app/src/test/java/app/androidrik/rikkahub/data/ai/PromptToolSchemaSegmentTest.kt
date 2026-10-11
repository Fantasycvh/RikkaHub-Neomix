package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 工具 schema 段（拼装预览的顶层字段归因）。
 *
 * 背景（私有仓记录·前期调研 §6.12 末节）：预览此前**完全没统计**实发的
 * 顶层 `tools` 数组——实测过的场景里 MCP schema ≈33K token（占总量约 80%）在预览里是 0，
 * 这是「预览估算远低于实发」的主因。
 */
class PromptToolSchemaSegmentTest {

    private val json: Json = JsonInstant

    private val labels = PromptInspectionLabels(
        system = "System",
        checkpoint = "Checkpoint",
        injection = "Injected",
        history = "History",
        toolSchema = "Tool schema (%1\$d tools, ≈%2\$d tokens)",
    )

    private fun tool(name: String, description: String = "desc") = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                }
            )
        },
        execute = { emptyList() },
    )

    @Test
    fun `no tools yields no segment`() {
        assertNull(
            toolsToSegment(emptyList(), null, json, labels)
        )
    }

    @Test
    fun `segment carries tool count and estimated tokens in label`() {
        val tools = listOf(tool("alpha"), tool("beta"))
        val seg = toolsToSegment(tools, null, json, labels)
        assertNotNull(seg)
        // label 内嵌数量与估算，用户一眼看到「对话之外还有多少」
        assertTrue(seg!!.label.startsWith("Tool schema (2 tools, ≈"))
        assertTrue(seg.tokens > 0)
    }

    @Test
    fun `segment is marked as a top-level field not a message`() {
        val seg = toolsToSegment(listOf(tool("alpha")), null, json, labels)!!
        // 关键语义：tools 与 messages 平级，不属于任何消息——
        // 此前它被渲染成 system 块，属归因错误。
        assertEquals(PromptSegmentLocation.TOP_LEVEL_FIELD, seg.location)
        assertEquals(PromptSegmentKind.TOOL_SCHEMA, seg.kind)
    }

    @Test
    fun `tokens come from ToolSchemaSize not the char heuristic`() {
        val tools = listOf(tool("alpha"), tool("beta"))
        val seg = toolsToSegment(tools, null, json, labels)!!
        val expected = app.androidrik.ai.util.ToolSchemaSize.estimate(tools, null, json)
        // token 数必须复用 provider 形状估算（比 cjk/1.5+other/4 更准），
        // 否则「工具段的数字」与日志里的 schema 诊断对不上。
        assertEquals(expected, seg.tokens)
    }

    @Test
    fun `content is the actual wire json so the page shows what is sent`() {
        val tools = listOf(tool("alpha"))
        val seg = toolsToSegment(tools, null, json, labels)!!
        // 展示用真实下发 JSON（含 OpenAI 包装），而不是另拼一份可能漂移的文本
        assertTrue(seg.content.contains("\"type\":\"function\""))
        assertTrue(seg.content.contains("\"name\":\"alpha\""))
    }

    @Test
    fun `provider shape changes both estimate and shown json`() {
        val tools = listOf(tool("alpha"))
        val openai = toolsToSegment(tools, ProviderSetting.OpenAI(), json, labels)!!
        val claude = toolsToSegment(tools, ProviderSetting.Claude(), json, labels)!!
        // Claude 用 input_schema 键、OpenAI 用 function.parameters —— 两处必须同时跟随
        assertTrue(openai.content.contains("\"function\""))
        assertTrue(claude.content.contains("input_schema"))
        assertTrue(!claude.content.contains("\"function\""))
    }

    @Test
    fun `estimate and shown json stay consistent for the same tool`() {
        // wireJson 与 estimate 共用同一份形状变换：若两处各自拼 JSON，体积与展示会漂移。
        val t = tool("alpha")
        val shown = app.androidrik.ai.util.ToolSchemaSize.wireJson(t, null, json)
        assertEquals(shown, toolsToSegment(listOf(t), null, json, labels)!!.content)
    }
}
