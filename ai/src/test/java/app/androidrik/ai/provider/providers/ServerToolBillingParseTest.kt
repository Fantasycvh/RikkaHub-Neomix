package app.androidrik.ai.provider.providers

import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.core.serverToolUseCounts
import app.androidrik.ai.provider.providers.claude.ClaudeStreamDecoder
import app.androidrik.ai.provider.providers.openai.ChatCompletionsAPI
import app.androidrik.ai.provider.providers.openai.ResponseAPI
import app.androidrik.ai.provider.providers.openai.ResponseApiStreamDecoder
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.common.http.KeyRoulette
import app.androidrik.ai.util.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DeepSeek `usage.server_tool_use` 计费次数在三种协议下的解析守卫。
 *
 * 背景：此前只有 Chat Completions 解析该字段，Responses / Anthropic 兼容端点换协议接入后
 * 统计页工具计费恒为 0。这里逐条覆盖非流式与流式的 usage 入口，防止再次漏字段。
 */
class ServerToolBillingParseTest {

    private val okHttp = OkHttpClient()

    private fun assertCounters(usage: TokenUsage, search: Int, fetch: Int, imageSearch: Int) {
        assertEquals(search, usage.toolSearch)
        assertEquals(fetch, usage.toolFetch)
        assertEquals(imageSearch, usage.toolImageSearch)
    }

    private fun invokeParseTokenUsage(target: Any, raw: String): TokenUsage {
        val method = target.javaClass.getDeclaredMethod("parseTokenUsage", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(target, json.parseToJsonElement(raw).jsonObject) as TokenUsage
    }

    @Test
    fun `chat completions non-stream parses server tool counters`() {
        val usage = invokeParseTokenUsage(
            ChatCompletionsAPI(okHttp, KeyRoulette.default()),
            """{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"server_tool_use":{"tool_search":3,"tool_fetch":1,"tool_image_search":2}}""",
        )
        assertCounters(usage, search = 3, fetch = 1, imageSearch = 2)
    }

    @Test
    fun `responses non-stream parses server tool counters`() {
        val usage = invokeParseTokenUsage(
            ResponseAPI(okHttp),
            """{"input_tokens":1,"output_tokens":2,"total_tokens":3,"server_tool_use":{"tool_search":4,"tool_fetch":5,"tool_image_search":6}}""",
        )
        assertCounters(usage, search = 4, fetch = 5, imageSearch = 6)
    }

    @Test
    fun `claude non-stream parses server tool counters`() {
        val usage = invokeParseTokenUsage(
            ClaudeProvider(okHttp),
            """{"usage":{"input_tokens":10,"output_tokens":2,"server_tool_use":{"tool_search":7,"tool_fetch":8,"tool_image_search":9}}}""",
        )
        assertCounters(usage, search = 7, fetch = 8, imageSearch = 9)
    }

    @Test
    fun `responses stream parses server tool counters`() {
        val result = ResponseApiStreamDecoder().accept(
            SseEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"id":"r","status":"completed","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3,"server_tool_use":{"tool_search":3,"tool_fetch":1,"tool_image_search":2}}}}""",
            ),
        )
        val usage = result.chunks.filterIsInstance<StreamChunk.Usage>().single().usage
        assertCounters(usage, search = 3, fetch = 1, imageSearch = 2)
    }

    @Test
    fun `claude stream parses server tool counters`() {
        val result = ClaudeStreamDecoder().accept(
            SseEvent(
                event = "message_delta",
                data = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":10,"output_tokens":2,"server_tool_use":{"tool_search":7,"tool_fetch":8,"tool_image_search":9}}}""",
            ),
        )
        val usage = result.chunks.filterIsInstance<StreamChunk.Usage>().single().usage
        assertCounters(usage, search = 7, fetch = 8, imageSearch = 9)
    }

    @Test
    fun `usage without server tool field yields zero counters`() {
        val counts = json.parseToJsonElement("""{"prompt_tokens":1,"completion_tokens":2}""")
            .jsonObject.serverToolUseCounts()
        assertEquals(0, counts.search)
        assertEquals(0, counts.fetch)
        assertEquals(0, counts.imageSearch)
    }
}
