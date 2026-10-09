package app.androidrik.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildMemoryTools` 的**接线**测试：验证 `needsApproval` 回调能从 `input` JSON 里正确取出
 * `action` 并交给判定函数。
 *
 * 审批**判定规则本身**（create 默认放行 / 可选拦截、edit-delete 默认拦截、未知 action 兜底）
 * 见 [MemoryToolApprovalTest]，那里测的是生产函数 [memoryToolNeedsApproval]——
 * 本类只负责"JSON → action → 判定"这条链路，不再复刻判定逻辑。
 */
class MemoryToolsTest {

    private val json = Json

    /** 记录回调实际收到的 action，用于验证提取路径。 */
    private val seenActions = mutableListOf<String>()

    private fun memoryTool(
        modifyApproval: Boolean = true,
        createApproval: Boolean = false,
    ) = buildMemoryTools(
        json = json,
        onCreation = { content, importance -> AssistantMemory("00000001", content, importance ?: 0.6f) },
        onUpdate = { id, content, importance ->
            AssistantMemory(id, content, importance ?: 0.6f)
        },
        onDelete = { },
        needsApproval = { action ->
            seenActions += action
            memoryToolNeedsApproval(
                Assistant(
                    memoryModifyApproval = modifyApproval,
                    memoryCreateApproval = createApproval,
                ),
                action,
            )
        },
    ).first { it.name == "memory_tool" }

    private fun input(action: String) = buildJsonObject { put("action", action) }

    @Test
    fun `action is extracted from input json`() {
        val tool = memoryTool()
        tool.needsApproval(input("edit"))
        tool.needsApproval(input("delete"))
        tool.needsApproval(input("create"))
        assertEquals(listOf("edit", "delete", "create"), seenActions)
    }

    @Test
    fun `missing action yields empty string not a crash`() {
        val tool = memoryTool()
        // 缺 action 不应抛（回调收到空串，由判定函数按兜底处理）
        tool.needsApproval(buildJsonObject { })
        assertEquals(listOf(""), seenActions)
    }

    @Test
    fun `default config gates edit and delete but allows create`() {
        val tool = memoryTool()
        assertTrue(tool.needsApproval(input("edit")))
        assertTrue(tool.needsApproval(input("delete")))
        assertFalse(tool.needsApproval(input("create")))
    }

    @Test
    fun `create is gated end-to-end when create approval enabled`() {
        val tool = memoryTool(createApproval = true)
        assertTrue(tool.needsApproval(input("create")))
    }

    @Test
    fun `tool name and description integrity`() {
        val tool = memoryTool()
        assertEquals("memory_tool", tool.name)
        assertTrue(tool.description.isNotBlank())
    }

    @Test
    fun `description and schema both expose importance with tiered guidance`() {
        val tool = memoryTool()
        // 描述里给出分档锚点——模型不填/乱填会让打分校准退化成噪声，这是唯一能约束它的地方
        assertTrue(tool.description.contains("importance"))
        assertTrue(tool.description.contains("0.9"))
        assertTrue(tool.description.contains("0.6-0.8"))
        // schema 里也要有该参数，否则模型看不到
        val schema = tool.parameters().toString()
        assertTrue(schema.contains("importance"))
    }

    // ---- importance 的提取与传递（执行链路） ----

    private val created = mutableListOf<Pair<String, Float?>>()
    private val updated = mutableListOf<Triple<String, String, Float?>>()

    private fun executingTool() = buildMemoryTools(
        json = json,
        onCreation = { content, importance ->
            created += content to importance
            AssistantMemory("00000001", content, importance ?: 0.6f)
        },
        onUpdate = { id, content, importance ->
            updated += Triple(id, content, importance)
            AssistantMemory(id, content, importance ?: 0.6f)
        },
        onDelete = { },
    ).first { it.name == "memory_tool" }

    @Test
    fun `importance is passed through on create`() = runBlocking {
        executingTool().execute(
            buildJsonObject {
                put("action", "create")
                put("content", "likes tea")
                put("importance", 0.9)
            }
        )
        assertEquals(listOf("likes tea" to 0.9f), created)
    }

    @Test
    fun `absent importance is passed as null so repository applies its default`() = runBlocking {
        executingTool().execute(
            buildJsonObject {
                put("action", "create")
                put("content", "likes tea")
            }
        )
        // null（而非 0f）——由仓库决定缺省值，工具层不替它做主
        assertEquals(listOf("likes tea" to null), created)
    }

    @Test
    fun `importance is passed through on edit`() = runBlocking {
        executingTool().execute(
            buildJsonObject {
                put("action", "edit")
                put("id", "a3f1c09e")
                put("content", "prefers coffee")
                put("importance", 0.4)
            }
        )
        assertEquals(listOf(Triple("a3f1c09e", "prefers coffee", 0.4f)), updated)
    }

    // ---- 代码级兜底拦截（V1）：命中敏感标识时不得落库 ----

    @Test
    fun `create with a bank card number is refused and never reaches the repository`() = runBlocking {
        val result = executingTool().execute(
            buildJsonObject {
                put("action", "create")
                put("content", "银行卡号 4111 1111 1111 1111")
                put("importance", 0.9)
            }
        )
        assertEquals("仓库不得收到被拦的写入", emptyList<Pair<String, Float?>>(), created)
        val text = result.single().let { (it as UIMessagePart.Text).text }
        assertTrue("回执要说明没存", text.contains("\"stored\":false"))
        assertTrue("回执要给出类别", text.contains("a bank card number"))
        assertTrue("回执要明说别重试", text.contains("Do not retry"))
        assertFalse("回执不得回显命中的原文", text.contains("4111"))
    }

    @Test
    fun `edit with a national id number is refused and never reaches the repository`() = runBlocking {
        val result = executingTool().execute(
            buildJsonObject {
                put("action", "edit")
                put("id", "a3f1c09e")
                put("content", "身份证 11010519491231002X")
            }
        )
        assertEquals("仓库不得收到被拦的修改", emptyList<Triple<String, String, Float?>>(), updated)
        val text = result.single().let { (it as UIMessagePart.Text).text }
        assertTrue(text.contains("an ID number"))
        assertFalse(text.contains("110105"))
    }

    @Test
    fun `ordinary content still goes through`() = runBlocking {
        executingTool().execute(
            buildJsonObject {
                put("action", "create")
                put("content", "用户偏好简洁回复")
            }
        )
        assertEquals(listOf("用户偏好简洁回复" to null), created)
    }

    @Test
    fun `delete is not affected by the guard`() = runBlocking {
        // delete 没有内容可泄漏，不该因为守卫而改变行为
        val deleted = mutableListOf<String>()
        val tool = buildMemoryTools(
            json = json,
            onCreation = { content, importance -> AssistantMemory("00000001", content, importance ?: 0.6f) },
            onUpdate = { id, content, importance -> AssistantMemory(id, content, importance ?: 0.6f) },
            onDelete = { deleted += it },
        ).first { it.name == "memory_tool" }
        tool.execute(buildJsonObject {
            put("action", "delete")
            put("id", "7bd20f45")
        })
        assertEquals(listOf("7bd20f45"), deleted)
    }
}
