package app.androidrik.rikkahub.data.plugin.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsToolDefinitionTest {
    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun rejects(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `defaults are serializable empty object parameters`() {
        val tool = Json.decodeFromString<JsToolDefinition>("""{"name":"lookup","description":"Lookup"}""")
        assertTrue(tool.validate().isEmpty())
        validateToolArguments(tool.parameters, schema("{}"))
        assertEquals(tool, Json.decodeFromString<JsToolDefinition>(Json.encodeToString(JsToolDefinition.serializer(), tool)))
    }

    @Test
    fun `tool names and descriptions are bounded`() {
        for (name in listOf("", "A", "_a", "a-b", "a.b", "a".repeat(33), "测试")) {
            assertFalse(JsToolDefinition(name, "description").validate().isEmpty())
        }
        assertTrue(JsToolDefinition("a".repeat(32), "d".repeat(1000)).validate().isEmpty())
        assertFalse(JsToolDefinition("a", " ").validate().isEmpty())
        assertFalse(JsToolDefinition("a", "d".repeat(1001)).validate().isEmpty())
    }

    @Test
    fun `provider names are stable isolated safe and hash plugin ids`() {
        val name = pluginToolName("plugin.one", "a".repeat(32))
        assertEquals(name, pluginToolName("plugin.one", "a".repeat(32)))
        assertNotEquals(name, pluginToolName("plugin.two", "a".repeat(32)))
        assertTrue(name.length <= 64)
        assertTrue(Regex("^rikka_[a-f0-9]{16}_[a-z][a-z0-9_]*$").matches(name))
        assertEquals("rikka_e3b0c44298fc1c14_lookup", pluginToolName("", "lookup"))
        rejects { pluginToolName("p", "unsafe-name") }
    }

    private val nested = schema("""{
        "type":"object","properties":{
            "name":{"type":"string","enum":["one","two"]},
            "count":{"type":"integer"},"ratio":{"type":"number"},
            "enabled":{"type":"boolean"},"nothing":{"type":"null"},
            "rows":{"type":"array","items":{"type":"object","properties":{
                "value":{"type":"string"}},"required":["value"],"additionalProperties":false}}
        },"required":["name","rows"],"additionalProperties":false
    }""")

    @Test
    fun `arguments validate basic types nested arrays required enums and extra keys`() {
        validateToolArguments(nested, schema("""{"name":"one","rows":[{"value":"ok"}],"count":1.0,
            "ratio":2.5,"enabled":true,"nothing":null}"""))
        for (value in listOf(
            """{"name":"one"}""", """{"name":"three","rows":[]}""",
            """{"name":1,"rows":[]}""", """{"name":"one","rows":[{}]}""",
            """{"name":"one","rows":[{"value":"x","extra":1}]}""",
            """{"name":"one","rows":[],"count":1.2}""",
            """{"name":"one","rows":[],"ratio":"2"}""",
            """{"name":"one","rows":[],"enabled":"true"}""",
            """{"name":"one","rows":[],"nothing":false}""",
            """{"name":"one","rows":[],"extra":1}""", "[]", "null"
        )) rejects { validateToolArguments(nested, Json.parseToJsonElement(value)) }
    }

    @Test
    fun `unsupported and malformed schemas fail closed`() {
        for (text in listOf(
            """{"type":"array","items":{"type":"string"}}""",
            """{"type":"object"}""",
            """{"type":"object","properties":{},"required":[1]}""",
            """{"type":"object","properties":{},"required":["missing"]}""",
            """{"type":"object","properties":{},"additionalProperties":{}}""",
            """{"type":"object","properties":{},"${'$'}ref":"x"}""",
            """{"type":"object","properties":{"x":{"type":"string","minLength":1}}}""",
            """{"type":"object","properties":{"x":{"type":"array"}}}""",
            """{"type":"object","properties":{"x":{"type":["null","string"]}}}""",
            """{"type":"object","properties":{"x":{"type":"string","enum":[1]}}}"""
        )) {
            val tool = JsToolDefinition("test", "test", schema(text))
            assertFalse(text, tool.validate().isEmpty())
            rejects { validateToolArguments(tool.parameters, schema("{}")) }
        }
    }

    @Test
    fun `additional properties default to allowed and may be explicitly allowed`() {
        val arguments = schema("""{"extra":[1,true,null]}""")
        validateToolArguments(JsToolDefinition("test", "test").parameters, arguments)
        validateToolArguments(schema("""{"type":"object","properties":{},"additionalProperties":true}"""), arguments)
        rejects { validateToolArguments(nested, schema("""{"name":"one","rows":{},"count":true}""")) }
    }

    @Test
    fun `schema size depth and argument depth are bounded`() {
        val huge = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("description", "界".repeat(6000))
        }
        assertFalse(JsToolDefinition("test", "test", huge).validate().isEmpty())
        var deep: JsonObject = schema("""{"type":"string"}""")
        repeat(40) { deep = JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to deep)) }
        val root = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(mapOf("x" to deep))))
        assertFalse(JsToolDefinition("test", "test", root).validate().isEmpty())
        var arguments: JsonElement = JsonPrimitive("x")
        repeat(40) { arguments = JsonObject(mapOf("x" to arguments)) }
        rejects { validateToolArguments(JsToolDefinition("test", "test").parameters, arguments) }
    }
}
