package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunk
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 Responses API 的 toolCallId 归一（上游 `5ac149b9` / #1948）。
 *
 * 回归背景：规范里 `item.id`（`fc_xxx`）与 `call_id`（`call_xxx`）不同，而**回传下一轮请求
 * 必须用 `call_id`**（`ResponseAPI.buildMessages` 里 `put("call_id", tool.toolCallId)`）。
 * 本仓原先用 `item.id` 当 `toolCallId`，于是多轮工具调用回传的 `call_id` 不符协议，
 * 兼容网关在同一流里**混用**两种 id 时工具参数也永远合并不上。
 *
 * P1（#1648 移植）后流式解析换成 [ResponseApiStreamDecoder]：id 归一从外部 resolver 移入解码器
 * 内部（复用 [ResponseToolCallIdResolver]），每次流一个实例；`item.id → call_id`、`output_index`
 * 两张映射照旧维护，`function_call_arguments.*` 按 `item_id → call_id → output_index` 回查。
 *
 * 事件形状照搬旧测试（真实网关抓包）：`output_item.added` 带 `item.id` + `item.call_id`，
 * 而 arguments 事件只带 `call_id` + `output_index`，甚至只带 `output_index`。
 */
class ResponseAPIStreamToolCallIdTest {

    private fun decode(
        payload: String,
        decoder: ResponseApiStreamDecoder = ResponseApiStreamDecoder(),
    ): List<StreamChunk> = decoder.accept(SseEvent(data = payload)).chunks

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

    private fun argumentsEvent(block: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("type", "response.function_call_arguments.done")
            block()
            put("arguments", "{\"query\":\"Kotlin\"}")
        }.toString()

    /** 取 chunks 里工具事件（Start/Delta）的 toolCallId。 */
    private fun toolCallIdOf(chunks: List<StreamChunk>): String? = chunks.mapNotNull {
        when (it) {
            is StreamChunk.ToolCallStart -> it.id
            is StreamChunk.ToolCallDelta -> it.id
            else -> null
        }
    }.firstOrNull()

    private fun toolInputDeltaOf(chunks: List<StreamChunk>): String? =
        chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta }.ifEmpty { null }

    @Test
    fun `normal shape - item id maps to call id everywhere`() {
        val decoder = ResponseApiStreamDecoder()
        val added = decode(functionCallItem("response.output_item.added", id = "fc_1"), decoder)

        // item.id=fc_1 但 call_id=call_1：规范 id 应是 call_1（回传时用的就是它）
        assertEquals("call_1", toolCallIdOf(added))

        // arguments.done 只带 item_id（正常 OpenAI 形状）→ 经解码器内部映射得到同一个 call_1
        val done = decode(argumentsEvent { put("item_id", "fc_1") }, decoder)
        assertEquals("call_1", toolCallIdOf(done))
        assertEquals("{\"query\":\"Kotlin\"}", toolInputDeltaOf(done))
    }

    @Test
    fun `gateway shape - arguments carry only call_id`() {
        val decoder = ResponseApiStreamDecoder()
        decode(functionCallItem("response.output_item.added", id = "fc_1"), decoder)

        // 网关不给 item_id，只给 call_id：直接用 call_id（本来就是要回传的那个）
        val done = decode(
            argumentsEvent {
                put("output_index", 1)
                put("call_id", "call_1")
            },
            decoder,
        )
        assertEquals("call_1", toolCallIdOf(done))
        assertEquals("{\"query\":\"Kotlin\"}", toolInputDeltaOf(done))
    }

    @Test
    fun `gateway shape - no item id at all falls back to output index`() {
        val decoder = ResponseApiStreamDecoder()
        // item.id 缺失 ⇒ 解码器退回 item.call_id，并把 output_index 登记到映射
        assertEquals(
            "call_1",
            toolCallIdOf(decode(functionCallItem("response.output_item.added", id = null), decoder)),
        )

        // arguments 连 item_id/call_id 都没有，只有 output_index → 查下标映射
        val done = decode(argumentsEvent { put("output_index", 1) }, decoder)
        assertEquals("call_1", toolCallIdOf(done))
        assertEquals("{\"query\":\"Kotlin\"}", toolInputDeltaOf(done))
    }

    @Test
    fun `resolution is per stream - a fresh decoder starts clean`() {
        // 旧实现靠外部 resolver 的 per-stream 生命周期；新实现每流一个解码器实例。
        val first = ResponseApiStreamDecoder()
        decode(functionCallItem("response.output_item.added", id = "fc_1"), first)

        // 新流未登记任何 item：arguments 带 item_id 只能原样返回（不泄漏上一流的状态）
        val second = ResponseApiStreamDecoder()
        val done = decode(argumentsEvent { put("item_id", "fc_1") }, second)
        assertEquals("fc_1", toolCallIdOf(done))
    }

    @Test
    fun `arguments without any identifier fails loudly`() {
        val error = assertThrows(IllegalStateException::class.java) {
            decode(argumentsEvent { })
        }
        // 明确失败，而不是产出空 id 的工具调用（空 id 会导致参数合并不上且无从排查）
        assertEquals("item_id not found", error.message)
    }

    @Test
    fun `unrelated events are untouched`() {
        val text = decode(
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("item_id", "msg_1")
                put("delta", "hello")
            }.toString()
        )
        assertEquals(
            "hello",
            text.filterIsInstance<StreamChunk.TextDelta>().single().text,
        )
        assertTrue(decode(buildJsonObject { put("type", "response.unknown.event") }.toString()).isEmpty())
    }
}
