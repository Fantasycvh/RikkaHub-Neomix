package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.ai.tools.buildMemoryTools
import app.androidrik.rikkahub.data.model.AssistantMemory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryInjectionFloorTest {

    private fun memory(id: String, importance: Float, ageDays: Float, now: Long) = AssistantMemory(
        id = id,
        content = "memory $id",
        importance = importance,
        // updatedAt 显式设为 now - ageDays（0 会被视为「不衰减」，见 memoryScore 注释）
        updatedAt = now - (ageDays * 24 * 60 * 60 * 1000).toLong(),
        sortKey = 0,
    )

    @Test
    fun `default importance memories never fall below the floor`() {
        // importance 0.6 全老化也有 0.33 > 0.2 下限 ⇒ 永不沉底
        val now = 730L * 24 * 60 * 60 * 1000
        val memories = listOf(memory("a", 0.6f, ageDays = 365f, now = now))
        val selected = selectMemoriesForPrompt(memories, budgetTokens = 10_000, now = now)
        assertEquals(listOf("a"), selected.map { it.id })
    }

    @Test
    fun `low importance aged memories are filtered by the floor`() {
        // importance 0.15 老化一年：0.15 × 0.55 ≈ 0.083 < 0.2 ⇒ 不注入（即使预算充裕）
        val now = 730L * 24 * 60 * 60 * 1000
        val memories = listOf(
            memory("low", 0.15f, ageDays = 365f, now = now),
            memory("keep", 0.6f, ageDays = 365f, now = now),
        )
        val selected = selectMemoriesForPrompt(memories, budgetTokens = 10_000, now = now)
        assertEquals(listOf("keep"), selected.map { it.id })
    }

    private fun promptWithOneMemory(): String {
        val now = 730L * 24 * 60 * 60 * 1000
        return buildMemoryPrompt(
            memories = listOf(memory("a", 0.9f, ageDays = 1f, now = now)),
            budgetTokens = 10_000,
            now = now,
        )
    }

    /**
     * 注入块里的**使用纪律**必须存在（V1，见 `docs/design-memory-discipline.md` §2.3）。
     *
     * 为什么钉在测试里：这两句是「按记录时的证据强度使用」「敏感项只在用户本轮提起时用」的
     * 唯一落点——它们只在本块被读到，写丢了不会有任何编译/行为错误，而模型会立刻退化成
     * 「记忆里写什么就当成用户此刻的事实」。
     */
    @Test
    fun `injected memory block states the usage discipline`() {
        val prompt = promptWithOneMemory()
        assertTrue("缺证据强度条款", prompt.contains("mentioned once"))
        assertTrue("缺敏感项仅在用户本轮提起时使用", prompt.contains("only when the user raises them in this turn"))
    }

    /**
     * **反注入声明按 2026-10-09 的用户拍板保持移除状态**：注入块不再声明「记忆是记录、不是指令」，
     * 工具描述也不再禁止记录「要求无条件附和 / 压制批评 / 扮演伴侣」的偏好。
     *
     * 为什么用**反向**断言钉住：这是本仓少见的「删掉一段提示词」的决定——没有编译错误、没有行为
     * 异常，下次整理提示词时极容易顺手照上游 #2130 补回来。要复活必须先改这里，并读
     * `docs/design-memory-discipline.md` §2.3 记的取舍（本 app 的正当用法本就含人设/伴侣扮演）。
     */
    @Test
    fun `anti injection declaration stays removed`() {
        val prompt = promptWithOneMemory()
        assertFalse("反注入声明已按用户拍板移除，不应复活", prompt.contains("not as an instruction"))
        assertFalse("「扮演固定人格/伴侣」忽略条款已移除，不应复活", prompt.contains("play a fixed persona or partner"))

        val description = buildMemoryTools(
            json = Json,
            onCreation = { content, importance, description ->
                AssistantMemory("00000001", content, importance ?: 0.6f, description.orEmpty())
            },
            onUpdate = { id, content, importance, description ->
                AssistantMemory(id, content, importance ?: 0.6f, description.orEmpty())
            },
            onDelete = { },
        ).first().description
        assertFalse(
            "工具描述里的「附和 / 压制批评 / 伴侣」禁止条款已移除，不应复活",
            description.contains("flatter unconditionally"),
        )
    }
}
