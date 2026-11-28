package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.DeferredToolProtocol
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ResponseApiDeferredToolRegressionTest {
    private fun event(type: String, fields: String) = SseEvent(event = type, data = """{"type":"$type",$fields}""")
    private fun added(id: String, args: String = "{}") = event("response.output_item.added",
        """"item":{"type":"tool_search_call","id":"item_$id","call_id":"$id","execution":"client","arguments":$args}""")
    private fun done(id: String, args: String) = event("response.output_item.done",
        """"item":{"type":"tool_search_call","id":"item_$id","call_id":"$id","execution":"client","arguments":$args}""")
    private fun messages(chunks: List<StreamChunk>): List<UIMessage> {
        val handler = StreamChunkHandler()
        return chunks.fold(listOf(UIMessage.user("weather"))) { history, chunk -> handler.handle(history, chunk) }
    }

    @Test fun `empty initial object does not hide completed query`() {
        val decoder = ResponseApiStreamDecoder()
        val start = decoder.accept(added("call_1"))
        assertTrue(start.chunks.none { it is StreamChunk.ToolCallDelta })
        val finish = decoder.accept(done("call_1", """{"query":"weather"}"""))
        val tool = messages(start.chunks + finish.chunks).last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"query":"weather"}""", tool.input)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("item_call_1", tool.metadataAs<DeferredToolSearchCallMetadata>()?.itemId)
    }

    @Test fun `final snapshot replaces partial deltas and is emitted only once`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = mutableListOf<StreamChunk>()
        chunks += decoder.accept(added("call_1")).chunks
        chunks += decoder.accept(event("response.tool_search_call_arguments.delta",
            """"item_id":"item_call_1","delta":"{\"query\":\"part"""")).chunks
        chunks += decoder.accept(event("response.tool_search_call_arguments.done",
            """"item_id":"item_call_1","arguments":{"query":"snapshot"}""")).chunks
        chunks += decoder.accept(done("call_1", """{"query":"weather"}""")).chunks
        chunks += decoder.accept(done("call_1", """{"query":"weather"}""")).chunks
        val tool = messages(chunks).last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"query":"weather"}""", tool.input)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallDelta>().size)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
    }

    @Test fun `interleaved searches keep independent final arguments`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = listOf(added("a"), added("b"), done("b", """{"names":["calendar"]}"""),
            done("a", """{"query":"weather"}""")).flatMap { decoder.accept(it).chunks }
        val tools = messages(chunks).last().parts.filterIsInstance<UIMessagePart.Tool>().associateBy { it.toolCallId }
        assertEquals("""{"query":"weather"}""", tools.getValue("a").input)
        assertEquals("""{"names":["calendar"]}""", tools.getValue("b").input)
    }

    @Test fun `terminal event flushes argument snapshot when output item done is absent`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = listOf(added("call_1"), event("response.function_call_arguments.done",
            """"item_id":"item_call_1","arguments":"{\"query\":\"weather\"}""""),
            event("response.completed", """"response":{"status":"completed"}"""))
            .flatMap { decoder.accept(it).chunks }
        assertEquals("""{"query":"weather"}""", messages(chunks).last().parts.filterIsInstance<UIMessagePart.Tool>().single().input)
    }

    private fun tool(name: String, deferred: Boolean = true) = Tool(name = name, description = name,
        deferLoading = deferred, execute = { emptyList() })
    private fun search(id: String, names: List<String>) = UIMessagePart.Tool(
        toolCallId = id, toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME, input = "{}",
        output = listOf(UIMessagePart.Text("Loaded", metadata = DeferredToolSearchResultMetadata(names).toMetadata())))

    @Test fun `replay deduplicates schemas across parallel calls and later messages`() {
        val api = ResponseAPI(OkHttpClient())
        val first = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            search("a", listOf("weather", "weather", "eager", "removed")), search("b", listOf("weather", "calendar"))))
        val second = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(search("c", listOf("calendar", "weather"))))
        val tools = listOf(tool("weather"), tool("calendar"), tool("eager", false)).associateBy { it.name }
        fun replay(history: List<UIMessage>) = api.buildMessages(history,
            deferredProtocol = DeferredToolProtocol.RESPONSES_TOOL_SEARCH, toolsByName = tools)
        val history = listOf(UIMessage.user("weather"), first, UIMessage.user("again"), second)
        val input = replay(history)
        val outputs = input.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "tool_search_output" }
        assertEquals(listOf("a", "b", "c"), outputs.map { it["call_id"]!!.jsonPrimitive.content })
        assertEquals(listOf(listOf("weather"), listOf("calendar"), emptyList<String>()), outputs.map { output ->
            output["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        })
        assertEquals(input, replay(history)) // A new HTTP request must replay definitions again.
        val trimmed = replay(listOf(UIMessage.user("trimmed"), second))
        val reloaded = trimmed.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "tool_search_output" }
        assertEquals(2, reloaded["tools"]!!.jsonArray.size) // Removed history must not leave stale loading state.
        assertEquals(listOf("calendar", "weather"), second.parts.filterIsInstance<UIMessagePart.Tool>().single()
            .output.single().metadataAs<DeferredToolSearchResultMetadata>()?.loadedToolNames)
    }
}
