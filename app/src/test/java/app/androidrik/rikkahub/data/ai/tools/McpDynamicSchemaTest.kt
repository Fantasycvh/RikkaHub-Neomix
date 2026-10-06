package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDynamicSchemaTest {

    private fun entry(
        name: String,
        schemaTokens: Int,
        server: String = "srv",
        paramsHint: String = "",
        description: String = "desc $name",
    ) = McpToolEntry(
        serverId = "id-$name",
        serverName = server,
        functionName = name,
        originalToolName = name,
        description = description,
        paramsHint = paramsHint,
        needsApproval = false,
        schemaTokens = schemaTokens,
        nativeTool = app.androidrik.ai.core.Tool(
            name = name,
            description = "",
            parameters = { app.androidrik.ai.core.InputSchema.Obj(properties = buildJsonObject { }) },
            execute = { emptyList() },
        ),
    )

    @Test
    fun `split keeps small tools native and collapses large ones`() {
        val entries = listOf(
            entry("small_a", 100),
            entry("small_b", 600),
            entry("big_x", 601),
            entry("huge_y", 5_000),
        )
        val split = splitMcpToolsBySchemaSize(entries, threshold = 600)
        assertEquals(listOf("small_a", "small_b"), split.native.map { it.functionName })
        assertEquals(listOf("huge_y", "big_x"), split.collapsed.map { it.functionName }) // 体积降序
    }

    @Test
    fun `params hint renders name type pairs and caps at 12`() {
        val properties = buildJsonObject {
            (1..15).forEach { i -> put("p$i", buildJsonObject { put("type", "string") }) }
        }
        val hint = buildParamsHint(buildJsonObject { put("properties", properties) })
        assertTrue(hint.startsWith("p1:string, p2:string"))
        assertEquals(12, hint.split(", ").size)
    }

    @Test
    fun `params hint tolerates missing properties`() {
        assertEquals("", buildParamsHint(null))
        assertEquals("", buildParamsHint(buildJsonObject { }))
    }

    @Test
    fun `tool map payload carries name server description hint and approval`() {
        val payload = Json.parseToJsonElement(
            buildToolMapPayload(listOf(entry("big_x", 900, server = "Devin", paramsHint = "q:string")))
        ).jsonObject
        val tool = payload.getValue("tools").jsonArray.single().jsonObject
        assertEquals("big_x", tool.getValue("name").jsonPrimitive.content)
        assertEquals("Devin", tool.getValue("server").jsonPrimitive.content)
        assertEquals("q:string", tool.getValue("params_hint").jsonPrimitive.content)
        assertEquals("false", tool.getValue("needs_approval").jsonPrimitive.content)
    }

    @Test
    fun `tool map description is truncated`() {
        val payload = Json.parseToJsonElement(
            buildToolMapPayload(listOf(entry("t", 900, description = "d".repeat(1_000))))
        ).jsonObject
        val description = payload.getValue("tools").jsonArray.single()
            .jsonObject.getValue("description").jsonPrimitive.content
        assertEquals(200, description.length)
    }

    @Test
    fun `explicitly allowed tools are promoted out of the collapsed tier`() {
        // 折叠只服务 token 预算，不能改信封语义：点名即提升（防静默失效）
        val collapsed = listOf(entry("heavy_a", 900), entry("heavy_b", 800), entry("heavy_c", 700))
        val (promoted, remaining) =
            promoteExplicitlyAllowed(collapsed, setOf("heavy_a", "heavy_c", "unrelated"))

        assertEquals(listOf("heavy_a", "heavy_c"), promoted.map { it.functionName })
        assertEquals(listOf("heavy_b"), remaining.map { it.functionName })
    }

    @Test
    fun `nothing is promoted without explicit names`() {
        val collapsed = listOf(entry("heavy_a", 900))
        val (promoted, remaining) = promoteExplicitlyAllowed(collapsed, emptySet())
        assertTrue(promoted.isEmpty())
        assertEquals(1, remaining.size)
    }

    @Test
    fun `resolve finds by sanitized function name`() {
        val collapsed = listOf(entry("srv_search_web", 900))
        assertEquals("srv_search_web", resolveCollapsedEntry(collapsed, "srv_search_web")!!.functionName)
        assertNull(resolveCollapsedEntry(collapsed, "nope"))
    }
}
