package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import app.androidrik.rikkahub.data.model.MediaCreationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatMediaToolTest {
    private fun args(prompt: String = "edit", reference: JsonElement? = null) = buildJsonObject {
        put("prompt", prompt)
        reference?.let { put("reference_images", it) }
    }

    private fun text(parts: List<UIMessagePart>) = (parts.single() as UIMessagePart.Text).text

    @Test
    fun `repeated pending request returns its record without another paid submission`() = runBlocking {
        var submissions = 0
        val tool = createImageTools(Assistant(), generateMedia = { _, _, _, _ ->
            submissions++
            """{"ok":true,"record_id":"pending","status":"RUNNING"}"""
        }, generate = { _, _, _ -> error("legacy path") }).single()
        val first = text(tool.execute(args()))
        repeat(3) { assertEquals(first, text(tool.execute(args()))) }
        assertEquals(1, submissions)
        tool.execute(args("another picture"))
        assertTrue(text(tool.execute(args("third picture"))).contains("too_many_calls"))
        assertEquals(2, submissions)
    }

    @Test
    fun `references reject paths malformed arrays and images outside the current branch`() = runBlocking {
        val submitted = mutableListOf<List<String>>()
        val tool = createImageTools(Assistant(), referenceIds = { setOf("selected-image") },
            generateMedia = { _, _, _, refs -> submitted.add(refs); "accepted" },
            generate = { _, _, _ -> error("legacy path") }).single()
        listOf(JsonPrimitive("selected-image"), JsonArray(listOf(JsonPrimitive(3))),
            JsonArray(listOf(JsonPrimitive("/data/user/0/app/files/secret.png"))),
            JsonArray(listOf(JsonPrimitive("other-branch")))).forEach {
            assertTrue(text(tool.execute(args(reference = it))).contains("invalid_references"))
        }
        assertTrue(submitted.isEmpty())
        tool.execute(args(reference = JsonArray(listOf(JsonPrimitive("selected-image")))))
        assertEquals(listOf(listOf("selected-image")), submitted)
    }

    @Test(expected = CancellationException::class)
    fun `cancelling chat propagates cancellation instead of encouraging a retry`() = runBlocking {
        createImageTools(Assistant(), generateMedia = { _, _, _, _ -> throw CancellationException("stopped") },
            generate = { _, _, _ -> emptyList() }).single().execute(args())
        Unit
    }

    @Test
    fun `unknown outcomes carry the durable record and prohibit automatic retry`() {
        val record = MediaCreationRecord(sessionId = Uuid.random(), providerId = Uuid.random(),
            providerName = "Fixture", modelId = "image", kind = MediaKind.IMAGE, prompt = "draw",
            status = MediaCreationStatus.SUBMIT_UNKNOWN)
        val content = Json.parseToJsonElement(imageToolMediaResult(record) { it }).jsonObject
        assertFalse(content.getValue("ok").jsonPrimitive.boolean)
        assertEquals(record.id.toString(), content.getValue("record_id").jsonPrimitive.content)
        assertTrue(content.getValue("note").jsonPrimitive.content.contains("Do not retry automatically"))
        assertTrue(parseGeneratedImagePaths(content).isEmpty())
    }
}
