package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务端已执行函数工具的 `function_call_output` 解析（上游 #2181 / `675e59f0d`）。
 *
 * 背景：部分兼容网关在**服务端执行函数工具**，并把结果作为 `function_call_output` 输出项下发。
 * 本仓原先三处解析点全部忽略该类型 ⇒ 工具 `output` 为空 ⇒ `isExecuted = false` ⇒
 * `GenerationHandler` **再执行一次**（重复副作用）；其中 `output_item.done` 还先取 `item.id`
 * 再分 type，网关不带 `id` 时直接 `error()` 让整轮请求失败。
 *
 * 事件形状参照上游测试与 Responses API 规范：`function_call_output` 只保证 `call_id` + `output`。
 */
class ResponseAPIFunctionCallOutputTest {

    private lateinit var api: ResponseAPI
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun decode(payload: String, resolver: ResponseToolCallIdResolver = ResponseToolCallIdResolver()): MessageChunk? =
        api.parseResponseDelta(json.parseToJsonElement(payload).jsonObject, resolver)

    private fun toolOf(chunk: MessageChunk?): UIMessagePart.Tool? =
        chunk?.choices?.firstOrNull()?.delta?.parts?.filterIsInstance<UIMessagePart.Tool>()?.firstOrNull()

    private fun outputItemEvent(
        eventType: String,
        item: kotlinx.serialization.json.JsonObject,
    ): String = buildJsonObject {
        put("type", eventType)
        put("output_index", 1)
        put("item", item)
    }.toString()

    private fun functionCallItem() = buildJsonObject {
        put("type", "function_call")
        put("id", "fc_1")
        put("call_id", "call_1")
        put("name", "search_web")
        put("arguments", "")
    }

    private fun functionCallOutputItem(
        output: kotlinx.serialization.json.JsonElement,
        includeId: Boolean = true,
        callId: String = "call_1",
    ) = buildJsonObject {
        put("type", "function_call_output")
        if (includeId) put("id", "fco_1")
        put("call_id", callId)
        put("output", output)
    }

    @Test
    fun `streaming added - function_call_output carries output text`() {
        val chunk = decode(outputItemEvent("response.output_item.added", functionCallOutputItem(kotlinx.serialization.json.JsonPrimitive("42°C"))))
        val tool = toolOf(chunk)

        assertEquals("call_1", tool?.toolCallId)
        assertEquals(listOf("42°C"), tool?.output?.filterIsInstance<UIMessagePart.Text>()?.map { it.text })
        // 有 output ⇒ 已执行，客户端不会再跑一遍
        assertTrue(tool!!.isExecuted)
    }

    @Test
    fun `streaming done without item id does not throw and still attaches output`() {
        // 回归：修复前 done 分支先取 item.id 再分 type，缺 id 直接 error("chunk id not found")
        val chunk = decode(
            outputItemEvent(
                "response.output_item.done",
                functionCallOutputItem(kotlinx.serialization.json.JsonPrimitive("ok"), includeId = false),
            )
        )
        val tool = toolOf(chunk)

        assertEquals("call_1", tool?.toolCallId)
        assertEquals("ok", tool?.output?.filterIsInstance<UIMessagePart.Text>()?.firstOrNull()?.text)
    }

    @Test
    fun `streaming - array output with input_text parts is joined`() {
        val output = buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", "line1")
            })
            add(buildJsonObject {
                put("type", "input_text")
                put("text", "line2")
            })
        }
        val chunk = decode(outputItemEvent("response.output_item.done", functionCallOutputItem(output)))
        assertEquals(
            "line1\nline2",
            toolOf(chunk)?.output?.filterIsInstance<UIMessagePart.Text>()?.firstOrNull()?.text,
        )
    }

    @Test
    fun `streaming - null output yields empty text and stays executed`() {
        val chunk = decode(outputItemEvent("response.output_item.done", functionCallOutputItem(JsonNull)))
        val tool = toolOf(chunk)!!

        assertEquals("", tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text)
        // 服务端确实执行过：宁可显示空结果，也不能让客户端再执行一次
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `streaming - missing call_id is ignored instead of crashing`() {
        val item = buildJsonObject {
            put("type", "function_call_output")
            put("output", "x")
        }
        val chunk = decode(outputItemEvent("response.output_item.done", item))
        assertEquals(null, chunk)
    }

    @Test
    fun `merge keeps existing tool input and appends server output`() {
        val empty = UIMessagePart.Tool(toolCallId = "call_1", toolName = "search_web", input = "{\"q\":\"k\"}")
        val withOutput = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "",
            input = "",
            output = listOf(UIMessagePart.Text("server result")),
        )

        val merged = empty.merge(withOutput)
        assertEquals("search_web", merged.toolName)
        assertEquals("{\"q\":\"k\"}", merged.input)
        assertTrue(merged.isExecuted)
    }

    @Test
    fun `non-streaming - output array attaches result to matching function_call`() {
        val response = buildJsonObject {
            put("id", "resp_1")
            put("model", "gpt-5")
            put(
                "output",
                buildJsonArray {
                    add(functionCallItem())
                    add(functionCallOutputItem(kotlinx.serialization.json.JsonPrimitive("sunny")))
                },
            )
        }

        val chunk = api.parseResponseOutput(response)
        val tools = chunk.choices.first().message!!.parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("call_1", tools.first().toolCallId)
        assertEquals("sunny", tools.first().output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text)
        assertTrue(tools.first().isExecuted)
    }

    @Test
    fun `non-streaming - unmatched output is ignored`() {
        val response = buildJsonObject {
            put("id", "resp_1")
            put("model", "gpt-5")
            put(
                "output",
                buildJsonArray {
                    add(functionCallItem())
                    add(functionCallOutputItem(kotlinx.serialization.json.JsonPrimitive("x"), callId = "call_other"))
                },
            )
        }

        val chunk = api.parseResponseOutput(response)
        val tools = chunk.choices.first().message!!.parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("call_1", tools.first().toolCallId)
        assertEquals(emptyList<UIMessagePart>(), tools.first().output)
    }
}
