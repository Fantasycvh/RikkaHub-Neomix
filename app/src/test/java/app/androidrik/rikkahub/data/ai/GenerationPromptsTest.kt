package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 记忆注入的打分校准 + token 预算截断（`docs/design/design-memory-scoring.md`）。
 *
 * 设计要点：选择由 `importance × 时效衰减` 打分 + 预算决定；**输出顺序按 sortKey 稳定**，
 * 以免 system prompt 前缀每次请求漂移、打掉供应商缓存。
 */
class GenerationPromptsTest {

    private val DAY_MS = 24L * 60 * 60 * 1000

    /** 测试辅助：token 是随机 opaque 串，稳定序由 sortKey（= 自增主键）决定。 */
    private fun memory(
        seq: Int,
        content: String = "mem-%03d".format(seq),
        importance: Float = 0.6f,
        updatedAt: Long = 0L,
    ) = AssistantMemory(
        id = "tok$seq",
        content = content,
        importance = importance,
        sortKey = seq,
        updatedAt = updatedAt,
    )

    // ===== 打分 =====

    @Test
    fun `score is importance times recency factor`() {
        val now = 1_000L * DAY_MS
        // 刚更新：recency=1 → 分数 = importance × 1.0
        assertEquals(0.9f, memoryScore(memory(1, importance = 0.9f, updatedAt = now), now), 0.001f)
        // 半衰期（30 天）：recency=0.5 → importance × (0.55+0.45×0.5)
        assertEquals(
            0.9f * (0.55f + 0.45f * 0.5f),
            memoryScore(memory(1, importance = 0.9f, updatedAt = now - 30 * DAY_MS), now),
            0.001f,
        )
    }

    @Test
    fun `unknown updatedAt is treated as fresh not stale`() {
        val now = 1_000L * DAY_MS
        // updatedAt=0 表示「未知」（迁移回填前的旧数据）——按不衰减处理，
        // 否则老记忆会被当成极旧而静默沉底。
        assertEquals(
            memoryScore(memory(1, importance = 0.8f, updatedAt = now), now),
            memoryScore(memory(2, importance = 0.8f, updatedAt = 0L), now),
            0.001f,
        )
    }

    @Test
    fun `high importance old memory can outrank low importance new one`() {
        val now = 1_000L * DAY_MS
        // 这正是打分校准相对「取最新」的意义：身份类记忆久未改动，仍应留在上下文
        val identityOld = memory(1, importance = 1.0f, updatedAt = now - 120 * DAY_MS)
        val minorNew = memory(2, importance = 0.3f, updatedAt = now)
        assertTrue(memoryScore(identityOld, now) > memoryScore(minorNew, now))
    }

    // ===== 分档量化（前缀稳定性） =====

    @Test
    fun `score bucket is stable over practical time drift`() {
        // 量化到 0.1 一档（就近取整）→ 常见整数重要度（0.8/0.9）落在桶心，
        // 可稳定数天而非「衰减一点点就掉档」。这是「前缀缓存不被反复打掉」的实现基础。
        val base = 1_000L * DAY_MS
        val m = memory(1, importance = 0.8f, updatedAt = base)

        val bucketNow = memoryScoreBucket(m, base)
        assertEquals("1 小时后不应换桶", bucketNow, memoryScoreBucket(m, base + 60 * 60 * 1000L))
        assertEquals("1 天后不应换桶", bucketNow, memoryScoreBucket(m, base + DAY_MS))
    }

    @Test
    fun `common round importances map to distinct buckets`() {
        val now = 1_000L * DAY_MS
        // 模型给的分档值应有清晰区分（0.9 > 0.7 > 0.5 > 0.3）
        fun bucketOf(importance: Float) =
            memoryScoreBucket(memory(1, importance = importance, updatedAt = now), now)
        assertTrue(bucketOf(0.9f) > bucketOf(0.7f))
        assertTrue(bucketOf(0.7f) > bucketOf(0.5f))
        assertTrue(bucketOf(0.5f) > bucketOf(0.3f))
    }

    // ===== 预算截断 =====

    @Test
    fun `budget truncates injected count`() {
        val many = (1..200).map { memory(it, content = "x".repeat(400)) }
        val selected = selectMemoriesForPrompt(many, budgetTokens = 500)
        // 400 个非 CJK 字符 ≈ 100 token + 8 开销；500 token 预算只装得下少数几条，远少于 200
        assertTrue("expected truncation, got ${selected.size}", selected.size in 1..6)
    }

    @Test
    fun `one huge memory does not block smaller ones`() {
        // 超预算时应当「跳过本条继续」而非 break——否则一条长记忆会把后续预算全堵死。
        // 布局关键：huge 的分数**居中**（前有高分小记忆、后有低分小记忆），
        // 这样它不会被「首条超预算也入选」的兜底规则直接放进来，才能真正测到 skip 行为。
        val highSmall = memory(1, content = "a".repeat(40), importance = 0.95f, updatedAt = 0L)
        val huge = memory(2, content = "x".repeat(40_000), importance = 0.6f, updatedAt = 0L)
        val lowSmalls = (3..6).map { memory(it, content = "b".repeat(40), importance = 0.3f, updatedAt = 0L) }

        val selected = selectMemoriesForPrompt(
            listOf(highSmall, huge) + lowSmalls,
            budgetTokens = 200,
            now = 0L,
        )

        assertTrue("huge memory was injected despite not fitting", huge !in selected)
        assertTrue("high-score small memory should be kept", highSmall in selected)
        // 关键断言：huge 之后的低分小记忆仍应入选（证明是 continue 而非 break）
        assertTrue(
            "budget was blocked after the huge memory (break instead of continue?)",
            selected.size >= 3,
        )
    }

    @Test
    fun `tiny budget still returns something rather than empty`() {
        // 首条即使超预算也入选：避免「预算极小 → 空集合」这种最坏结果
        val selected = selectMemoriesForPrompt(
            listOf(memory(1, content = "x".repeat(10_000))),
            budgetTokens = 1,
        )
        assertEquals(1, selected.size)
    }

    // ===== 输出顺序（前缀稳定性） =====

    @Test
    fun `output order is by sortKey not by score`() {
        val now = 1_000L * DAY_MS
        // seq 大的 importance 低 → 分数低，但输出仍应按 sortKey 降序（新在前）
        val low = memory(1, importance = 1.0f, updatedAt = now)
        val high = memory(9, importance = 0.2f, updatedAt = now)
        val selected = selectMemoriesForPrompt(listOf(low, high), budgetTokens = 10_000, now = now)
        assertEquals(listOf(9, 1), selected.map { it.sortKey })
    }

    @Test
    fun `selection is independent of input list order`() {
        val now = 1_000L * DAY_MS
        val memories = (1..40).map { memory(it, content = "x".repeat(50), updatedAt = now) }
        val ascending = selectMemoriesForPrompt(memories, 500, now)
        val descending = selectMemoriesForPrompt(memories.sortedByDescending { it.sortKey }, 500, now)
        assertEquals(ascending.map { it.id }, descending.map { it.id })
    }

    // ===== 提示词装配 =====

    @Test
    fun `build memory prompt emits opaque token as id not sequential number`() {
        val prompt = buildMemoryDataPrompt(listOf(memory(seq = 7, content = "hello")))
        assertTrue(prompt.contains("\"id\": \"tok7\""))
    }

    @Test
    fun `build memory prompt respects token budget`() {
        val memories = (1..60).map { memory(it, content = "x".repeat(10_000)) }
        val prompt = buildMemoryDataPrompt(memories, budgetTokens = 8_000)
        // 旧实现按 100K 字符注入（60×10K = 600K 字符 → 巨大 prompt）；现在受 token 预算约束
        assertTrue("prompt too large: ${prompt.length}", prompt.length < 60_000)
    }

    @Test
    fun `build memory prompt with empty memories produces no scaffolding`() {
        // L0 纪律层在 system（恒定），与数据分离
        assertTrue(buildMemoryDisciplinePrompt().contains("**Memories**"))
        // L2+L3 数据层：无记忆时只剩前导标记 + 空数组，不渲染清单标题
        val data = buildMemoryDataPrompt(emptyList())
        assertEquals("Empty library must not spend budget on empty scaffolding", "", data)
        assertFalse("空记忆不应渲染清单标题", data.contains("**Memory index**"))
    }

    // ===== 边界 =====

    @Test
    fun `empty and single memory cases`() {
        assertTrue(selectMemoriesForPrompt(emptyList(), 8_000).isEmpty())
        assertEquals(1, selectMemoriesForPrompt(listOf(memory(1)), 8_000).size)
    }

    @Test
    fun `importance is clamped to valid range`() {
        val now = 0L
        val over = memory(1, importance = 5f, updatedAt = 0L)
        val under = memory(2, importance = -3f, updatedAt = 0L)
        // 越界值不应产生越界分数
        assertTrue(memoryScore(over, now) <= 1.0f)
        assertTrue(memoryScore(under, now) >= 0f)
        assertTrue(abs(memoryScore(over, now) - 1.0f) < 0.001f)
    }
}
