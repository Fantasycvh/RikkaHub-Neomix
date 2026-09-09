package app.androidrik.ai.provider.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证 Gemini function schema 归一化 ensureArrayItems：
 * 数组类型属性缺 items 时补 {"type":"string"}（Gemini API 校验要求），
 * 嵌套对象不递归重包（保持原样）。
 */
class GoogleProviderSchemaTest {

    private fun schema(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `array property without items gets string items added`() {
        val input = schema(
            "type" to kotlinx.serialization.json.JsonPrimitive("object"),
            "properties" to buildJsonObject {
                put("foo", buildJsonObject {
                    put("type", "array")
                    // 缺 items
                })
            },
        )
        val out = input.ensureArrayItems().jsonObject
        val foo = out["properties"]!!.jsonObject["foo"]!!.jsonObject
        assertEquals("array", foo["type"]?.jsonPrimitive?.contentOrNull)
        assertNotNull("数组属性必须补 items", foo["items"])
        assertEquals("string", foo["items"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `non-array properties are left untouched`() {
        val input = schema(
            "type" to kotlinx.serialization.json.JsonPrimitive("object"),
            "properties" to buildJsonObject {
                put("bar", buildJsonObject { put("type", "string") })
            },
        )
        val out = input.ensureArrayItems().jsonObject
        val bar = out["properties"]!!.jsonObject["bar"]!!.jsonObject
        assertNull("非数组属性不插 items", bar["items"])
    }

    @Test
    fun `array with existing items keeps them`() {
        val input = schema(
            "type" to kotlinx.serialization.json.JsonPrimitive("object"),
            "properties" to buildJsonObject {
                put("args", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "integer") })
                })
            },
        )
        val out = input.ensureArrayItems().jsonObject
        val args = out["properties"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("integer", args["items"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }
}
