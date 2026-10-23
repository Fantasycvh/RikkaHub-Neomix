package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetAssemblyTest {
    private val settings = Settings.dummy().let {
        it.copy(displaySetting = it.displaySetting.copy(enableLatexRendering = false))
    }
    private val model = Model(modelId = "budget-test", displayName = "Budget test")
    private val assistant = Assistant(enableMemory = true, systemPrompt = "persona")
    private val memories = listOf(
        AssistantMemory("anchor", "anchor fact", importance = 0.9f, isAnchor = true, sortKey = 1),
        AssistantMemory("note", "ordinary note", importance = 0.8f, sortKey = 2),
    )
    private val messages = listOf(UIMessage.user("ordinary question"))

    private fun assemble(budget: Int, disabled: Boolean = false, enabled: Boolean = true) =
        buildInternalMessagesWithPlan(
            assistant = assistant.copy(enableMemory = enabled),
            settings = settings.copy(memoryInjectBudgetTokens = budget),
            model = model,
            messages = messages,
            memories = memories,
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
            memoryDisabled = disabled,
            frozenAnchorIds = setOf("anchor"),
        )

    @Test
    fun `tiny budgets add no memory blocks or synthetic tail`() {
        listOf(-1, 0, 1).forEach { budget ->
            val result = assemble(budget)
            assertEquals(0, result.memoryPlan.totalEstimatedTokens)
            assertEquals(memories.size, result.memoryPlan.omittedCount)
            assertEquals(messages, result.messages.filter { it.role == MessageRole.USER })
            assertEquals(messages.single(), result.messages.last())
            assertFalse(result.messages.first().parts.toString().contains("anchor fact"))
        }
    }

    @Test
    fun `system projection and tail use the exact same plan strings`() {
        val result = assemble(8_000)
        val plan = result.memoryPlan
        val blocks = buildSystemBlocks(
            assistant, settings, model, messages, memories, emptyList(), null,
            frozenAnchorIds = setOf("anchor"), memoryPlan = plan,
        ).filter { it.source == SystemBlockSource.MEMORY || it.source == SystemBlockSource.ANCHOR }
        assertEquals(listOf(plan.anchorText, plan.disciplineText), blocks.map { it.content })
        assertEquals(plan.anchorText + plan.disciplineText, joinSystemBlocks(blocks))
        val tail = result.messages.last()
        assertTrue(tail.isSynthetic)
        assertEquals(MessageRole.USER, tail.role)
        assertEquals(plan.dataText, tail.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertFalse(plan.dataText.contains("anchor fact"))
        assertTrue(plan.totalEstimatedTokens <= 8_000)
    }

    @Test
    fun `disabled memory does not leak in either component`() {
        listOf(assemble(8_000, disabled = true), assemble(8_000, enabled = false)).forEach {
            assertEquals(0, it.memoryPlan.totalEstimatedTokens)
            assertTrue(it.memoryPlan.disciplineText.isEmpty())
            assertTrue(it.memoryPlan.anchorText.isEmpty())
            assertTrue(it.memoryPlan.dataText.isEmpty())
            assertEquals(messages, it.messages.filter { message -> message.role == MessageRole.USER })
        }
    }

    @Test
    fun `budget selection never changes the persisted frozen candidate set`() {
        val candidates = anchorIdsOf(memories)
        assertEquals(listOf("anchor"), candidates)
        assertTrue(assemble(1).memoryPlan.anchors.isEmpty())
        assertEquals(candidates, anchorIdsOf(memories))
        assertEquals(0, memories.sumOf { it.readCount + it.chatReadCount + it.consolidationReadCount })
    }

    @Test
    fun `preview keeps cold plan counts when tokenizer warms before projection`() {
        val registry = app.androidrik.common.tokenizer.TokenizerRegistry
        registry.release()
        try {
            val plan = assemble(8_000).memoryPlan
            assertEquals(MEMORY_ESTIMATION_HEURISTIC, plan.estimationMode)
            assertTrue("Bundled tokenizer must load", registry.installFromClasspath())
            val blocks = listOf(
                SystemBlock(SystemBlockSource.ANCHOR, content = plan.anchorText),
                SystemBlock(SystemBlockSource.MEMORY, content = plan.disciplineText),
            )
            val segments = systemBlocksToSegments(
                blocks, PromptInspectionLabels("system", "checkpoint", "injection", "history"),
                "system", memoryPlan = plan,
            )
            assertEquals(listOf(plan.anchorTokens, plan.disciplineTokens), segments.map { it.tokens })
        } finally {
            registry.release()
        }
    }

    @Test
    fun `warmed real tokenizer also respects every tested hard cap`() {
        val registry = app.androidrik.common.tokenizer.TokenizerRegistry
        registry.release()
        try {
            assertTrue("Bundled tokenizer must load", registry.installFromClasspath())
            val records = memories + AssistantMemory(
                "escaped-\"-" + "x".repeat(100), "中文\n\"quoted\" ".repeat(300), importance = 0.9f,
            )
            listOf(-1, 0, 1, 250, 500, 1_000, 8_000).forEach { budget ->
                val plan = buildMemoryInjectionPlan(records, budget, now = 100)
                assertEquals(MEMORY_ESTIMATION_TOKENIZER, plan.estimationMode)
                assertEquals(registry.count(plan.disciplineText), plan.disciplineTokens)
                assertEquals(registry.count(plan.anchorText), plan.anchorTokens)
                assertEquals(registry.count(plan.dataText), plan.dataTokens)
                assertTrue(plan.totalEstimatedTokens <= maxOf(0, budget))
                assertEquals(records.size - plan.anchors.size - plan.fullText.size - plan.indexed.size,
                    plan.omittedCount)
            }
        } finally {
            registry.release()
        }
    }

    @Test
    fun `supplied plan is not recomputed from projection query or budget`() {
        val plan = assemble(8_000).memoryPlan
        val blocks = buildSystemBlocks(
            assistant, settings.copy(memoryInjectBudgetTokens = 0), model,
            listOf(UIMessage.user("different query")), emptyList(), emptyList(), null,
            memoryPlan = plan,
        )
        assertEquals(plan.anchorText, blocks.single { it.source == SystemBlockSource.ANCHOR }.content)
        assertEquals(plan.disciplineText, blocks.single { it.source == SystemBlockSource.MEMORY }.content)
    }
}
