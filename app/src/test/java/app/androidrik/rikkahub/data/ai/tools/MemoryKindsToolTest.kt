package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class MemoryKindsToolTest {
    private var memory = AssistantMemory("task-id", "submit homework", revision = 3,
        kind = MemoryKind.TASK, taskStatus = MemoryTaskStatus.OPEN)
    private var receivedKind: MemoryKind? = null
    private var receivedDue: Long? = null
    private var completions = 0
    private val proposals = mutableListOf<MemoryConsolidationProposal>()

    private fun tool(propose: Boolean = false, initialVisible: Boolean = true): Tool = buildMemoryTools(
        json = Json,
        onCreation = { _, _, _ -> error("legacy create must not run") },
        onUpdate = { _, _, _, _ -> error("legacy update must not run") },
        onDelete = {},
        typedOnCreation = { content, _, _, kind, due ->
            receivedKind = kind; receivedDue = due
            memory.copy(content = content, kind = kind, dueAt = due)
        },
        typedVersionedOnUpdate = { _, content, _, _, revision, kind, due ->
            assertEquals(3L, revision)
            receivedKind = kind; receivedDue = due
            val nextKind = kind ?: memory.kind
            memory.copy(content = content, kind = nextKind,
                dueAt = if (nextKind == MemoryKind.TASK) due ?: memory.dueAt else 0L,
                taskStatus = if (nextKind == MemoryKind.TASK) memory.taskStatus else MemoryTaskStatus.OPEN)
        },
        versionedOnComplete = { _, revision ->
            completions++
            check(revision == memory.revision) { "revision conflict" }
            check(memory.kind == MemoryKind.TASK) { "not a task" }
            if (memory.taskStatus != MemoryTaskStatus.DONE) {
                memory = memory.copy(taskStatus = MemoryTaskStatus.DONE, revision = revision + 1)
            }
            memory
        },
        versionedOnDrop = { _, revision ->
            check(revision == memory.revision) { "revision conflict" }
            check(memory.kind == MemoryKind.TASK) { "not a task" }
            if (memory.taskStatus != MemoryTaskStatus.DROPPED) {
                memory = memory.copy(taskStatus = MemoryTaskStatus.DROPPED, revision = revision + 1)
            }
            memory
        },
        onRead = { memory },
        onSearch = { _, _ -> listOf(memory) },
        initialRevisions = if (initialVisible) mapOf(memory.id to memory.revision) else emptyMap(),
        proposalSink = if (propose) ({ proposals += it }) else null,
    ).single()

    private suspend fun call(tool: Tool, action: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val result = tool.execute(buildJsonObject { put("action", action); extra() })
        return Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `create defaults topic and unknown kind falls back`() = runBlocking {
        val tool = tool()
        call(tool, "create") { put("content", "likes tea") }
        assertEquals(MemoryKind.TOPIC, receivedKind)
        assertEquals(0L, receivedDue)
        call(tool, "create") { put("content", "likes tea"); put("kind", "future-kind") }
        assertEquals(MemoryKind.TOPIC, receivedKind)
    }

    @Test fun `task due is strict local midnight and receipts expose metadata`() = runBlocking {
        val tool = tool()
        val result = call(tool, "create") {
            put("content", "submit homework"); put("kind", "task"); put("due", "2026-10-23")
        }
        assertEquals(LocalDate.of(2026, 10, 23).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), receivedDue)
        assertEquals("task", result["kind"]?.jsonPrimitive?.content)
        assertEquals("2026-10-23", result["due"]?.jsonPrimitive?.content)
        for (date in listOf("2026-02-30", "2026-2-03", "2026-10-23T10:00", " tomorrow")) {
            assertTrue(runCatching { call(tool, "create") {
                put("content", "submit homework"); put("kind", "task"); put("due", date)
            } }.isFailure)
        }
    }

    @Test fun `edit omission preserves and empty due clears through typed callback`() = runBlocking {
        val tool = tool()
        call(tool, "edit") { put("id", memory.id); put("content", "homework"); put("expected_revision", 3) }
        assertNull(receivedKind); assertNull(receivedDue)
        call(tool, "edit") {
            put("id", memory.id); put("content", "homework"); put("expected_revision", 3)
            put("due", ""); put("kind", "topic")
        }
        assertEquals(MemoryKind.TOPIC, receivedKind); assertEquals(0L, receivedDue)
    }

    @Test fun `complete uses CAS without redundant read and repeat is idempotent`() = runBlocking {
        val tool = tool(initialVisible = false)
        val first = call(tool, "complete") { put("id", memory.id); put("expected_revision", 3) }
        assertEquals("done", first["task_status"]?.jsonPrimitive?.content)
        assertEquals(4L, first["revision"]?.jsonPrimitive?.longOrNull)
        val second = call(tool, "complete") { put("id", memory.id); put("expected_revision", 4) }
        assertEquals(4L, second["revision"]?.jsonPrimitive?.longOrNull)
        assertEquals(2, completions)
        assertTrue(runCatching { call(tool, "complete") { put("id", memory.id); put("expected_revision", 3) } }.isFailure)
    }

    @Test fun `complete requires positive revision delegates CAS and rejects non-task`() = runBlocking {
        val tool = tool()
        assertTrue(runCatching { call(tool, "complete") { put("id", memory.id); put("expected_revision", 0) } }.isFailure)
        assertEquals(0, completions)
        val stale = runCatching { call(tool, "complete") { put("id", memory.id); put("expected_revision", 99) } }.exceptionOrNull()
        assertEquals("revision conflict", stale?.message)
        assertEquals(1, completions)
        memory = memory.copy(kind = MemoryKind.TOPIC)
        val failure = runCatching { call(tool, "complete") { put("id", memory.id); put("expected_revision", 3) } }.exceptionOrNull()
        assertEquals("not a task", failure?.message)
    }

    @Test fun `proposal payload includes kind and explicit clear and completion never writes directly`() = runBlocking {
        val tool = tool(propose = true)
        val receipt = call(tool, "edit") {
            put("id", memory.id); put("content", "homework"); put("expected_revision", 3)
            put("kind", "task"); put("due", "")
        }
        assertEquals("task", proposals.single().kind)
        assertEquals(0L, proposals.single().dueAt)
        assertEquals("", receipt["due"]?.jsonPrimitive?.content)
        call(tool, "complete") { put("id", memory.id); put("expected_revision", 3) }
        assertEquals(0, completions); assertEquals(2, proposals.size)
        assertEquals("complete", proposals.last().action)
    }

    @Test fun `status proposals require observed revision and never become replacement dependencies`() = runBlocking {
        for (action in listOf("complete", "drop")) {
            proposals.clear()
            val tool = tool(propose = true, initialVisible = false)
            assertTrue(runCatching { call(tool, action) { put("id", memory.id); put("expected_revision", 3) } }.isFailure)
            call(tool, "read") { put("id", memory.id) }
            val receipt = call(tool, action) { put("id", memory.id); put("expected_revision", 3) }
            assertFalse(receipt["applied"]!!.jsonPrimitive.boolean)
            assertEquals("task", receipt["kind"]!!.jsonPrimitive.content)
            assertEquals(if (action == "complete") "done" else "dropped", receipt["task_status"]!!.jsonPrimitive.content)
            val operation = receipt["operation_id"]!!.jsonPrimitive.content
            assertTrue(runCatching { call(tool, "delete") {
                put("id", memory.id); put("expected_revision", 3)
                put("depends_on", buildJsonArray { add(operation) })
            } }.isFailure)
            assertEquals(listOf(action), proposals.map { it.action })
        }
    }

    @Test fun `drop directly uses status callback and repeats idempotently`() = runBlocking {
        val tool = tool(initialVisible = false)
        val first = call(tool, "drop") { put("id", memory.id); put("expected_revision", 3) }
        assertEquals("dropped", first["task_status"]!!.jsonPrimitive.content)
        val second = call(tool, "drop") { put("id", memory.id); put("expected_revision", 4) }
        assertEquals(4L, second["revision"]!!.jsonPrimitive.long)
    }

    @Test fun `read and search return kind status and due`() = runBlocking {
        memory = memory.copy(dueAt = LocalDate.of(2026, 10, 23).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val tool = tool()
        val read = call(tool, "read") { put("id", memory.id) }
        val search = call(tool, "search") { put("query", "homework") }["records"]!!.jsonArray.single().jsonObject
        for (record in listOf(read, search)) {
            assertEquals("task", record["kind"]?.jsonPrimitive?.content)
            assertEquals("open", record["task_status"]?.jsonPrimitive?.content)
            assertEquals("2026-10-23", record["due"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `complete approval follows modify not create`() {
        for (action in listOf("complete", "drop")) {
            assertTrue(memoryToolNeedsApproval(Assistant(memoryModifyApproval = true, memoryCreateApproval = false), action))
            assertFalse(memoryToolNeedsApproval(Assistant(memoryModifyApproval = false, memoryCreateApproval = true), action))
        }
    }

    @Test fun `both production assemblers bind all typed callbacks and scoped completion`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        for (path in listOf("tools/ChatToolFactory.kt", "MemoryConsolidationGenerationAgent.kt")) {
            val source = File(root, "app/androidrik/rikkahub/data/ai/$path").readText()
            assertTrue(source.contains("typedOnCreation ="))
            assertTrue(source.contains("typedVersionedOnUpdate ="))
            assertTrue(source.contains("versionedOnComplete ="))
            assertTrue(source.contains("versionedOnDrop ="))
            assertTrue(source.contains("MemoryTaskStatus.DROPPED"))
            assertTrue(source.contains("memoryRepository.setTaskStatusInStore("))
            assertTrue(source.contains("kind = kind, dueAt = dueAt"))
        }
    }
}
