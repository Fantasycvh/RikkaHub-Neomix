package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentsJsonTest {

    private val json = Json

    @Test
    fun `lenient retry recovers unquoted keys`() {
        val r = parseToolArguments("""{a:1, b:"x"}""", json)
        assertEquals(true, r.lenient)
        assertEquals(0, r.trailingChars)
        assertEquals(1, r.args.jsonObject["a"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `strict parse never sets lenient flag`() {
        val r = parseToolArguments("""{"a":1}""", json)
        assertEquals(false, r.lenient)
        assertEquals(0, r.trailingChars)
    }

    @Test
    fun `single object parses strictly with no trailing`() {
        val r = parseToolArguments("""{"query":"Tavily API","max_results":2}""", json)
        assertEquals(0, r.trailingChars)
        assertEquals("Tavily API", r.args.jsonObject["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun `two concatenated objects recovers the first one`() {
        // #1985 实测形态：紧凑 + 带空格，语义相同
        val input = """{"query":"a"}{"query": "a"}"""
        val r = parseToolArguments(input, json)
        // 第一个对象 13 字符，尾随 = 总长 - 13 = 14
        assertEquals(14, r.trailingChars)
        assertEquals("a", r.args.jsonObject["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun `two objects separated by whitespace recovers the first`() {
        val r = parseToolArguments("""{"a":1}   { "a": 2 }""", json)
        assertEquals(1, r.args.jsonObject["a"]!!.jsonPrimitive.content.toInt())
        assertTrue(r.trailingChars > 0)
    }

    @Test
    fun `braces inside strings do not break pairing`() {
        // 字符串里的 }{ 不参与配对：第一个对象在真正的闭合处结束
        val input = """{"text":"a}{" }{"text":"b"}"""
        val r = parseToolArguments(input, json)
        assertEquals("a}{", r.args.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `escaped quote inside string is handled`() {
        val input = """{"text":"say \"}hi\""}{"text":"x"}"""
        val r = parseToolArguments(input, json)
        assertEquals("""say "}hi"""", r.args.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested and mixed containers recover the whole outer object`() {
        val input = """{"list":[1,{"k":"}"}],"m":{"n":2}}{"junk":true}"""
        val r = parseToolArguments(input, json)
        assertEquals(2, r.args.jsonObject.size)
        assertEquals(2, r.args.jsonObject["m"]!!.jsonObject["n"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `array as first value recovers the array`() {
        val input = """[1,{"a":2}][3]"""
        val r = parseToolArguments(input, json)
        val arr = r.args as kotlinx.serialization.json.JsonArray
        assertEquals(2, arr.size)
        assertEquals(2, arr[1].jsonObject["a"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `truncated first object is not recoverable and rethrows original`() {
        // 第一个对象不完整 ⇒ 取「第一个完整值」不存在，如实抛原始错误（JsonDecodingException）
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            parseToolArguments("""{"query":"x" {"query":"y"}""", json)
        }
    }

    @Test
    fun `non container input rethrows only when no complete value exists`() {
        // 默认 Json 对顶层裸字面量是宽容的（garbage 直接解析成 JsonPrimitive）——钉住该既有行为
        assertEquals("garbage", parseToolArguments("garbage", json).args.toString())
        assertEquals(0, parseToolArguments("garbage", json).trailingChars)
        // 不完整且截不出任何完整值 ⇒ 如实重抛原始错误
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            parseToolArguments("{", json)
        }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            parseToolArguments("""{"a": {""", json)
        }
    }

    @Test
    fun `firstJsonValueBounds returns null on empty or whitespace`() {
        assertNull(firstJsonValueBounds(""))
        assertNull(firstJsonValueBounds("   "))
    }

    private fun assertTrue(b: Boolean) {
        org.junit.Assert.assertTrue(b)
    }
}
