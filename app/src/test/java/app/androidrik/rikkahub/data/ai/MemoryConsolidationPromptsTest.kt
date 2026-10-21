package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.utils.JsonInstantPretty
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 做梦输入侧（正文切分 + 校准表）的纯函数单测。
 *
 * 这里钉的是**成本与正确性的边界**：装不下就不许推进水位（否则那些轮次被永久跳过）、
 * 单轮超预算也不许让某个会话永远轮不到、没有正文的轮次也要跨过去（否则它每 2 分钟被扫一次）。
 */
class MemoryConsolidationPromptsTest {

    private val zone = TimeZone.currentSystemDefault()

    private fun stamp(hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.parse("2026-01-01T${"%02d".format(hour)}:${"%02d".format(minute)}")

    private fun millis(hour: Int, minute: Int = 0): Long =
        stamp(hour, minute).toInstant(zone).toEpochMilliseconds()

    /** ASCII 文本：`estimateTokens` 对非 CJK 是 4 字符/token ⇒ 长度可控，测试才确定 */
    private fun text(chars: Int): String = "x".repeat(chars)

    private fun conversation(title: String, vararg turns: Pair<Int, String>): Conversation = Conversation(
        assistantId = Uuid.random(),
        title = title,
        messageNodes = turns.map { (hour, body) ->
            MessageNode(
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(body)),
                        createdAt = stamp(hour),
                    )
                )
            )
        },
    )

    @Test
    fun `transcript keeps only turns after the baseline`() {
        val conversation = conversation("家", 1 to text(40), 5 to text(40), 9 to text(40))
        val transcript = buildConsolidationTranscript(
            conversations = listOf(conversation),
            // 基线落在第 5 小时之后：水位语义是「严格晚于」，等于基线的轮次已经算整理过
            baselines = mapOf(conversation.id to millis(5, 30)),
            budgetTokens = 10_000,
        )

        assertTrue("基线之前的轮次不该出现", !transcript.text.contains("2026-01-01 01:00"))
        assertTrue("等于基线的轮次也算整理过", !transcript.text.contains("2026-01-01 05:00"))
        assertTrue(transcript.text.contains("2026-01-01 09:00"))
        assertTrue("会话标题要带上（模型据此判断这是哪段对话）", transcript.text.contains("--- conversation: 家 ---"))
        assertEquals(
            "水位推进到最后一轮的时间",
            listOf(ConsumedTurns(conversation.id, millis(9))),
            transcript.consumed,
        )
    }

    @Test
    fun `a conversation that does not fit the budget stays pending`() {
        val first = conversation("甲", 1 to text(200))
        val second = conversation("乙", 2 to text(200))
        val transcript = buildConsolidationTranscript(
            conversations = listOf(first, second),
            baselines = mapOf(first.id to 0L, second.id to 0L),
            // 约 50 token：只够第一条（200 字符 ≈ 50 token）
            budgetTokens = 60,
        )

        assertTrue(transcript.text.contains("--- conversation: 甲 ---"))
        assertTrue("装不下的会话整段不出现", !transcript.text.contains("--- conversation: 乙 ---"))
        assertEquals("只有真正带进去的会话才允许推进水位", listOf(first.id), transcript.consumed.map { it.conversationId })
        assertEquals("剩余轮数要能看出来（诊断用）", 1, transcript.remainingTurns)
    }

    @Test
    fun `a single oversized turn is still included`() {
        val conversation = conversation("巨型", 1 to text(4_000))
        val transcript = buildConsolidationTranscript(
            conversations = listOf(conversation),
            baselines = mapOf(conversation.id to 0L),
            budgetTokens = 10,
        )

        assertTrue("单轮超预算也要带上，否则这个会话永远轮不到", transcript.text.contains("xxx"))
        assertEquals(listOf(conversation.id), transcript.consumed.map { it.conversationId })
    }

    @Test
    fun `once the budget is exhausted later conversations are only counted`() {
        val first = conversation("甲", 1 to text(200), 2 to text(200))
        val second = conversation("乙", 3 to text(200), 4 to text(200), 5 to text(200))
        val transcript = buildConsolidationTranscript(
            conversations = listOf(first, second),
            baselines = mapOf(first.id to 0L, second.id to 0L),
            budgetTokens = 60,
        )

        assertTrue("甲的后半段没带进去", !transcript.text.contains("2026-01-01 02:00"))
        assertEquals("丙未被尝试", listOf(first.id), transcript.consumed.map { it.conversationId })
        assertEquals("甲剩 1 轮 + 乙整段 3 轮", 4, transcript.remainingTurns)
    }

    @Test
    fun `a turn without representable text advances the watermark without entering the transcript`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            title = "只有思考",
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Reasoning("thinking hard")),
                            createdAt = stamp(3),
                        )
                    )
                )
            ),
        )
        val transcript = buildConsolidationTranscript(
            conversations = listOf(conversation),
            baselines = mapOf(conversation.id to 0L),
            budgetTokens = 10_000,
        )

        assertEquals("正文应为空", "", transcript.text)
        assertEquals(
            "水位仍要跨过去：否则它永远算「待整理」，每 2 分钟被扫一次却什么也做不了",
            listOf(ConsumedTurns(conversation.id, millis(3))),
            transcript.consumed,
        )
    }

    @Test
    fun `attachments become placeholders and tool parts are dropped`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            title = "附件",
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text("看这个"),
                                UIMessagePart.Image(url = "file://a.png"),
                                UIMessagePart.Document(url = "file://b.pdf", fileName = "报.pdf"),
                            ),
                            createdAt = stamp(4),
                        )
                    )
                )
            ),
        )
        val transcript = buildConsolidationTranscript(
            conversations = listOf(conversation),
            baselines = mapOf(conversation.id to 0L),
            budgetTokens = 10_000,
        )

        assertTrue(transcript.text.contains("看这个"))
        assertTrue(transcript.text.contains("[image]"))
        assertTrue(transcript.text.contains("[file: 报.pdf]"))
        assertTrue("正文里不该出现图片/文档的真实 URL（没用还贵）", !transcript.text.contains("file://"))
    }

    @Test
    fun `library snapshot shows kinds and task status due without non task metadata`() {
        val records = listOf(
            AssistantMemory("profile", "stable fact", kind = MemoryKind.PROFILE),
            AssistantMemory("topic", "project fact"),
            AssistantMemory("task", "commitment", kind = MemoryKind.TASK, taskStatus = MemoryTaskStatus.DROPPED,
                dueAt = millis(0), revision = 7L),
            AssistantMemory("undated", "another", kind = MemoryKind.TASK),
        )
        val page = pageOfLibrary(records, 0L, 100_000)
        val prompt = buildLibraryPagePrompt(page)
        val rows = JsonInstantPretty.parseToJsonElement(prompt.substring(prompt.indexOf('['))).jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }
        assertEquals("profile", rows.getValue("profile")["kind"]!!.jsonPrimitive.content)
        assertEquals("topic", rows.getValue("topic")["kind"]!!.jsonPrimitive.content)
        for (id in listOf("profile", "topic")) {
            assertTrue(rows.getValue(id)["task_status"] == null)
            assertTrue(rows.getValue(id)["due"] == null)
        }
        assertEquals("dropped", rows.getValue("task")["task_status"]!!.jsonPrimitive.content)
        assertEquals("2026-01-01", rows.getValue("task")["due"]!!.jsonPrimitive.content)
        assertEquals("7", rows.getValue("task")["revision"]!!.jsonPrimitive.content)
        assertEquals("open", rows.getValue("undated")["task_status"]!!.jsonPrimitive.content)
        assertTrue(rows.getValue("undated")["due"] == null)
    }

    @Test
    fun `calibration gives every kind and all task statuses including undated tasks`() {
        val records = listOf(
            AssistantMemory("p", "identity", kind = MemoryKind.PROFILE, sortKey = 1),
            AssistantMemory("t", "knowledge", sortKey = 2),
        ) + MemoryTaskStatus.entries.mapIndexed { index, status ->
            AssistantMemory("task${status.storage}", "commitment", kind = MemoryKind.TASK,
                taskStatus = status, dueAt = if (index == 0) 0 else millis(0), sortKey = index + 3)
        }
        val table = buildMemoryCalibrationTable(records.reversed())
        assertTrue(table.lines().first().startsWith("- p | kind profile |"))
        assertTrue(table.contains("- t | kind topic |"))
        assertTrue(table.contains("kind task; status open; due none"))
        assertTrue(table.contains("kind task; status done; due 2026-01-01"))
        assertTrue(table.contains("kind task; status dropped; due 2026-01-01"))
        assertEquals(5, table.lines().size)
    }

    @Test
    fun `librarian rules require fresh user evidence and forbid cross kind merging`() {
        val prompt = MEMORY_CONSOLIDATION_SYSTEM_PROMPT
        assertTrue(prompt.contains("only within the same kind"))
        assertTrue(prompt.contains("Never merge across kinds or reclassify records merely to bypass"))
        assertTrue(prompt.contains("user explicitly says in NEW TURNS"))
        assertTrue(prompt.contains("action=\"complete\""))
        assertTrue(prompt.contains("action=\"drop\""))
        assertTrue(prompt.contains("assistant's claim is never evidence"))
        assertTrue(prompt.contains("A past due date, age, silence"))
        assertTrue(prompt.contains("never retire because of age"))
        assertTrue(prompt.contains("Never auto-complete, drop or retire"))
        assertTrue(prompt.contains("create a separate"))
        assertTrue(prompt.contains("candidate in tail context, not automatically a system anchor"))
    }

    @Test
    fun `proposal note includes task status actions but only same kind replacements authorize delete`() {
        val note = MEMORY_CONSOLIDATION_PROPOSAL_NOTE
        assertTrue(note.contains("`create` / `edit` / `delete` / `complete` / `drop`"))
        assertTrue(note.contains("edit/delete/complete/drop"))
        assertTrue(note.contains("expected_revision"))
        assertTrue(note.contains("applied:false"))
        assertTrue(note.contains("same-kind replacement"))
        assertTrue(note.contains("delete's non-empty `depends_on`"))
        assertTrue(note.contains("Only create/edit replacement operation_ids"))
        assertTrue(note.contains("never replacement dependencies"))
    }

    @Test
    fun `calibration descriptions and content fallback cannot introduce extra rows or columns`() {
        val described = AssistantMemory("described", "original content\n| unchanged", description = "中文|主题\n次行\r末行")
        val fallback = AssistantMemory("fallback", "正文|主题\n另行")
        val table = buildMemoryCalibrationTable(listOf(described, fallback))
        assertEquals(2, table.lines().size)
        assertTrue(table.lines()[0].endsWith("中文\\|主题 次行 末行"))
        assertTrue(table.lines()[1].endsWith("正文\\|主题 另行"))
        assertEquals("中文|主题\n次行\r末行", described.description)
        assertEquals("original content\n| unchanged", described.content)
        assertEquals("正文|主题\n另行", fallback.content)
    }

    @Test
    fun `calibration table shows declared importance against actual reads`() {
        val table = buildMemoryCalibrationTable(
            listOf(
                AssistantMemory(id = "aaa", content = "常读的", importance = 0.4f, readCount = 7, sortKey = 1,
                    chatReadCount = 2, consolidationReadCount = 13),
                AssistantMemory(id = "bbb", content = "没读过的", importance = 0.9f, sortKey = 2),
                AssistantMemory(id = "ccc", content = "有主题词的", importance = 0.6f, description = "主题", sortKey = 3),
            )
        )

        assertTrue(table.contains("- aaa | kind topic | importance 0.40 | legacy+chat read 7x; chat 2x; unknown 5x; consolidation 13x | 常读的"))
        assertTrue(table.contains("- bbb | kind topic | importance 0.90 | legacy+chat never read; chat 0x; unknown 0x; consolidation 0x | 没读过的"))
        assertTrue(table.contains("- ccc | kind topic | importance 0.60 | legacy+chat never read; chat 0x; unknown 0x; consolidation 0x | 主题"))
        assertEquals("空库不发校准表", "", buildMemoryCalibrationTable(emptyList()))
    }
}
