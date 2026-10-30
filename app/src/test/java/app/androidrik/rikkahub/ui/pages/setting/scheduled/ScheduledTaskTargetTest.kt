package app.androidrik.rikkahub.ui.pages.setting.scheduled

import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.db.entity.targetModeOrDefault
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行目标（docs/design/design-scheduled-ai-tasks.md §4.1）的纯逻辑测试：
 * 目标解析的向后兼容（老库只有 EXISTING）+ 会话选择器按助手分组。
 */
class ScheduledTaskTargetTest {

    private fun assistant(name: String) = Assistant(name = name)

    private fun conversation(assistantId: Uuid, title: String) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = emptyList(),
    )

    @Test
    fun `unknown or missing target mode falls back to EXISTING`() {
        assertEquals(TargetMode.EXISTING, targetModeOrDefault(null))
        assertEquals(TargetMode.EXISTING, targetModeOrDefault(""))
        assertEquals(TargetMode.EXISTING, targetModeOrDefault("NOT_A_MODE"))
        // 迁移前的老行：列默认值就是 'EXISTING'
        assertEquals(TargetMode.EXISTING, targetModeOrDefault("EXISTING"))
    }

    @Test
    fun `known target modes round trip through their stored names`() {
        TargetMode.entries.forEach { mode ->
            assertEquals(mode, targetModeOrDefault(mode.name))
        }
        // 持久化兼容护栏：这三个名字已经落到库里（49→50 迁移 + 新建任务），改名即破坏老数据
        assertEquals(
            listOf("EXISTING", "DEDICATED", "NEW_EACH_RUN"),
            TargetMode.entries.map { it.name },
        )
    }

    @Test
    fun `conversations are grouped by assistant in assistant order`() {
        val a = assistant("甲")
        val b = assistant("乙")
        val convA1 = conversation(a.id, "A1")
        val convA2 = conversation(a.id, "A2")
        val convB1 = conversation(b.id, "B1")

        val groups = groupConversationsByAssistant(
            conversations = listOf(convB1, convA1, convA2),
            assistants = listOf(a, b),
        )

        assertEquals(2, groups.size)
        assertEquals(a.id, groups[0].assistant?.id)
        assertEquals(listOf("A1", "A2"), groups[0].conversations.map { it.title })
        assertEquals(b.id, groups[1].assistant?.id)
        assertEquals(listOf("B1"), groups[1].conversations.map { it.title })
    }

    @Test
    fun `conversations of a deleted assistant stay visible in an unknown group`() {
        val a = assistant("甲")
        val orphan = conversation(Uuid.random(), "孤儿会话")

        val groups = groupConversationsByAssistant(
            conversations = listOf(orphan),
            assistants = listOf(a),
        )

        assertEquals(1, groups.size)
        assertEquals(null, groups[0].assistant)
        assertEquals(listOf("孤儿会话"), groups[0].conversations.map { it.title })
    }

    @Test
    fun `empty conversations produce no groups`() {
        assertTrue(groupConversationsByAssistant(emptyList(), listOf(assistant("甲"))).isEmpty())
    }
}
