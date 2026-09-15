package app.androidrik.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具 schema 体积估算（B2：按 provider 实际形状）。
 *
 * 关键约束：估算必须**套用 provider 发送时的形状变换**（OpenAI 的 `type/function` 包装、
 * Claude 的 `input_schema` 键、Google 的字段剥离 + `ensureArrayItems`），否则数字不可解释。
 */
class ToolSchemaSizeTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun tool(
        name: String = "demo_tool",
        description: String = "A demo tool.",
        schema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path")
                })
            })
        },
    ) = Tool(
        name = name,
        description = description,
        parameters = { InputSchema.Obj(properties = schema) },
        execute = { emptyList() },
    )

    // ---- 形状差异：Claude 用 input_schema 键、OpenAI 用 type/function 包装 ----

    @Test
    fun `claude shape uses input_schema key`() {
        val t = tool()
        val claude = ToolSchemaSize.estimate(t, providerOf(ProviderSetting.Claude()), json)
        val openai = ToolSchemaSize.estimate(t, providerOf(ProviderSetting.OpenAI()), json)
        // 两者都是正数且接近（包装差异只在个位数百分比），但不应相等
        assertTrue("claude estimate should be positive", claude > 0)
        assertTrue("openai estimate should be positive", openai > 0)
        assertTrue(
            "shapes should differ slightly: claude=$claude openai=$openai",
            claude != openai,
        )
    }

    @Test
    fun `google shape strips enum and format fields making it smaller`() {
        val withEnum = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("format", "uri-reference")
                        put("enum", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("dex"))
                            add(kotlinx.serialization.json.JsonPrimitive("resource"))
                            add(kotlinx.serialization.json.JsonPrimitive("native"))
                        })
                    })
                })
            },
        )
        val google = ToolSchemaSize.estimate(withEnum, providerOf(ProviderSetting.Google()), json)
        val openai = ToolSchemaSize.estimate(withEnum, providerOf(ProviderSetting.OpenAI()), json)
        // Google 剥掉 enum/format → 应显著更小（这份 schema 里 enum 占了大头）
        assertTrue(
            "google should be smaller after stripping enum/format: google=$google openai=$openai",
            google < openai,
        )
    }

    // ---- ensureArrayItems：数组缺 items 会被补上 ----

    @Test
    fun `bare array and already-filled array estimate the same`() {
        // ensureArrayItems 是**幂等**的：裸 array 补出 items、已带 items 的保持原样，
        // 两者归一化后线缆字节一致。故「估算稳定，不因 server 是否声明 items 而漂移」。
        val bare = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("classes", buildJsonObject { put("type", "array") })
                })
            },
        )
        val filled = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("classes", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                })
            },
        )
        val provider = providerOf(ProviderSetting.OpenAI())
        assertEquals(
            "idempotent normalization should make estimates equal",
            ToolSchemaSize.estimate(filled, provider, json),
            ToolSchemaSize.estimate(bare, provider, json),
        )
    }

    @Test
    fun `injected items still counts toward the estimate`() {
        // 裸 array 归一化后多了 items 字段 —— 估算必须包含它（不能按原始 schema 算，会漏）
        val bare = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("classes", buildJsonObject { put("type", "array") })
                })
            },
        )
        val noArray = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("classes", buildJsonObject { put("type", "string") })
                })
            },
        )
        val provider = providerOf(ProviderSetting.OpenAI())
        // array 归一化后 = {"type":"array","items":{"type":"string"}}，比 {"type":"string"} 长
        assertTrue(
            "normalized array should be larger than a plain string field",
            ToolSchemaSize.estimate(bare, provider, json) > ToolSchemaSize.estimate(noArray, provider, json),
        )
    }

    @Test
    fun `array typed via list form is normalized too`() {
        // type: ["array","null"] 这种列表形式也算数组（MCP schema 常见）
        val listTyped = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("tags", buildJsonObject {
                        put("type", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("array"))
                            add(kotlinx.serialization.json.JsonPrimitive("null"))
                        })
                    })
                })
            },
        )
        val stringField = tool(
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("tags", buildJsonObject {
                        put("type", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("string"))
                            add(kotlinx.serialization.json.JsonPrimitive("null"))
                        })
                    })
                })
            },
        )
        val provider = providerOf(ProviderSetting.OpenAI())
        // 列表含 "array" 也应补 items → 比不含 array 的更长
        assertTrue(
            ToolSchemaSize.estimate(listTyped, provider, json) >
                ToolSchemaSize.estimate(stringField, provider, json),
        )
    }

    // ---- 聚合与边界 ----

    @Test
    fun `batch estimate equals sum of individual estimates`() {
        val tools = listOf(
            tool(name = "a", description = "First tool."),
            tool(name = "b", description = "Second tool."),
            tool(name = "c", description = "Third tool."),
        )
        val provider = providerOf(ProviderSetting.OpenAI())
        val batch = ToolSchemaSize.estimate(tools, provider, json)
        val individually = tools.sumOf { ToolSchemaSize.estimate(it, provider, json) }
        assertEquals(individually, batch)
    }

    @Test
    fun `empty tool list estimates zero`() {
        assertEquals(0, ToolSchemaSize.estimate(emptyList(), providerOf(ProviderSetting.OpenAI()), json))
    }

    @Test
    fun `unknown provider falls back to openai shape`() {
        val t = tool()
        val nullProvider = ToolSchemaSize.estimate(t, null, json)
        val openai = ToolSchemaSize.estimate(t, providerOf(ProviderSetting.OpenAI()), json)
        assertEquals(openai, nullProvider)
    }

    @Test
    fun `schema-less tool still estimates something`() {
        // parameters 返回 null（如某些内置工具）→ 走空 Obj，不应崩且应为正
        val t = Tool(
            name = "no_schema",
            description = "Tool without parameters.",
            parameters = { null },
            execute = { emptyList() },
        )
        assertTrue(ToolSchemaSize.estimate(t, providerOf(ProviderSetting.OpenAI()), json) > 0)
    }

    @Test
    fun `longer description yields larger estimate`() {
        val provider = providerOf(ProviderSetting.OpenAI())
        val short = tool(description = "Short.")
        val long = tool(description = "A".repeat(4_000))
        assertTrue(
            ToolSchemaSize.estimate(long, provider, json) > ToolSchemaSize.estimate(short, provider, json),
        )
    }

    private fun providerOf(setting: ProviderSetting): ProviderSetting = setting
}
