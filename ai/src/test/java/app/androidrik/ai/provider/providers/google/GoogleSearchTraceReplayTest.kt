package app.androidrik.ai.provider.providers.google

import app.androidrik.ai.provider.Model
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.providers.GoogleProvider
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.GoogleThoughtMetadata
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.util.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded by upstream d3f128f34. Replay the request/response boundary tested by 14d60e44e. */
class GoogleSearchTraceReplayTest {
    @Test
    fun `mixed client function and Google Search signatures survive the next request`() {
        val call = Trace("gemini-search-tool")
        val search = Trace("gemini-search-continue")
        val initial = replay(listOf(UIMessage.user("Read memory, then search.")), call)
        val function = initial.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        val expectedTool = call.expectedParts.single { it.stringOrNull("type") == "tool" }
        assertEquals(expectedTool.stringOrNull("name"), function.toolName)
        assertEquals(expectedTool["input"], function.inputAsJson())

        val executed = initial.last().copy(parts = initial.last().parts.map {
            if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("Denis Villeneuve"))) else it
        })
        val completed = replay(initial.dropLast(1) + executed, search)
        val serverTools = completed.last().parts.filterIsInstance<UIMessagePart.ServerTool>()
        val expectedSearchIds = search.expectedParts.filter { it.stringOrNull("type") == "server_tool" }
            .map { it.stringOrNull("id") }
        assertEquals(expectedSearchIds, serverTools.map { it.toolCallId })
        assertTrue(serverTools.all { it.status == ServerToolStatus.COMPLETED })
        assertEquals(expectedTool.stringOrNull("id"), function.toolCallId)

        val finalText = completed.last().parts.last() as UIMessagePart.Text
        assertTrue(finalText.text.isNotBlank())
        assertTrue(
            "The final text must carry the signature from the standalone trailing part",
            finalText.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature ==
                search.wireParts.last()["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
        )

        val contents = buildContents(completed).map { it.jsonObject }
        assertEquals(listOf("user", "model", "user", "model"), contents.map { it.stringOrNull("role") })
        val sentParts = contents.flatMap { it.getValue("parts").jsonArray.map { part -> part.jsonObject } }
        val wireParts = call.wireParts + search.wireParts
        assertEquals(function.toolCallId, sentParts.single { "functionCall" in it }
            .getValue("functionCall").jsonObject.stringOrNull("id"))
        assertEquals(function.toolCallId, sentParts.single { "functionResponse" in it }
            .getValue("functionResponse").jsonObject.stringOrNull("id"))
        assertTrue(
            "Every original signature must be sent in order",
            wireParts.mapNotNull { it.stringOrNull("thoughtSignature") } ==
                sentParts.mapNotNull { it.stringOrNull("thoughtSignature") },
        )
        assertTrue(
            "The Google Search call/result blocks must survive verbatim",
            wireParts.filter { "toolCall" in it || "toolResponse" in it } ==
                sentParts.filter { "toolCall" in it || "toolResponse" in it },
        )
    }

    @Test
    fun `nonstream response also pairs search results and retains final signature`() {
        val trace = Trace("gemini-search-continue")
        val provider = GoogleProvider(OkHttpClient())
        val parse = GoogleProvider::class.java.getDeclaredMethod("parseMessageParts", JsonArray::class.java)
        parse.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val parts = parse.invoke(provider, JsonArray(trace.wireParts)) as List<UIMessagePart>
        val serverTools = parts.filterIsInstance<UIMessagePart.ServerTool>()
        val count = trace.expectedParts.count { it.stringOrNull("type") == "server_tool" }
        assertEquals(count, serverTools.size)
        assertTrue(serverTools.all { it.status == ServerToolStatus.COMPLETED && it.output != null })
        val messages = listOf(UIMessage.user("Search."), UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        val sentParts = buildContents(messages).last().jsonObject.getValue("parts").jsonArray.map { it.jsonObject }
        assertTrue(
            "Raw search blocks must survive nonstream parsing and serialization",
            trace.wireParts.filter { "toolCall" in it || "toolResponse" in it } ==
                sentParts.filter { "toolCall" in it || "toolResponse" in it },
        )
        val answer = sentParts.last()
        assertTrue(answer.stringOrNull("text").orEmpty().isNotBlank())
        assertTrue(answer.stringOrNull("thoughtSignature") == trace.wireParts.last().stringOrNull("thoughtSignature"))
    }

    private fun replay(messages: List<UIMessage>, trace: Trace): List<UIMessage> {
        val decoder = GoogleStreamDecoder(trace.name, "gemini-4-carbon")
        val handler = StreamChunkHandler(Model(modelId = "gemini-4-carbon"))
        val chunks = trace.events.flatMap { decoder.accept(it).chunks } + decoder.onClosed()
        assertEquals(1, chunks.count { it is StreamChunk.Finish })
        return chunks.fold(messages, handler::handle)
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents", List::class.java, Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(GoogleProvider(OkHttpClient()), messages, true) as JsonArray
    }

    private class Trace(val name: String) {
        val events = resource("events.jsonl").lineSequence().filter(String::isNotBlank)
            .map { json.decodeFromString<SseEvent>(it) }.toList()
        val expectedParts = json.parseToJsonElement(resource("expected.json")).jsonObject.getValue("parts")
            .jsonArray.map { it.jsonObject }
        val wireParts: List<JsonObject> = events.flatMap { event ->
            json.parseToJsonElement(event.data).jsonObject["candidates"]?.jsonArray.orEmpty().flatMap { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty().map { it.jsonObject }
            }
        }

        private fun resource(file: String): String = checkNotNull(javaClass.classLoader?.getResource(
            "stream-traces/generated/google-generateContent/$name/$file"
        )).readText()
    }
}
