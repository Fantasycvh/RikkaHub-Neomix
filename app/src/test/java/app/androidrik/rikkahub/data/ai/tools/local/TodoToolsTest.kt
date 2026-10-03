package app.androidrik.rikkahub.data.ai.tools.local

import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation

class TodoToolsTest {

    @Test
    fun `execute normalizes todos and returns payload`() {
        val tool = buildTodoWriteTool()
        val args = buildJsonObject {
            put("todos", buildJsonArray {
                add(buildJsonObject {
                    put("content", "  Write test  ")
                    put("status", "in_progress")
                })
                add(buildJsonObject {
                    put("content", "Ship it")
                    put("status", "completed")
                })
            })
        }
        val output = runBlocking { tool.execute(args) }.single() as UIMessagePart.Text
        val parsed = Json.parseToJsonElement(output.text).jsonObject
        val todos = parsed.getValue("todos").jsonArray
        assertEquals(2, todos.size)
        assertEquals("Write test", todos[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("in_progress", todos[0].jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(!parsed.containsKey("warning"))
    }

    @Test
    fun `execute warns when more than one in_progress`() {
        val tool = buildTodoWriteTool()
        val args = buildJsonObject {
            put("todos", buildJsonArray {
                add(buildJsonObject { put("content", "A"); put("status", "in_progress") })
                add(buildJsonObject { put("content", "B"); put("status", "in_progress") })
            })
        }
        val output = runBlocking { tool.execute(args) }.single() as UIMessagePart.Text
        assertTrue(Json.parseToJsonElement(output.text).jsonObject.containsKey("warning"))
    }

    @Test
    fun `execute rejects blank content and bad status`() {
        val tool = buildTodoWriteTool()
        val blank = buildJsonObject {
            put("todos", buildJsonArray {
                add(buildJsonObject { put("content", "  "); put("status", "pending") })
            })
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { tool.execute(blank) } }

        val bad = buildJsonObject {
            put("todos", buildJsonArray {
                add(buildJsonObject { put("content", "A"); put("status", "doing") })
            })
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { tool.execute(bad) } }
    }

    @Test
    fun `parseTodoPlanArgs tolerates malformed input`() {
        assertNull(parseTodoPlanArgs(""))
        assertNull(parseTodoPlanArgs("not json"))
        assertNull(parseTodoPlanArgs("""{"todos":[]}"""))
        // 非法条目被剔除，全部非法时返回 null
        assertNull(parseTodoPlanArgs("""{"todos":[{"content":"","status":"pending"}]}"""))
        // 合法（混入的垃圾条目被跳过）
        val ok = parseTodoPlanArgs("""{"todos":[{"content":"A","status":"completed"},{"junk":true}]}""")
        assertEquals(listOf(TodoEntry("A", "completed")), ok)
    }

    @Test
    fun `latestTodoPlan takes the last todo_write call in the branch`() {
        fun toolMsg(input: String): UIMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = Uuid.random().toString(), toolName = "todo_write", input = input)),
        )

        val conversation = Conversation.ofId(
            id = Uuid.random(),
        ).updateCurrentMessages(listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
            toolMsg("""{"todos":[{"content":"Old","status":"completed"}]}"""),
            toolMsg("""{"todos":[{"content":"New A","status":"in_progress"},{"content":"New B","status":"pending"}]}"""),
        ))

        val plan = latestTodoPlan(conversation)
        assertEquals(listOf(TodoEntry("New A", "in_progress"), TodoEntry("New B", "pending")), plan)
    }

    @Test
    fun `latestTodoPlan ignores non todo tools and returns empty without calls`() {
        val conversation = Conversation.ofId(id = Uuid.random()).updateCurrentMessages(listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(toolCallId = Uuid.random().toString(), toolName = "search_web", input = "{}")
            )),
        ))
        assertTrue(latestTodoPlan(conversation).isEmpty())
    }
}
