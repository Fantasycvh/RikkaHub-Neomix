package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 流式 Responses API 的 toolCallId 归一（上游 `5ac149b9` / #1948）。
 *
 * 回归背景：规范里 `item.id`（`fc_xxx`）与 `call_id`（`call_xxx`）不同，而**回传下一轮请求
 * 必须用 `call_id`**（`ResponseAPI.buildMessages` 里 `put("call_id", tool.toolCallId)`）。
 * 本仓原先用 `item.id` 当 `toolCallId`，于是：
 * 1. 多轮工具调用回传的 `call_id` 不符协议；
 * 2. 兼容网关在同一流里**混用**两种 id 时，工具参数因 id 不一致而永远合并不上。
 *
 * 事件形状照搬上游测试（真实网关抓包）：`output_item.added` 带 `item.id` + `item.call_id`，
 * 而 arguments 事件只带 `call_id` + `output_index`，甚至只带 `output_index`。
 */
class ResponseAPIStreamToolCallIdTest {

    private lateinit var api: ResponseAPI
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun decode(payload: String, resolver: ResponseToolCallIdResolver): MessageChunk? =
        api.parseResponseDelta(json.parseToJsonElement(payload).jsonObject, resolver)

    /** function_call 的 output_item 事件（`id` 可省，模拟网关差异）。 */
    private fun functionCallItem(type: String, id: String?): String = buildJsonObject {
        put("type", type)
        put("output_index", 1)
        put("item", buildJsonObject {
            put("type", "function_call")
            id?.let { put("id", it) }
            put("call_id", "call_1")
            put("name", "search_web")
            put("arguments", "")
        })
    }.toString()

    private fun argumentsEvent(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("type", "response.function_call_arguments.done")
            extra()
            put("arguments", "{\"query\":\"Kotlin\"}")
        }.toString()

    /** 取 chunk 里工具部件的 toolCallId。 */
    private fun toolCallIdOf(chunk: MessageChunk?): String? =
        chunk?.choices?.first()?.delta?.parts?.filterIsInstance<UIMessagePart.Tool>()?.first()?.toolCallId

    @Test
    fun `normal shape - item id maps to call id everywhere`() {
        val resolver = ResponseToolCallIdResolver()
        val added = decode(functionCallItem("response.output_item.added", id = "fc_1"), resolver)

        // item.id=fc_1 但 call_id=call_1：规范 id 应是 call_1（回传时用的就是它）
        assertEquals("call_1", toolCallIdOf(added))

        // arguments.done 只带 item_id（正常 OpenAI 形状）→ 经映射得到同一个 call_1
        val done = decode(argumentsEvent { put("item_id", "fc_1") }, resolver)
        assertEquals("call_1", toolCallIdOf(done))
    }

    @Test
    fun `gateway shape - arguments carry only call_id`() {
        val resolver = ResponseToolCallIdResolver()
        decode(functionCallItem("response.output_item.added", id = "fc_1"), resolver)

        val done = decode(argumentsEvent {
            put("output_index", 1)
            put("call_id", "call_1")
        }, resolver)
        assertEquals("call_1", toolCallIdOf(done))
    }

    @Test
    fun `gateway shape - no item id at all falls back to output index`() {
        val resolver = ResponseToolCallIdResolver()
        // item.id 缺失 ⇒ 退回 item.call_id
        assertEquals("call_1", toolCallIdOf(decode(functionCallItem("response.output_item.added", id = null), resolver)))

        // arguments 连 call_id 都没有，只有 output_index → 查下标映射
        val done = decode(argumentsEvent { put("output_index", 1) }, resolver)
        assertEquals("call_1", toolCallIdOf(done))
    }

    @Test
    fun `resolution is per stream - a fresh resolver starts clean`() {
        val first = ResponseToolCallIdResolver()
        decode(functionCallItem("response.output_item.added", id = "fc_1"), first)

        // 新流未登记任何 item：arguments 带 item_id 只能原样返回（不泄漏上一流的状态）
        val second = ResponseToolCallIdResolver()
        val done = decode(argumentsEvent { put("item_id", "fc_1") }, second)
        assertEquals("fc_1", toolCallIdOf(done))
    }

    @Test
    fun `arguments without any identifier fails loudly`() {
        val resolver = ResponseToolCallIdResolver()
        val error = runCatching { decode(argumentsEvent { }, resolver) }.exceptionOrNull()
        // 明确失败，而不是产出空 id 的工具调用（空 id 会导致参数合并不上且无从排查）
        assertEquals("item_id not found", error?.message)
    }

    @Test
    fun `unrelated events are untouched`() {
        val resolver = ResponseToolCallIdResolver()
        val text = decode(
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("item_id", "msg_1")
                put("delta", "hello")
            }.toString(),
            resolver,
        )
        assertEquals(
            "hello",
            text?.choices?.first()?.delta?.parts?.filterIsInstance<UIMessagePart.Text>()?.first()?.text,
        )
        assertNull(decode(buildJsonObject { put("type", "response.unknown.event") }.toString(), resolver))
    }
}
