package app.androidrik.ai.provider.providers.google

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.common.http.CustomBody
import app.androidrik.common.http.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.GoogleProvider
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isProviderSigned
import app.androidrik.ai.util.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class InteractionsApiTest {
    private val vertex = ProviderSetting.Google(
        vertexAI = true,
        useServiceAccount = true,
        location = "global",
        projectId = "test-project",
        serviceAccountEmail = "test@example.invalid",
    )
    private val params = TextGenerationParams(
        model = Model(modelId = "gemini-4-hydrogen", abilities = listOf(ModelAbility.REASONING)),
    )
    private val api = InteractionsAPI(OkHttpClient()) { _, request -> request }

    @Test
    fun `legacy Google settings default to generateContent and only Vertex multi regions expose Interactions`() {
        assertFalse(json.decodeFromString<ProviderSetting.Google>("{}").useInteractionsApi)
        assertFalse(vertex.copy(vertexAI = false).supportsInteractionsApi)
        assertFalse(vertex.copy(useServiceAccount = false).supportsInteractionsApi)
        assertFalse(vertex.copy(location = "us-central1").supportsInteractionsApi)
        assertEquals("aiplatform.googleapis.com", vertex.interactionsUrl().host)
        assertEquals("aiplatform.us.rep.googleapis.com", vertex.copy(location = "us").interactionsUrl().host)
        assertEquals("aiplatform.eu.rep.googleapis.com", vertex.copy(location = "eu").interactionsUrl().host)
        assertEquals("/v1/projects/test-project/locations/global/interactions", vertex.interactionsUrl().encodedPath)
    }

    @Test
    fun `Hydrogen uses low for OFF and custom body cannot enable server storage or change transport`() {
        val body = api.buildRequestBody(
            vertex,
            listOf(UIMessage.user("Test")),
            params.copy(customBody = listOf(
                CustomBody("store", JsonPrimitive(true)),
                CustomBody("stream", JsonPrimitive(false)),
                CustomBody("background", JsonPrimitive(true)),
                CustomBody("previous_interaction_id", JsonPrimitive("older")),
            )),
            stream = true,
        )
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertNull(body["background"])
        assertNull(body["previous_interaction_id"])
        assertEquals(JsonPrimitive("low"), body["generation_config"]?.jsonObject?.get("thinking_level"))
    }

    @Test
    fun `parameterless function tools omit schema instead of sending JSON null`() {
        val body = api.buildRequestBody(
            vertex,
            listOf(UIMessage.user("Use the get_current_time tool.")),
            params.copy(
                model = params.model.copy(abilities = params.model.abilities + ModelAbility.TOOL),
                tools = listOf(Tool("get_current_time", "Get the current date and time.") { emptyList() }),
            ),
            stream = false,
        )
        val function = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("get_current_time"), function["name"])
        assertFalse("Vertex rejects a JSON null schema", function.containsKey("parameters"))
    }

    @Test
    fun `stored chats only send a delta when the local prefix and conversation still match`() {
        val stored = vertex.copy(storeInteractions = true)
        val chat = params.copy(sessionId = "chat-a")
        val firstUser = UIMessage.user("Remember red.")
        val first = api.planRequest(stored, listOf(firstUser), chat, stream = true)
        assertEquals(JsonPrimitive(true), first.body["store"])
        assertNull(first.body["previous_interaction_id"])
        val answer = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("OK.")),
            vertexInteraction = first.checkpoint("interaction-a", "completed", "OK."),
        )
        val secondUser = UIMessage.user("Which color?")
        val secondMessages = listOf(firstUser, answer, secondUser)
        val second = api.planRequest(stored, secondMessages, chat, stream = false)
        assertEquals(JsonPrimitive("interaction-a"), second.body["previous_interaction_id"])
        assertEquals(api.buildInput(listOf(secondUser)), second.body["input"])
        assertEquals(api.buildInput(secondMessages), second.fullBody["input"])
        assertTrue(second.shouldRestart(InteractionsRequestException(400, "Previous Interaction ID not found")))
        assertFalse(second.shouldRestart(InteractionsRequestException(400, "Bad input")))

        val alternatives = listOf(
            listOf(UIMessage.user("Remember blue."), answer, secondUser),
            listOf(firstUser, answer.copy(parts = listOf(UIMessagePart.Text("Edited."))), secondUser),
            listOf(firstUser, answer.copy(vertexInteraction = answer.vertexInteraction!!.copy(expiresAtMillis = 0)), secondUser),
        )
        alternatives.forEach { messages ->
            assertNull(api.planRequest(stored, messages, chat, stream = false).body["previous_interaction_id"])
        }
        assertNull(api.planRequest(stored, secondMessages, chat.copy(sessionId = "chat-b"), stream = false)
            .body["previous_interaction_id"])
        assertEquals(JsonPrimitive(false), api.planRequest(stored, secondMessages, params, stream = false).body["store"])
    }

    @Test
    fun `a stale previous interaction retries once with the full local history`() = runBlocking {
        val requests = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val responses = ArrayDeque(listOf(
            200 to """{"id":"old-id","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"OK."}]}]}""",
            400 to """[{"error":{"message":"Previous Interaction ID not found"}}]""",
            200 to """{"id":"new-id","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Red."}]}]}""",
        ))
        val instance = InteractionsAPI(OkHttpClient.Builder().addInterceptor { chain ->
            val data = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            requests += json.parseToJsonElement(data).jsonObject
            val (code, body) = responses.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()) { _, request -> request }
        val stored = vertex.copy(storeInteractions = true)
        val chat = params.copy(sessionId = "chat-a")
        val user = UIMessage.user("Remember red.")
        val first = instance.generateText(stored, listOf(user), chat)
        val followUp = UIMessage.user("Which color?")
        val result = instance.generateText(stored, listOf(user, first.message, followUp), chat)
        assertEquals("Red.", result.message.toText())
        assertEquals("new-id", result.message.vertexInteraction?.id)
        assertEquals(JsonPrimitive("old-id"), requests[1]["previous_interaction_id"])
        assertEquals(1, requests[1].getValue("input").jsonArray.size)
        assertNull(requests[2]["previous_interaction_id"])
        assertEquals(3, requests[2].getValue("input").jsonArray.size)
    }

    @Test
    fun `research uses the global background endpoint and polls and cancels the same task`() = runBlocking {
        val requests = mutableListOf<Request>()
        val responses = ArrayDeque(listOf(
            """{"id":"research-1","status":"in_progress"}""",
            """{"id":"research-1","status":"in_progress","steps":[{"type":"thought","summary":[{"type":"text","text":"Comparing sources"}]}]}""",
            """{"id":"research-1","status":"cancelled"}""",
        ))
        val research = InteractionsAPI(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responses.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()) { _, request -> request.newBuilder().header("Authorization", "Bearer fixture").build() }

        val started = research.startResearch(vertex, "Compare published release notes.")
        val progress = research.getResearch(vertex, started.id)
        val cancelled = research.cancelResearch(vertex, started.id)
        assertEquals("in_progress", started.status)
        assertEquals("Comparing sources", progress.progress)
        assertTrue(cancelled.isTerminal)
        assertEquals("cancelled", cancelled.status)
        val root = "/v1beta1/projects/test-project/locations/global/interactions"
        assertEquals(listOf("POST", "GET", "POST"), requests.map { it.method })
        assertEquals(listOf(root, "$root/research-1", "$root/research-1/cancel"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.header("Authorization") == "Bearer fixture" })
        val sent = Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()
        val body = json.parseToJsonElement(sent).jsonObject
        assertEquals(JsonPrimitive("deep-research-preview-04-2026"), body["agent"])
        assertEquals(JsonPrimitive(true), body["background"])
        assertEquals(JsonPrimitive(true), body["store"])
        assertNull(body["model"])
    }

    @Test
    fun `completed research snapshots expose the report and citations separately from progress`() {
        val snapshot = json.parseToJsonElement("""
            {"id":"saved-id","status":"completed","steps":[
              {"type":"thought","summary":[{"type":"text","text":"Comparing sources"}]},
              {"type":"model_output","content":[{"type":"text","text":"Report","annotations":[
                {"type":"url_citation","title":"Source","url":"https://example.com"}
              ]}]}
            ]}
        """.trimIndent()).jsonObject.parseVertexResearch()
        assertTrue(snapshot.isTerminal)
        assertEquals("Report", snapshot.report)
        assertEquals("Comparing sources", snapshot.progress)
        assertEquals("https://example.com", snapshot.citations.single().url)
    }

    @Test
    fun `stream persists a thought signature and rebuilds tool history after action`() {
        val decoder = InteractionsStreamDecoder(params.model.modelId)
        val chunks = listOf(
            """{"event_type":"interaction.created","interaction":{"id":"interaction-1","status":"in_progress"}}""",
            """{"event_type":"step.start","index":0,"step":{"type":"thought"}}""",
            """{"event_type":"step.delta","index":0,"delta":{"type":"thought_signature","signature":"signed-thought"}}""",
            """{"event_type":"step.stop","index":0}""",
            """{"event_type":"step.start","index":1,"step":{"type":"function_call","id":"call-1","name":"lookup","arguments":{}}}""",
            """{"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"{\"label\":\"hydrogen\"}"}}""",
            """{"event_type":"step.stop","index":1}""",
            """{"event_type":"interaction.completed","interaction":{"status":"requires_action","usage":{"total_input_tokens":10,"total_output_tokens":2,"total_thought_tokens":3,"total_tokens":15}}}""",
            "[DONE]",
        ).flatMap { decoder.accept(SseEvent(data = it)).chunks } + decoder.onClosed()

        assertEquals("requires_action", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertEquals(5, chunks.filterIsInstance<StreamChunk.Usage>().single().usage.completionTokens)
        val handler = StreamChunkHandler()
        val user = UIMessage.user("Look it up")
        val assistant = chunks.fold(listOf(user), handler::handle).last()
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        val call = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(reasoning.isProviderSigned())
        assertEquals("call-1", call.toolCallId)
        assertEquals(JsonPrimitive("hydrogen"), call.inputAsJson().jsonObject["label"])

        val executed = assistant.copy(parts = listOf(reasoning, call.copy(output = listOf(UIMessagePart.Text("123")))))
        val reminder = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("Keep it brief")))
        val input = api.buildInput(listOf(reminder, user, executed, reminder.copy(id = UIMessage.user("").id)))
        val steps = input.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user_input", "thought", "function_call", "function_result", "user_input"),
            steps.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(JsonPrimitive("signed-thought"), steps[1]["signature"])
        assertEquals(JsonPrimitive("call-1"), steps[3]["call_id"])
        assertTrue(steps.last().toString().contains("<system-reminder>"))
    }

    @Test
    fun `early EOF does not finish a streamed response`() {
        val decoder = InteractionsStreamDecoder()
        val chunks = decoder.accept(SseEvent(
            data = """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"Partial"}}""",
        )).chunks + decoder.onClosed()
        assertEquals("Partial", chunks.filterIsInstance<StreamChunk.TextDelta>().single().text)
        assertTrue(chunks.none { it is StreamChunk.Finish })
    }

    @Test
    fun `Google provider selects endpoint and sends bearer and merged headers for Interactions`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (body, mediaType) = when {
                request.url.host == "oauth2.googleapis.com" ->
                    """{"access_token":"fixture-token","expires_in":3600}""" to "application/json"
                request.url.encodedPath.endsWith("/interactions") &&
                    request.header("Accept") == "text/event-stream" -> """
                        data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"streamed"}}

                        data: {"event_type":"interaction.completed","interaction":{"status":"completed"}}

                    """.trimIndent().plus("\n") to "text/event-stream"
                request.url.encodedPath.endsWith("/interactions") ->
                    """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"interactions"}]}]}""" to "application/json"
                else -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"legacy"}]}}]}""" to "application/json"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", mediaType).body(body.toResponseBody(mediaType.toMediaType())).build()
        }.build()
        val provider = GoogleProvider(client)
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val configured = vertex.copy(
            privateKey = Base64.getEncoder().encodeToString(key.encoded),
            useInteractionsApi = true,
            customHeaders = listOf(CustomHeader("X-Test", "provider"), CustomHeader("X-Provider-Only", "kept")),
        )
        val messages = listOf(UIMessage.user("Test"))
        val requestParams = params.copy(customHeaders = listOf(CustomHeader("x-test", "request")))
        assertEquals("interactions", provider.generateText(configured, messages, requestParams).message.toText())
        val stream = provider.streamText(configured, messages, requestParams).toList()
        assertEquals("streamed", stream.filterIsInstance<StreamChunk.TextDelta>().single().text)
        assertEquals("completed", stream.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertEquals("legacy", provider.generateText(configured.copy(location = "us-central1"), messages, params).message.toText())
        assertEquals("legacy", provider.generateText(configured.copy(useInteractionsApi = false), messages, params).message.toText())

        val interactions = requests.filter { it.url.encodedPath.endsWith("/interactions") }
        assertEquals(2, interactions.size)
        for (request in interactions) {
            assertEquals(listOf("Bearer fixture-token"), request.headers.values("Authorization"))
            assertEquals(listOf("request"), request.headers.values("X-Test"))
            assertEquals("kept", request.header("X-Provider-Only"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals(JsonPrimitive(false), json.parseToJsonElement(body).jsonObject["store"])
        }
    }
}
