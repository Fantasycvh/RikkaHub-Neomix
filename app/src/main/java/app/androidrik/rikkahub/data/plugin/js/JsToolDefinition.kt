package app.androidrik.rikkahub.data.plugin.js

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Declarative tool; all tools share onTool({ name, arguments, ctx }), never a custom hook. */
@Serializable
data class JsToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    },
) {
    fun validate(): List<String> = buildList {
        if (!TOOL_NAME.matches(name)) add("tool name 必须匹配 ${TOOL_NAME.pattern}")
        if (description.isBlank() || description.length > 1000) add("description 必须非空且不超过 1000 字符")
        runCatching { validateToolSchema(parameters) }.exceptionOrNull()?.let {
            add("parameters: ${it.message}")
        }
    }
}

private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
private const val MAX_SCHEMA_BYTES = 16 * 1024
private const val MAX_JSON_DEPTH = 32
private const val MAX_JSON_NODES = 16 * 1024

/** Provider-safe name (at most 55 ASCII characters), stable and isolated by plugin id. */
fun pluginToolName(pluginId: String, toolName: String): String {
    require(TOOL_NAME.matches(toolName)) { "Invalid tool name" }
    val hash = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "rikka_${hash}_$toolName"
}

/**
 * Strict JSON Schema subset: explicit single type, object properties/required/boolean
 * additionalProperties, array items, enum, and title/description annotations. Unknown
 * keywords (including references, combinators, formats and bounds) are rejected.
 * Schemas are limited to 16 KiB and JSON depth 32; values have depth/node limits too.
 * Throws IllegalArgumentException for an unsupported schema or invalid arguments.
 */
fun validateToolArguments(schema: JsonObject, arguments: JsonElement) {
    validateToolSchema(schema)
    checkJsonBounds(arguments)
    validateValue(schema, arguments, "arguments")
}

private fun validateToolSchema(schema: JsonObject) {
    checkJsonBounds(schema)
    require(schema.toString().toByteArray(Charsets.UTF_8).size <= MAX_SCHEMA_BYTES) { "schema exceeds 16 KiB" }
    require(schema["type"] == JsonPrimitive("object")) { "root type must be object" }
    validateSchemaNode(schema, "parameters")
}

private fun checkJsonBounds(element: JsonElement) {
    var nodes = 0
    fun visit(value: JsonElement, depth: Int) {
        require(depth <= MAX_JSON_DEPTH) { "JSON depth exceeds $MAX_JSON_DEPTH" }
        require(++nodes <= MAX_JSON_NODES) { "JSON node count exceeds $MAX_JSON_NODES" }
        when (value) {
            is JsonObject -> value.values.forEach { visit(it, depth + 1) }
            is JsonArray -> value.forEach { visit(it, depth + 1) }
            else -> Unit
        }
    }
    visit(element, 0)
}

private fun validateSchemaNode(schema: JsonObject, path: String) {
    val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(type in setOf("object", "array", "string", "number", "integer", "boolean", "null")) {
        "$path: explicit supported type required"
    }
    val allowed = setOf("type", "enum", "title", "description") + when (type) {
        "object" -> setOf("properties", "required", "additionalProperties")
        "array" -> setOf("items")
        else -> emptySet()
    }
    require(schema.keys.all { it in allowed }) { "$path: unsupported schema keyword" }
    listOf("title", "description").forEach { key ->
        schema[key]?.let { require(it is JsonPrimitive && it.isString) { "$path.$key must be a string" } }
    }
    if (type == "object") {
        val properties = schema["properties"] as? JsonObject
            ?: throw IllegalArgumentException("$path.properties must be an object")
        properties.forEach { (key, child) ->
            require(child is JsonObject) { "$path.properties.$key must be a schema object" }
            validateSchemaNode(child, "$path.properties.$key")
        }
        schema["required"]?.let { required ->
            require(required is JsonArray) { "$path.required must be a string array" }
            require(required.all { it is JsonPrimitive && it.isString && it.content in properties }) {
                "$path.required must reference existing properties"
            }
            require(required.distinct().size == required.size) { "$path.required contains duplicates" }
        }
        schema["additionalProperties"]?.let {
            require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) {
                "$path.additionalProperties must be boolean"
            }
        }
    }
    if (type == "array") {
        val items = schema["items"] as? JsonObject
            ?: throw IllegalArgumentException("$path.items must be a schema object")
        validateSchemaNode(items, "$path.items")
    }
    schema["enum"]?.let { values ->
        require(values is JsonArray && values.isNotEmpty()) { "$path.enum must be a nonempty array" }
        val withoutEnum = JsonObject(schema.filterKeys { it != "enum" })
        values.forEach { validateValue(withoutEnum, it, "$path.enum") }
    }
}

private fun validateValue(schema: JsonObject, value: JsonElement, path: String) {
    val primitive = value as? JsonPrimitive
    val number = primitive?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
    val type = (schema.getValue("type") as JsonPrimitive).content
    val matches = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> primitive?.isString == true
        "number" -> number != null
        "integer" -> number != null && number.stripTrailingZeros().scale() <= 0
        "boolean" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
        "null" -> value == JsonNull
        else -> false
    }
    require(matches) { "$path must have type $type" }
    (schema["enum"] as? JsonArray)?.let { require(value in it) { "$path is not in enum" } }
    if (value is JsonObject) {
        val properties = schema.getValue("properties") as JsonObject
        (schema["required"] as? JsonArray)?.forEach {
            val key = (it as JsonPrimitive).content
            require(key in value) { "$path missing required property $key" }
        }
        value.forEach { (key, child) ->
            val childSchema = properties[key] as? JsonObject
            if (childSchema != null) validateValue(childSchema, child, "$path.$key")
            else require(schema["additionalProperties"] != JsonPrimitive(false)) { "$path unknown property $key" }
        }
    }
    if (value is JsonArray) {
        val items = schema.getValue("items") as JsonObject
        value.forEachIndexed { index, child -> validateValue(items, child, "$path[$index]") }
    }
}
