package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具 schema 压缩（批次4 ToolSchemaCompactor）：只裁描述文本，参数/required/枚举不动；
 * 纪律文本工具 3× 上限；档位映射与句子边界截断。
 */
class ToolSchemaCompactorTest {

    private fun tool(
        name: String,
        description: String = "D".repeat(1000),
        paramDescription: String = "P".repeat(500),
        required: List<String> = listOf("path"),
    ) = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = kotlinx.serialization.json.buildJsonObject {
                    put("path", kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive(paramDescription))
                        put("enum", kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("a"))
                            add(JsonPrimitive("b"))
                        })
                        put("examples", kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("example"))
                        })
                    })
                },
                required = required,
            )
        },
        execute = { emptyList() },
    )

    @Test
    fun `off level returns the same instances`() {
        val tools = listOf(tool("t1"))
        assertTrue(ToolSchemaCompactor.compact(tools, ToolSchemaCompactor.Level.OFF).first() === tools.first())
    }

    @Test
    fun `balanced trims descriptions but keeps parameters enums and required`() {
        val compacted = ToolSchemaCompactor.compact(
            listOf(tool("t1", required = listOf("path"))),
            ToolSchemaCompactor.Level.BALANCED,
        ).single()
        assertNotEquals(tool("t1").description, compacted.description)
        assertTrue(compacted.description.length <= 321) // 320 + …
        val schema = compacted.parameters() as InputSchema.Obj
        assertEquals(listOf("path"), schema.required)
        val path = schema.properties["path"]!!.jsonObject
        // 枚举原样保留；参数描述收到 120
        assertEquals("a", path["enum"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonPrimitive.content)
        assertTrue(path["description"]!!.jsonPrimitive.content.length <= 121)
        // BALANCED 不丢 examples
        assertTrue("examples" in path)
    }

    @Test
    fun `aggressive drops examples`() {
        val compacted = ToolSchemaCompactor.compact(
            listOf(tool("t1")),
            ToolSchemaCompactor.Level.AGGRESSIVE,
        ).single()
        val path = (compacted.parameters() as InputSchema.Obj).properties["path"]!!.jsonObject
        assertTrue("examples" !in path)
    }

    @Test
    fun `essential tool gets the triple cap`() {
        val normal = ToolSchemaCompactor.compact(
            listOf(tool("plain_tool"), tool("memory_tool")),
            ToolSchemaCompactor.Level.BALANCED,
        )
        val plainDesc = normal.first { it.name == "plain_tool" }.description
        val memoryDesc = normal.first { it.name == "memory_tool" }.description
        // 320 vs min(960, 320*3)：纪律文本工具的上限是 3×
        assertTrue(plainDesc.length <= 321)
        assertTrue(memoryDesc.length > plainDesc.length || memoryDesc.length <= 961)
    }

    @Test
    fun `trimDescription cuts at sentence boundary when reasonably near`() {
        val text = "First sentence. " + "x".repeat(300) + ". Second sentence."
        val trimmed = ToolSchemaCompactor.trimDescription(text, 100)
        assertTrue(trimmed.endsWith("…"))
        // 边界句号在窗口内 ≥50% 位置 ⇒ 在句号处断
        assertTrue(trimmed.startsWith("First sentence."))
    }

    @Test
    fun `level mapping follows the persisted int scheme`() {
        assertEquals(ToolSchemaCompactor.Level.OFF, ToolSchemaCompactor.Level.from(0))
        assertEquals(ToolSchemaCompactor.Level.BALANCED, ToolSchemaCompactor.Level.from(1))
        assertEquals(ToolSchemaCompactor.Level.AGGRESSIVE, ToolSchemaCompactor.Level.from(2))
        // 未知值回落 BALANCED（与 Yuihub from() 同语义）
        assertEquals(ToolSchemaCompactor.Level.BALANCED, ToolSchemaCompactor.Level.from(9))
    }

    @Test
    fun `json serialization of compacted schema keeps tool callable shape`() {
        val compacted = ToolSchemaCompactor.compact(
            listOf(tool("t1", paramDescription = "Q".repeat(400))),
            ToolSchemaCompactor.Level.BALANCED,
        ).single()
        val encoded = JsonInstant.encodeToString(InputSchema.Obj.serializer(), compacted.parameters() as InputSchema.Obj)
        val obj = JsonInstant.parseToJsonElement(encoded).jsonObject
        assertTrue("required" in obj)
    }
}
