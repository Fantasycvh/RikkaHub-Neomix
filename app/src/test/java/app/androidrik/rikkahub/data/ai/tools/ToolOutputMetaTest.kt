package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 工具输出**完整性声明**（`_meta`）的契约测试。
 *
 * 三条不能退让的性质：
 * 1. 截断时结构完整（`truncated` / `omitted.kind` / `omitted.count` / `hint`）；
 * 2. **不截断时不写 `_meta`**——缺席就是「完整」，完整路径不许多花一个字节；
 * 3. `count == 0` 视同没丢东西 ⇒ 同样不写（见 [ToolOutputMeta.payload] 的取舍说明）。
 *
 * 端到端那两条（`read_conversation` / `memory_tool.search`）走的是**生产函数**，
 * 不是复刻判定逻辑：它们证明接线没漏，纯助手单测证明不了这一点。
 */
class ToolOutputMetaTest {

    private fun metaOf(payload: String): JsonObject? =
        Json.parseToJsonElement(payload).jsonObject[ToolOutputMeta.KEY]?.jsonObject

    private fun conversation(vararg texts: String): Conversation {
        val messages = texts.map { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(it))) }
        return Conversation.ofId(id = Uuid.random()).updateCurrentMessages(messages)
    }

    // ---- 助手本身 ----

    @Test
    fun `truncated declaration carries the full structure`() {
        val meta = ToolOutputMeta.payload(OmittedKind.CHARS, 12_345, "Call read_conversation with a smaller context.")
            ?: error("count>0 必须产出声明")

        assertEquals(true, meta.getValue("truncated").jsonPrimitive.content.toBoolean())
        val omitted = meta.getValue("omitted").jsonObject
        assertEquals("chars", omitted.getValue("kind").jsonPrimitive.content)
        assertEquals(12_345, omitted.getValue("count").jsonPrimitive.int)
        assertTrue(meta.getValue("hint").jsonPrimitive.content.contains("read_conversation"))
    }

    @Test
    fun `items kind is spelled exactly as the contract says`() {
        val meta = ToolOutputMeta.payload(OmittedKind.ITEMS, 2, "Call memory_tool with action:\"read\".")
            ?: error("count>0 必须产出声明")
        assertEquals("items", meta.getValue("omitted").jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun `zero count declares nothing because nothing was lost`() {
        // 取舍：`_meta` 缺席 == 输出完整是唯一判据。写一个 count:0 的条目等于自相矛盾
        // （声称被截断，却一个字节都没丢），所以 0 走「不写」。
        assertNull(ToolOutputMeta.payload(OmittedKind.CHARS, 0, "Call read_conversation again."))
        assertNull(ToolOutputMeta.payload(OmittedKind.CHARS, -1, "Call read_conversation again."))

        val built = buildJsonObject {
            put("ok", true)
            markTruncated(OmittedKind.CHARS, 0, "Call read_conversation again.")
        }
        assertFalse("count==0 不得写出 _meta", built.containsKey(ToolOutputMeta.KEY))
    }

    @Test
    fun `complete output carries no _meta key at all`() {
        // 端到端：短会话读出来是完整的，那就一个字节都不许多
        val payload = buildConversationReadPayload(
            conversation = conversation("m0", "m1"),
            anchorMessageId = null,
            context = 2,
        )
        assertNull("完整结果不得有 _meta", metaOf(payload))
        assertFalse(payload.contains("_meta"))
    }

    // ---- read_conversation（字符） ----

    @Test
    fun `read_conversation declares the chars it dropped`() {
        val long = "x".repeat(20_000)
        val payload = buildConversationReadPayload(
            conversation = conversation(long, long, long),
            anchorMessageId = null,
            context = 5,
        )
        val meta = metaOf(payload) ?: error("截断时必须写 _meta")
        val omitted = meta.getValue("omitted").jsonObject

        assertEquals(true, meta.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertEquals("chars", omitted.getValue("kind").jsonPrimitive.content)
        assertTrue("被削掉的字符数必须为正", omitted.getValue("count").jsonPrimitive.int > 0)
        // 精确值：三条各 20_000 字，单条上限 8_000 ⇒ 前两条各留 8_000、各丢 12_000；
        // 总量上限 24_000 ⇒ 第三条整条没写、丢 20_000。合计 44_000。
        // 这个**精确**值同时钉住「同一条不许既算半截又算整条」的二重计数（那样会是 56_000）。
        assertEquals("被丢掉的字符数", 44_000, omitted.getValue("count").jsonPrimitive.int)
        val hint = meta.getValue("hint").jsonPrimitive.content
        assertTrue("hint 要指向真实工具 conversation_search", hint.contains("conversation_search"))
        assertTrue("hint 要指向真实工具 read_conversation", hint.contains("read_conversation"))
        // 散文仍在（纯加法，既有 UI/测试不受影响）
        assertTrue(Json.parseToJsonElement(payload).jsonObject.containsKey("note"))
    }

    @Test
    fun `a single over-long message alone is enough to declare`() {
        val payload = buildConversationReadPayload(
            conversation = conversation("y".repeat(9_000)),
            anchorMessageId = null,
            context = 1,
        )
        val meta = metaOf(payload) ?: error("单条超限也必须声明")
        assertEquals("chars", meta.getValue("omitted").jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue(meta.getValue("omitted").jsonObject.getValue("count").jsonPrimitive.int >= 1_000)
    }

    // ---- memory_tool.search（条目） ----

    private fun memorySearch(records: List<AssistantMemory>): String = runBlocking {
        buildMemoryTools(
            json = Json,
            onCreation = { content, importance, description ->
                AssistantMemory("00000001", content, importance ?: 0.6f, description.orEmpty())
            },
            onUpdate = { id, content, importance, description ->
                AssistantMemory(id, content, importance ?: 0.6f, description.orEmpty())
            },
            onDelete = { },
            onSearch = { _, _ -> records },
        ).first { it.name == "memory_tool" }.execute(
            buildJsonObject {
                put("action", "search")
                put("query", "TikZ")
            },
        ).single().let { (it as UIMessagePart.Text).text }
    }

    @Test
    fun `memory search declares the items it could not return`() {
        val records = (1..3).map { index ->
            AssistantMemory("0000000$index", "z".repeat(3_000), 0.5f, "topic $index")
        }
        val text = memorySearch(records)
        val meta = metaOf(text) ?: error("按预算砍掉记录时必须写 _meta")

        val omitted = meta.getValue("omitted").jsonObject
        assertEquals("items", omitted.getValue("kind").jsonPrimitive.content)
        val returned = Json.parseToJsonElement(text).jsonObject.getValue("returned").jsonPrimitive.int
        assertEquals("被略过的条数 == 命中 - 返回", 3 - returned, omitted.getValue("count").jsonPrimitive.int)
        val hint = meta.getValue("hint").jsonPrimitive.content
        assertTrue("hint 要指向真实工具 memory_tool", hint.contains("memory_tool"))
        assertTrue("hint 要指明 read 这个 action", hint.contains("read"))
        assertTrue(Json.parseToJsonElement(text).jsonObject.containsKey("omitted"))
    }

    @Test
    fun `memory search that fits entirely carries no _meta`() {
        val text = memorySearch(listOf(AssistantMemory("00000001", "short", 0.5f, "topic")))
        assertNull("没超预算就不得有 _meta", metaOf(text))
    }
}
