package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端工具 item 的多轮回传（对齐上游 #2186）。
 *
 * 背景：Codex / ChatGPT 订阅后端 `store=false`，上一轮由服务端执行的工具（web_search 等）以 item
 * 形式出现在历史里；下一轮若不把它们回传，模型看不到自己的检索结果，严格后端还可能 400。
 * 本仓解码时已把原始 item 存进 `ServerToolMetadata.call`，这里钉住「发请求时会被原样/重建回传」。
 */
class ResponseAPIServerToolEchoTest {

    private val api = ResponseAPI(OkHttpClient())

    private fun items(vararg parts: UIMessagePart): JsonArray =
        api.buildMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())))

    private fun JsonArray.findFirst(type: String): JsonObject? =
        firstOrNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == type } as? JsonObject

    @Test
    fun `原始 web_search item 原样回传（不丢 action 结构）`() {
        val rawCall = buildJsonObject {
            put("type", "web_search_call")
            put("id", "ws_1")
            put("status", "completed")
            putJsonObject("action") {
                put("type", "search")
                put("query", "kotlin coroutines")
                put("sources", "web")
            }
        }
        val tool = UIMessagePart.ServerTool(
            toolCallId = "ws_1",
            toolName = "web_search",
            input = rawCall["action"],
            status = ServerToolStatus.COMPLETED,
            metadata = ServerToolMetadata(
                protocol = ServerToolProtocol.OPENAI_RESPONSES,
                call = rawCall,
            ).toMetadata(),
        )

        val items = items(UIMessagePart.Text("答案"), tool)

        // 文本先以 assistant content item 落盘
        assertTrue(items.any { (it as? JsonObject)?.get("type") == null })
        val echoed = items.findFirst("web_search_call")
        assertEquals("必须原样回传解码时保存的原始 item", rawCall, echoed)
        assertNull("其它协议之外不应额外重建 item", items.findFirst("web_search_call_duplicate"))
    }

    @Test
    fun `没有原始 item 时按字段重建（web_search action）`() {
        val action = buildJsonObject {
            put("type", "search")
            put("query", "android compose")
        }
        val tool = UIMessagePart.ServerTool(
            toolCallId = "ws_2",
            toolName = "web_search",
            input = action,
            output = buildJsonObject { put("status", "ok") },
            status = ServerToolStatus.FAILED,
        )

        val rebuilt = items(tool).findFirst("web_search_call")

        assertEquals("ws_2", rebuilt?.get("id")?.jsonPrimitive?.content)
        assertEquals("failed", rebuilt?.get("status")?.jsonPrimitive?.content)
        assertEquals("web_search 的 input 写到 action 字段", action, rebuilt?.get("action"))
        assertEquals("ok", rebuilt?.get("output")?.jsonObject?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `非 OpenAI Responses 协议的服务端工具不回传`() {
        val tool = UIMessagePart.ServerTool(
            toolCallId = "srvtoolu_1",
            toolName = "web_search",
            input = buildJsonObject { put("query", "x") },
            status = ServerToolStatus.COMPLETED,
            metadata = ServerToolMetadata(
                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                call = buildJsonObject { put("type", "server_tool_use") },
            ).toMetadata(),
        )

        val items = items(UIMessagePart.Text("hi"), tool)

        assertTrue(items.all { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == null })
        assertEquals(1, items.size)
    }
}
