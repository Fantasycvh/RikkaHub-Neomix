package app.androidrik.rikkahub.data.plugin.js

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.plugin.api.PluginCapability
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tool assembly and invocation policy, without Android or a JavaScript runtime. */
class JsPluginToolsTest {
    private val emptyArguments = JsonObject(emptyMap())
    private val requiredSchema = Json.parseToJsonElement(
        """{
            "type":"object",
            "properties":{"query":{"type":"string"}},
            "required":["query"],
            "additionalProperties":false
        }""",
    ).jsonObject

    private fun manifest(
        tool: JsToolDefinition = JsToolDefinition("lookup", "Look up a value"),
        timeoutMs: Long = 1500,
    ) = JsPluginManifest(
        id = "sample.tools",
        name = "Tools",
        target = JsTarget.NONE,
        timeoutMs = timeoutMs,
        tools = listOf(tool),
    )

    @Test
    fun `assembled tool checks current capability and cannot run after revocation`() = runBlocking {
        var granted = true
        var executions = 0
        val checkedCapabilities = mutableListOf<PluginCapability>()
        val tool = createJsPluginTools(
            pluginId = "sample.tools",
            manifest = manifest(),
            capabilityGranted = {
                checkedCapabilities += it
                granted
            },
            executeTool = { _, _ ->
                executions++
                JsonNull
            },
        ).single()

        tool.execute(emptyArguments)
        assertEquals(1, executions)
        granted = false
        val error = runCatching { tool.execute(emptyArguments) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("TOOL_REGISTER"))
        assertEquals(1, executions)
        assertEquals(List(2) { PluginCapability.TOOL_REGISTER }, checkedCapabilities)
    }

    @Test
    fun `invalid arguments are rejected before executing the runtime`() = runBlocking {
        var executions = 0
        val tool = createJsPluginTools(
            "sample.tools",
            manifest(JsToolDefinition("lookup", "Look up", requiredSchema)),
            { true },
        ) { _, _ ->
            executions++
            JsonNull
        }.single()
        val invalidArguments = listOf(
            emptyArguments,
            Json.parseToJsonElement("""{"query":42}"""),
            Json.parseToJsonElement("""{"query":"ok","extra":true}"""),
            JsonPrimitive("not an object"),
        )

        invalidArguments.forEach { arguments ->
            val error = runCatching { tool.execute(arguments) }.exceptionOrNull()
            assertTrue("Expected validation failure for $arguments, got $error", error is IllegalArgumentException)
        }
        assertEquals(0, executions)
    }

    @Test
    fun `valid arguments and local name reach executor and results remain JSON text`() = runBlocking {
        val arguments = Json.parseToJsonElement("""{"query":"hello"}""")
        val calls = mutableListOf<Pair<String, JsonElement>>()
        val values = listOf(
            Json.parseToJsonElement("""{"answer":[1,true,null],"message":"hello"}"""),
            JsonPrimitive("quoted\ntext"),
            JsonPrimitive(42),
            JsonPrimitive(true),
            JsonNull,
        )
        var nextResult = 0
        val tool = createJsPluginTools(
            "sample.tools",
            manifest(JsToolDefinition("lookup", "Look up", requiredSchema)),
            { true },
        ) { name, receivedArguments ->
            calls += name to receivedArguments
            values[nextResult++]
        }.single()

        values.forEach { value ->
            assertEquals(listOf(UIMessagePart.Text(value.toString())), tool.execute(arguments))
        }
        assertEquals(List(values.size) { "lookup" to arguments }, calls)
    }

    @Test
    fun `all external tool invocations require approval`() {
        val tool = createJsPluginTools("sample.tools", manifest(), { true }) { _, _ -> JsonNull }.single()

        listOf(emptyArguments, JsonNull, JsonPrimitive(false)).forEach {
            assertTrue(tool.needsApproval(it))
        }
    }

    @Test
    fun `two plugins sharing a declaration keep names capabilities and executors isolated`() = runBlocking {
        val manifest = manifest()
        var firstGranted = true
        var firstExecutions = 0
        var secondExecutions = 0
        val first = createJsPluginTools("plugin.first", manifest, { firstGranted }) { name, _ ->
            assertEquals("lookup", name)
            firstExecutions++
            JsonPrimitive("first")
        }.single()
        val second = createJsPluginTools("plugin.second", manifest, { true }) { name, _ ->
            assertEquals("lookup", name)
            secondExecutions++
            JsonPrimitive("second")
        }.single()

        assertEquals(pluginToolName("plugin.first", "lookup"), first.name)
        assertEquals(pluginToolName("plugin.second", "lookup"), second.name)
        assertNotEquals(first.name, second.name)
        assertEquals(listOf(UIMessagePart.Text("\"first\"")), first.execute(emptyArguments))
        firstGranted = false
        assertTrue(runCatching { first.execute(emptyArguments) }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(UIMessagePart.Text("\"second\"")), second.execute(emptyArguments))
        assertEquals(1, firstExecutions)
        assertEquals(1, secondExecutions)
    }

    @Test
    fun `tool exposes declaration description properties and required fields`() {
        val declaration = JsToolDefinition("lookup", "Look up a value", requiredSchema)
        val tool = createJsPluginTools("sample.tools", manifest(declaration), { true }) { _, _ -> JsonNull }.single()

        assertEquals(declaration.description, tool.description)
        assertEquals(
            InputSchema.Obj(requiredSchema.getValue("properties").jsonObject, listOf("query")),
            tool.parameters(),
        )
        val optionalTool = createJsPluginTools("sample.tools", manifest(), { true }) { _, _ -> JsonNull }.single()
        assertNull((optionalTool.parameters() as InputSchema.Obj).required)
    }

    @Test
    fun `execution timeout includes six second host buffer`() {
        listOf(JsPluginManifest.MIN_TIMEOUT_MS, 1500L, JsPluginManifest.MAX_TIMEOUT_MS).forEach { timeout ->
            val tool = createJsPluginTools("sample.tools", manifest(timeoutMs = timeout), { true }) { _, _ ->
                JsonNull
            }.single()

            assertEquals(timeout + 6000, tool.executionTimeoutMillis)
        }
    }

    @Test
    fun `manifest without declarations creates no tools or runtime calls`() {
        val tools = createJsPluginTools("sample.tools", manifest().copy(tools = emptyList()), {
            error("No capability check is needed during assembly")
        }) { _, _ ->
            error("No runtime invocation is needed during assembly")
        }

        assertTrue(tools.isEmpty())
    }
}
