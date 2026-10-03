package app.androidrik.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.ai.core.InputSchema

/**
 * `$ref` 就地展开（#1974 / #1968）：悬空 `$ref` 会让 provider 拒绝**整个请求**，
 * 故这里钉住「能解析就解析、解析不了就宽松降级（绝不留下 `$ref`）」两条契约。
 */
class McpSchemaRefsTest {
    private val json = Json

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun inline(properties: String, defs: String?): InlineRefsResult =
        inlineSchemaRefs(
            properties = obj(properties),
            defs = defs?.let { obj(it) },
        )

    @Test
    fun `inlines a top-level ref from defs`() {
        val result = inline(
            properties = """{"trigger":{"${'$'}ref":"#/${'$'}defs/TriggerSpec"}}""",
            defs = """{"TriggerSpec":{"type":"object","properties":{"kind":{"type":"string"}}}}""",
        )

        assertEquals(1, result.resolvedCount)
        assertTrue(result.unresolvedRefs.isEmpty())
        val trigger = result.schema.getValue("trigger").jsonObject
        assertEquals("object", trigger.getValue("type").jsonPrimitive.content)
        assertEquals(
            "string",
            trigger.getValue("properties").jsonObject.getValue("kind")
                .jsonObject.getValue("type").jsonPrimitive.content,
        )
        // 展开后不得残留 $ref
        assertTrue(!result.schema.toString().contains("\$ref"))
    }

    @Test
    fun `inlines nested and multi-use refs`() {
        val result = inline(
            properties = """{"a":{"${'$'}ref":"#/${'$'}defs/Item"},"b":{"${'$'}ref":"#/${'$'}defs/Item"}}""",
            defs = """{"Item":{"type":"string","enum":["x","y"]}}""",
        )

        assertEquals(2, result.resolvedCount)
        assertEquals("string", result.schema.getValue("a").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("string", result.schema.getValue("b").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `inlines refs inside arrays`() {
        val result = inline(
            properties = """{"items":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/Row"}}}""",
            defs = """{"Row":{"type":"object","properties":{"n":{"type":"integer"}}}}""",
        )

        assertEquals(1, result.resolvedCount)
        val arrayItems = result.schema.getValue("items").jsonObject
            .getValue("items").jsonObject
        assertEquals("object", arrayItems.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `resolves a ref that points inside another definition`() {
        val result = inline(
            properties = """{"deep":{"${'$'}ref":"#/${'$'}defs/Outer/properties/inner"}}""",
            defs = """{"Outer":{"type":"object","properties":{"inner":{"type":"boolean"}}}}""",
        )

        assertEquals(1, result.resolvedCount)
        assertEquals("boolean", result.schema.getValue("deep").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `ref chain is followed through definitions`() {
        // A 的 properties 里又引用了 B：必须递归展开到底
        val result = inline(
            properties = """{"a":{"${'$'}ref":"#/${'$'}defs/A"}}""",
            defs = """{
                "A":{"type":"object","properties":{"b":{"${'$'}ref":"#/${'$'}defs/B"}}},
                "B":{"type":"string"}
            }""",
        )

        assertEquals(2, result.resolvedCount)
        val b = result.schema.getValue("a").jsonObject
            .getValue("properties").jsonObject.getValue("b").jsonObject
        assertEquals("string", b.getValue("type").jsonPrimitive.content)
        assertTrue(!result.schema.toString().contains("\$ref"))
    }

    @Test
    fun `recursive ref is truncated to type only instead of looping forever`() {
        // 自引用（树/链表形态的 schema）：不能无限展开，循环处截断为目标的 type（保留类型约束）
        val result = inline(
            properties = """{"node":{"${'$'}ref":"#/${'$'}defs/Node"}}""",
            defs = """{"Node":{"type":"object","properties":{"name":{"type":"string"},"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}}""",
        )

        // 第一层展开 + 循环截断都算解析成功
        assertEquals(2, result.resolvedCount)
        assertEquals(0, result.unresolvedRefs.size)
        val node = result.schema.getValue("node").jsonObject
        assertEquals("string", node.getValue("properties").jsonObject
            .getValue("name").jsonObject.getValue("type").jsonPrimitive.content)
        // 循环处保留 type（上游 cf79246b 同款），而不是空对象或残留 $ref
        assertEquals(
            mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object")),
            node.getValue("properties").jsonObject.getValue("next").jsonObject,
        )
        assertTrue(!result.schema.toString().contains("\$ref"))
    }

    @Test
    fun `mutually recursive refs are truncated`() {
        val result = inline(
            properties = """{"a":{"${'$'}ref":"#/${'$'}defs/A"}}""",
            defs = """{
                "A":{"type":"object","properties":{"b":{"${'$'}ref":"#/${'$'}defs/B"}}},
                "B":{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/A"}}}
            }""",
        )

        assertTrue(result.resolvedCount >= 2)
        assertEquals(0, result.unresolvedRefs.size)
        assertTrue(!result.schema.toString().contains("\$ref"))
    }

    @Test
    fun `dangling ref degrades to permissive schema and is reported`() {
        // $defs 里没有该键（服务器 schema 自身不合法 / 指向被丢弃的 definitions）：宽松降级
        val result = inline(
            properties = """{"x":{"${'$'}ref":"#/${'$'}defs/Missing"}}""",
            defs = """{"Other":{"type":"string"}}""",
        )

        assertEquals(0, result.resolvedCount)
        assertEquals(listOf("#/\$defs/Missing"), result.unresolvedRefs)
        assertEquals(
            emptyMap<String, kotlinx.serialization.json.JsonElement>(),
            result.schema.getValue("x").jsonObject,
        )
    }

    @Test
    fun `root pointer that misses degrades and is reported`() {
        // 根文档（type+properties+$defs）里不存在该指针 ⇒ 宽松降级
        val result = inline(
            properties = """{"y":{"${'$'}ref":"#/properties/other"}}""",
            defs = """{"Other":{"type":"string"}}""",
        )

        assertEquals(0, result.resolvedCount)
        assertEquals(listOf("#/properties/other"), result.unresolvedRefs)
        assertEquals(
            emptyMap<String, kotlinx.serialization.json.JsonElement>(),
            result.schema.getValue("y").jsonObject,
        )
    }

    @Test
    fun `root properties pointer resolves without defs`() {
        // 任意 #/ 指针（对齐上游 cf79246b）：#/properties/... 在无 $defs 时也可解析
        val result = inline(
            properties = """{"a":{"type":"string"},"b":{"${'$'}ref":"#/properties/a"}}""",
            defs = null,
        )

        assertEquals(1, result.resolvedCount)
        assertEquals(0, result.unresolvedRefs.size)
        assertEquals("string", result.schema.getValue("b").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `legacy definitions pointer resolves`() {
        // 旧式 #/definitions/... 写法（根文档没有该键时降级，有 $defs 的服务器不受影响）
        val result = inline(
            properties = """{"x":{"${'$'}ref":"#/definitions/Legacy"}}""",
            defs = """{"Legacy":{"type":"integer"}}""",
        )

        // 根文档里只放了 $defs 键 ⇒ #/definitions/ 不存在 ⇒ 宽松降级（如实记录）
        assertEquals(0, result.resolvedCount)
        assertEquals(listOf("#/definitions/Legacy"), result.unresolvedRefs)
    }

    @Test
    fun `recursive ref via properties pointer keeps type`() {
        // 非 $defs 指针的循环引用同样截断为 type
        val result = inline(
            properties = """{"node":{"type":"object","properties":{"next":{"${'$'}ref":"#/properties/node"}}}}""",
            defs = null,
        )

        assertEquals(2, result.resolvedCount)
        assertEquals(0, result.unresolvedRefs.size)
        val next = result.schema.getValue("node").jsonObject
            .getValue("properties").jsonObject.getValue("next").jsonObject
        assertEquals("object", next.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `ref with sibling keys keeps the siblings`() {
        // draft 2020-12 允许 $ref 与兄弟键并存；兄弟键的描述信息不该被丢
        val result = inline(
            properties = """{"z":{"${'$'}ref":"#/${'$'}defs/Base","description":"覆盖说明"}}""",
            defs = """{"Base":{"type":"string"}}""",
        )

        val z = result.schema.getValue("z").jsonObject
        assertEquals("string", z.getValue("type").jsonPrimitive.content)
        assertEquals("覆盖说明", z.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `no defs still attempts root pointer resolution`() {
        // 无 $defs 但 properties 里有 $ref：不再整体早退，构建根文档解析
        // （#/​$defs/X 指向不存在的定义 ⇒ 宽松降级并如实记录）
        val result = inline(
            properties = """{"a":{"type":"string"},"r":{"${'$'}ref":"#/${'$'}defs/X"}}""",
            defs = null,
        )

        assertEquals(0, result.resolvedCount)
        assertEquals(listOf("#/\$defs/X"), result.unresolvedRefs)
        assertTrue(!result.schema.toString().contains("\$ref"))
    }

    @Test
    fun `null properties yields empty object`() {
        val result = inlineSchemaRefs(properties = null, defs = obj("""{"A":{"type":"string"}}"""))
        assertEquals(
            emptyMap<String, kotlinx.serialization.json.JsonElement>(),
            result.schema,
        )
        assertEquals(0, result.resolvedCount)
    }

    @Test
    fun `json pointer escapes in definition names are decoded`() {
        // JSON Pointer 规范：~1 → "/"，~0 → "~"
        val result = inline(
            properties = """{"a":{"${'$'}ref":"#/${'$'}defs/a~1b"}}""",
            defs = """{"a/b":{"type":"number"}}""",
        )

        assertEquals(1, result.resolvedCount)
        assertEquals("number", result.schema.getValue("a").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `unrelated properties are untouched`() {
        val result = inline(
            properties = """{"keep":{"type":"string","description":"原样"},"ref":{"${'$'}ref":"#/${'$'}defs/A"}}""",
            defs = """{"A":{"type":"integer"}}""",
        )

        val keep = result.schema.getValue("keep").jsonObject
        assertEquals("string", keep.getValue("type").jsonPrimitive.content)
        assertEquals("原样", keep.getValue("description").jsonPrimitive.content)
    }

    // ---- 接缝测试：真实的 ToolSchema → InputSchema 转换（#1974 的出错点）----
    //
    // 纯函数测试无法覆盖「转换这一步有没有真的调用展开」。这里用 SDK 自己的序列化器
    // 解析服务器响应形态的 JSON（与 `sdkClient.listTools()` 走同一条解析路径），
    // 再断言产出的 InputSchema 里没有悬空 `$ref`。

    private val sdkJson = Json { ignoreUnknownKeys = true }

    private fun serverToolSchema(text: String): ToolSchema =
        sdkJson.decodeFromString(ToolSchema.serializer(), text)

    @Test
    fun `toSchema inlines defs refs in the real sdk conversion`() {
        // 形态对齐 Devin MCP 的工具：$defs 在顶层，properties 里用 $ref 引用
        val toolSchema = serverToolSchema(
            """
            {
              "${'$'}schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "action": { "type": "string" },
                "trigger": { "${'$'}ref": "#/${'$'}defs/AutomationTriggerSpec" }
              },
              "required": ["action"],
              "${'$'}defs": {
                "AutomationTriggerSpec": {
                  "type": "object",
                  "properties": { "kind": { "type": "string", "enum": ["cron", "webhook"] } }
                }
              }
            }
            """.trimIndent(),
        )

        val schema = toolSchema.toSchema() as InputSchema.Obj

        assertEquals(listOf("action"), schema.required)
        assertTrue(
            "转换结果不得残留 \$ref（悬空引用会让 provider 拒绝整个请求）",
            !schema.properties.toString().contains("\$ref"),
        )
        // $ref 位置被真实定义替换：能取到 kind.enum
        val trigger = schema.properties.getValue("trigger").jsonObject
        val kind = trigger.getValue("properties").jsonObject.getValue("kind").jsonObject
        assertEquals(
            """["cron","webhook"]""",
            kind.getValue("enum").toString(),
        )
    }

    @Test
    fun `toSchema keeps plain schemas byte identical`() {
        // 不用 $defs 的常规服务器：转换结果必须与修复前完全一致（零行为变更）
        val toolSchema = serverToolSchema(
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
        )

        val schema = toolSchema.toSchema() as InputSchema.Obj

        assertEquals(
            """{"path":{"type":"string"}}""",
            schema.properties.toString(),
        )
        assertEquals(listOf("path"), schema.required)
    }

    @Test
    fun `toSchema degrades dangling refs rather than emitting them`() {
        // 服务器 schema 自身不合法（引用不存在的定义）：宁可宽松，不可让请求 400
        val toolSchema = serverToolSchema(
            """{
              "type":"object",
              "properties":{"x":{"${'$'}ref":"#/${'$'}defs/Missing"}},
              "${'$'}defs":{"Other":{"type":"string"}}
            }""",
        )

        val schema = toolSchema.toSchema() as InputSchema.Obj

        assertTrue(!schema.properties.toString().contains("\$ref"))
        assertEquals(
            emptyMap<String, kotlinx.serialization.json.JsonElement>(),
            schema.properties.getValue("x").jsonObject,
        )
    }
}
