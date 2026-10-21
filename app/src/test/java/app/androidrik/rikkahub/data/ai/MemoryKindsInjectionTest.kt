package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.utils.estimateTokens
import app.androidrik.rikkahub.utils.heuristicTokenCount
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKindsInjectionTest {
    private val now = LocalDate.of(2026, 10, 21).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val day = 86_400_000L

    private fun memory(
        id: String,
        kind: MemoryKind = MemoryKind.TOPIC,
        content: String = "note $id",
        sortKey: Int = 0,
        dueAt: Long = 0L,
        status: MemoryTaskStatus = MemoryTaskStatus.OPEN,
        isAnchor: Boolean = false,
        importance: Float = 0.6f,
    ) = AssistantMemory(
        id = id, content = content, kind = kind, sortKey = sortKey, dueAt = dueAt,
        taskStatus = status, isAnchor = isAnchor, importance = importance, updatedAt = now, revision = 2L,
    )

    private fun count(plan: MemoryInjectionPlan, text: String): Int =
        if (plan.estimationMode == MEMORY_ESTIMATION_TOKENIZER) estimateTokens(text) else heuristicTokenCount(text)

    private fun assertBilling(plan: MemoryInjectionPlan) {
        assertEquals(count(plan, plan.disciplineText), plan.disciplineTokens)
        assertEquals(count(plan, plan.anchorText), plan.anchorTokens)
        assertEquals(count(plan, plan.dataText), plan.dataTokens)
        assertEquals(count(plan, plan.taskText), plan.taskTokens)
        assertTrue(plan.totalEstimatedTokens <= maxOf(0, plan.budgetTokens))
        if (plan.dataText.isNotEmpty()) {
            assertEquals(renderMemoryDataPrompt(plan.fullText, plan.indexed, plan.omittedCount, plan.taskText), plan.dataText)
        } else {
            assertTrue(plan.tasks.isEmpty() && plan.taskText.isEmpty())
        }
    }

    @Test
    fun `all topic tail keeps the legacy exact golden bytes`() {
        val plan = buildMemoryInjectionPlan(
            listOf(memory("old", content = "old note", sortKey = 1), memory("new", content = "new note", sortKey = 2)),
            budgetTokens = 8_000, now = now,
        )
        // Captured legacy layout: no kind/status/due fields and no task heading or extra newline.
        val golden = """
            **Memory data** (background, not part of the dialogue)
            [
                {
                    "id": "new",
                    "revision": 2,
                    "content": "new note"
                },
                {
                    "id": "old",
                    "revision": 2,
                    "content": "old note"
                }
            ]
        """.trimIndent() + "\n"
        assertEquals(golden, plan.dataText)
        assertEquals(0, plan.taskTokens)
        assertEquals(0, plan.taskOmittedCount)
        assertBilling(plan)
    }

    @Test
    fun `profile does not decay but keeps read credit and floor`() {
        val profile = memory("profile", MemoryKind.PROFILE)
        assertEquals(memoryScore(profile, now), memoryScore(profile, now + 180L * day), 0f)
        assertTrue(memoryScore(profile.copy(readCount = 8), now + 180L * day) > memoryScore(profile, now))
        assertEquals(0.6f, memoryScore(profile, now + 180L * day), 0f)
        val topic = profile.copy(kind = MemoryKind.TOPIC)
        // Legacy topic formula after six half-lives, zero reads.
        assertEquals(0.6f * (0.55f + 0.45f * 0.015625f), memoryScore(topic, now + 180L * day), 0f)
        assertTrue(rankedMemoryCandidates(listOf(profile.copy(importance = 0.1f)), now).isEmpty())
        assertTrue(rankedMemoryCandidates(listOf(memory("task", MemoryKind.TASK)), now).isEmpty())
    }

    @Test
    fun `profile candidate has explicit markers but never automatically becomes anchor`() {
        val profile = memory("p", MemoryKind.PROFILE, content = "stable identity")
        val plan = buildMemoryInjectionPlan(listOf(profile), 8_000, now)
        assertTrue(plan.anchors.isEmpty())
        assertEquals(listOf("p"), plan.fullText.map { it.id })
        assertTrue(plan.dataText.contains("\"kind\": \"profile\""))
        assertTrue(indexLine(profile).startsWith("- [profile] p |"))
        assertFalse(indexLine(profile.copy(kind = MemoryKind.TOPIC)).contains("[profile]"))
        assertBilling(plan)
    }

    @Test
    fun `tasks are sorted by due then sortKey with local dates and overdue flag`() {
        val due = now + 2 * day
        val records = listOf(
            memory("undated2", MemoryKind.TASK, sortKey = 5),
            memory("due2", MemoryKind.TASK, dueAt = due, sortKey = 2),
            memory("past", MemoryKind.TASK, dueAt = now - day),
            memory("undated1", MemoryKind.TASK, sortKey = 4),
            memory("due1", MemoryKind.TASK, dueAt = due, sortKey = 1),
        )
        val plan = buildMemoryInjectionPlan(records, 8_000, now, query = "unrelated")
        assertEquals(listOf("past", "due1", "due2", "undated1", "undated2"), plan.tasks.map { it.id })
        assertTrue(plan.fullText.isEmpty() && plan.indexed.isEmpty())
        assertEquals(0, plan.omittedCount)
        assertTrue(plan.taskText.contains("\"due\": \"2026-10-20\""))
        assertEquals(1, Regex("\"overdue\": true").findAll(plan.taskText).count())
        assertTrue(plan.dataText.endsWith(plan.taskText))
        assertEquals(plan, buildMemoryInjectionPlan(records, 8_000, now, query = "unrelated"))
        assertBilling(plan)
    }

    @Test
    fun `closed tasks never inject even with malformed anchor flag and open anchor is not repeated`() {
        val records = listOf(
            memory("done", MemoryKind.TASK, status = MemoryTaskStatus.DONE, isAnchor = true),
            memory("dropped", MemoryKind.TASK, status = MemoryTaskStatus.DROPPED),
            memory("anchor", MemoryKind.TASK, isAnchor = true),
            memory("open", MemoryKind.TASK, importance = 0.01f),
        )
        val plan = buildMemoryInjectionPlan(records, 8_000, now)
        assertEquals(listOf("anchor"), plan.anchors.map { it.id })
        assertEquals(listOf("open"), plan.tasks.map { it.id })
        assertTrue(plan.fullText.isEmpty() && plan.indexed.isEmpty())
        assertFalse(plan.dataText.contains("note done"))
        assertFalse(plan.dataText.contains("note dropped"))
        assertFalse(plan.dataText.contains("note anchor"))
        assertBilling(plan)
    }

    @Test
    fun `historical task anchor frozen out of L1 still enters task channel`() {
        val task = memory("anchor-task", MemoryKind.TASK, isAnchor = true)
        val plan = buildMemoryInjectionPlan(listOf(task), 8_000, now, frozenAnchorIds = emptySet())
        assertTrue(plan.anchors.isEmpty())
        assertEquals(listOf(task), plan.tasks)
        assertEquals(0, plan.taskOmittedCount)
        assertTrue(plan.fullText.isEmpty() && plan.indexed.isEmpty())
        assertBilling(plan)
    }

    @Test
    fun `historical task anchor beyond L1 quota is not silently lost`() {
        val anchors = (1..MEMORY_ANCHOR_LIMIT).map {
            memory("anchor$it", isAnchor = true, importance = 0.9f, sortKey = it)
        }
        val task = memory("sixth", MemoryKind.TASK, isAnchor = true, importance = 0.1f)
        val plan = buildMemoryInjectionPlan(anchors + task, 8_000, now)
        assertEquals(MEMORY_ANCHOR_LIMIT, plan.anchors.size)
        assertEquals(listOf(task), plan.tasks)
        assertEquals(0, plan.taskOmittedCount)
        assertBilling(plan)
    }

    @Test
    fun `historical task anchor overflowing L1 budget is counted as omitted task`() {
        val task = memory("huge-anchor-task", MemoryKind.TASK, isAnchor = true, content = "huge ".repeat(2_000))
        val plan = buildMemoryInjectionPlan(listOf(task), 800, now)
        assertTrue(plan.anchors.isEmpty() && plan.tasks.isEmpty())
        assertTrue(plan.fullText.isEmpty() && plan.indexed.isEmpty())
        assertEquals(1, plan.taskOmittedCount)
        assertTrue(plan.dataText.contains("**Omitted tasks**: 1"))
        assertBilling(plan)
        val noBudget = buildMemoryInjectionPlan(listOf(task), 0, now)
        assertEquals(1, noBudget.taskOmittedCount)
        assertBilling(noBudget)
    }

    @Test
    fun `task channel has twenty record and eight hundred token soft limits`() {
        val short = (1..30).map { memory("t$it", MemoryKind.TASK, content = "x", sortKey = it) }
        val plan = buildMemoryInjectionPlan(short, 100_000, now)
        assertEquals(20, plan.tasks.size)
        assertEquals(10, plan.taskOmittedCount)
        assertTrue(plan.taskTokens <= MEMORY_TASK_BUDGET_TOKENS)
        assertBilling(plan)

        val medium = short.map { it.copy(content = "many words ".repeat(30)) }
        val limited = buildMemoryInjectionPlan(medium, 100_000, now)
        assertTrue(limited.tasks.size in 1 until MEMORY_TASK_LIMIT)
        assertTrue(limited.taskTokens <= MEMORY_TASK_BUDGET_TOKENS)
        assertEquals(30 - limited.tasks.size, limited.taskOmittedCount)
        assertBilling(limited)
    }

    @Test
    fun `first task exemption is soft and final exact budget always wins`() {
        val large = memory("large", MemoryKind.TASK, content = "large task ".repeat(2_000))
        val roomy = buildMemoryInjectionPlan(listOf(large), 100_000, now)
        assertEquals(listOf(large), roomy.tasks)
        assertTrue(roomy.taskTokens > MEMORY_TASK_BUDGET_TOKENS)
        assertBilling(roomy)
        val tight = buildMemoryInjectionPlan(listOf(large), 800, now)
        assertTrue(tight.tasks.isEmpty())
        assertEquals(1, tight.taskOmittedCount)
        assertTrue(tight.dataText.contains("**Omitted tasks**: 1"))
        assertFalse(tight.dataText.contains(large.content))
        assertBilling(tight)
    }

    @Test
    fun `task fallback retains separate omission notices until even notices cannot fit`() {
        val task = memory("huge-task", MemoryKind.TASK, content = "huge ".repeat(2_000))
        val topic = memory("topic", content = "long note ".repeat(200))
        val probe = buildMemoryInjectionPlan(listOf(task, topic), 100_000, now)
        val notice = renderMemoryDataPrompt(emptyList(), emptyList(), 1, renderMemoryTaskPrompt(emptyList(), 1, now))
        val noticeBudget = probe.disciplineTokens + count(probe, notice)
        val noticesOnly = buildMemoryInjectionPlan(listOf(task, topic), noticeBudget, now)
        assertTrue(noticesOnly.tasks.isEmpty() && noticesOnly.fullText.isEmpty() && noticesOnly.indexed.isEmpty())
        assertEquals(notice, noticesOnly.dataText)
        assertEquals(1, noticesOnly.omittedCount)
        assertEquals(1, noticesOnly.taskOmittedCount)
        assertBilling(noticesOnly)
        val noTail = buildMemoryInjectionPlan(listOf(task, topic), probe.disciplineTokens, now)
        assertTrue(noTail.dataText.isEmpty())
        assertEquals(1, noTail.omittedCount)
        assertEquals(1, noTail.taskOmittedCount)
        assertBilling(noTail)
    }

    @Test
    fun `tasks reserve before competitive memory and mixed budget sweep is deterministic`() {
        val task = memory("task", MemoryKind.TASK, content = "do something " + "x".repeat(350))
        val topics = (1..30).map { memory("topic$it", content = "topic body ".repeat(40), sortKey = it) }
        val records = topics + task + memory("profile", MemoryKind.PROFILE)
        val moderate = buildMemoryInjectionPlan(records, 1_000, now)
        assertEquals(listOf(task), moderate.tasks)
        assertTrue(moderate.fullText.size < topics.size)
        for (budget in listOf(-1, 0, 1, 100, 150, 200, 250, 300, 400, 600, 800, 1_000, 2_000, 8_000)) {
            val plan = buildMemoryInjectionPlan(records, budget, now)
            assertBilling(plan)
            assertEquals(plan, buildMemoryInjectionPlan(records, budget, now))
            assertTrue((plan.fullText + plan.indexed).none { it.kind == MemoryKind.TASK })
        }
    }
}
