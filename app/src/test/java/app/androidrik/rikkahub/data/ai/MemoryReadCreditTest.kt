package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * **信用记账**（读取遥测 → 打分）的纯函数测试，见 `docs/design-memory-discipline.md` §4.6。
 *
 * 要守住的三件事：
 * 1. **零读数 = 完全等于接入前的公式**（逐位）——接入不得改变既有数据的注入行为；
 * 2. 读数只朝一个方向抬分（单调），且**有上限**（不能盖过用户显式写的 importance 太多）；
 * 3. 「从未读过」不等于「刚读过」（否则所有老记忆永久免于衰减）。
 */
class MemoryReadCreditTest {

    private val dayMs = 24 * 60 * 60 * 1000L
    private val now = 1_800L * dayMs

    private fun memory(
        id: String = "m",
        importance: Float = 0.6f,
        writtenDaysAgo: Float = 1f,
        readCount: Int = 0,
        lastReadDaysAgo: Float? = null,
    ) = AssistantMemory(
        id = id,
        content = "memory",
        importance = importance,
        updatedAt = now - (writtenDaysAgo * dayMs).toLong(),
        readCount = readCount,
        lastReadAt = lastReadDaysAgo?.let { now - (it * dayMs).toLong() } ?: 0L,
    )

    /** 接入前的公式（`importance × (0.55 + 0.45 × 时效衰减)`），用来做逐位回归对照。 */
    private fun legacyScore(importance: Float, writtenDaysAgo: Float): Float {
        val age = (writtenDaysAgo * dayMs).toLong()
        val recency = exp(-ln(2.0) * age / (30L * dayMs)).toFloat()
        return importance * (0.55f + 0.45f * recency)
    }

    @Test
    fun `zero read telemetry reproduces the legacy formula exactly`() {
        val cases = listOf(
            0.15f to 400f,
            0.6f to 0f,
            0.6f to 30f,
            0.8f to 90f,
            0.95f to 365f,
            1f to 1000f,
        )
        cases.forEach { (importance, ageDays) ->
            val actual = memoryScore(memory(importance = importance, writtenDaysAgo = ageDays), now)
            val expected = legacyScore(importance, ageDays)
            assertEquals(
                "importance=$importance age=${ageDays}d 的零读数打分必须与接入前一致",
                expected,
                actual,
                1e-6f,
            )
        }
    }

    @Test
    fun `read credit saturates at eight reads and is monotone`() {
        assertEquals(0f, memoryReadCredit(0), 0f)
        assertTrue("第一次读取就该有反馈", memoryReadCredit(1) > 0.3f)
        assertTrue("第一次读取不该满额", memoryReadCredit(1) < 0.35f)
        assertTrue("一半次数 ≈ 一半信用（对数）", memoryReadCredit(2) in 0.49f..0.51f)
        assertEquals("读满饱和次数即满额", 1f, memoryReadCredit(8), 1e-6f)
        assertEquals("再多读不再涨", 1f, memoryReadCredit(200), 1e-6f)
        for (n in 1..40) {
            assertTrue("信用必须单调不减：n=$n", memoryReadCredit(n) >= memoryReadCredit(n - 1))
        }
    }

    @Test
    fun `a recent read refreshes staleness`() {
        val stale = memory(importance = 0.6f, writtenDaysAgo = 400f)
        val reread = memory(importance = 0.6f, writtenDaysAgo = 400f, readCount = 3, lastReadDaysAgo = 1f)
        val staleScore = memoryScore(stale, now)
        val rereadScore = memoryScore(reread, now)
        assertTrue("老记忆被最近读过 ⇒ 活跃度按读取时间算（$staleScore → $rereadScore）", rereadScore > 0.5f)
        assertTrue("读过的必须排在同类没读过的前面", rereadScore > staleScore)
    }

    @Test
    fun `never read is not treated as freshly read`() {
        // 400 天前写入、从未读过：活跃度只能来自写入衰减 ⇒ ≈ 0.6 × 0.55
        val score = memoryScore(memory(importance = 0.6f, writtenDaysAgo = 400f), now)
        assertTrue("从未读过不得被当成刚读过（实际 $score）", score < 0.34f)
    }

    @Test
    fun `credit lifts a low importance memory back over the injection floor`() {
        val ignored = memory(id = "never-read", importance = 0.15f, writtenDaysAgo = 400f)
        val used = memory(id = "read-a-lot", importance = 0.15f, writtenDaysAgo = 400f, readCount = 8, lastReadDaysAgo = 1f)
        assertTrue("低重要度 + 老化仍在下限之下（${memoryScore(ignored, now)}）", memoryScore(ignored, now) < 0.2f)
        assertTrue("同一记忆被反复读取后必须越过下限（${memoryScore(used, now)}）", memoryScore(used, now) > 0.2f)

        val selected = selectMemoriesForPrompt(listOf(ignored, used), budgetTokens = 10_000, now = now)
        assertEquals("下限比较用的是新分数 ⇒ 只有被读过的那条入选", listOf("read-a-lot"), selected.map { it.id })
    }

    @Test
    fun `score never exceeds one and stays monotone in both read signals`() {
        assertEquals(
            "满分 + 刚读过 = 1.0（不越界）",
            1f,
            memoryScore(memory(importance = 1f, writtenDaysAgo = 0f, readCount = 8, lastReadDaysAgo = 0f), now),
            1e-6f,
        )
        for (importance in listOf(0.1f, 0.4f, 0.6f, 0.9f)) {
            var previous = -1f
            for (reads in 0..10) {
                val score = memoryScore(
                    memory(importance = importance, writtenDaysAgo = 200f, readCount = reads, lastReadDaysAgo = 2f),
                    now,
                )
                assertTrue("importance=$importance reads=$reads 时分数必须单调（$previous → $score）", score >= previous)
                assertTrue("分数不得越界：$score", score in 0f..1f)
                previous = score
            }
        }
    }

    @Test
    fun `credit alone cannot outrank a declared identity memory`() {
        // 信用上限的存在意义：读数最多把一条记忆抬到「默认档」，不能盖过用户/模型显式声明的身份信息
        val minorButRead = memory(importance = 0.2f, writtenDaysAgo = 0f, readCount = 100, lastReadDaysAgo = 0f)
        val identity = memory(importance = 0.95f, writtenDaysAgo = 0f)
        assertTrue(
            "反复读取的次要记忆（${memoryScore(minorButRead, now)}）不得压过刚写的身份记忆（${memoryScore(identity, now)}）",
            memoryScore(minorButRead, now) < memoryScore(identity, now),
        )
    }

    /**
     * **接线守卫**（源码扫描，与 [ThinkingDegenerationWiringTest] 同款）。
     *
     * 起因：读数的价值全在链路上——判据本身有单测，但任何一条链断了都是**静默失效**：
     * 实体映射漏掉 `read_count` ⇒ 分数永远按 0 算（看着一切正常）；`recordRead` 不写
     * `last_read_at` ⇒ 读取衰减永远为 0；工具装配的 `onRead` 不落库 ⇒ 根本没读数。
     */
    @Test
    fun `read telemetry is wired from dao to score`() {
        val repository = read("app/androidrik/rikkahub/data/repository/MemoryRepository.kt")
        assertTrue("实体 → 模型必须带上读数", repository.contains("readCount = readCount"))
        assertTrue("实体 → 模型必须带上最近读取时间", repository.contains("lastReadAt = lastReadAt"))
        assertTrue("读取唯一入口必须落一次遥测", repository.contains("memoryDAO.recordRead("))

        val dao = read("app/androidrik/rikkahub/data/db/dao/MemoryDAO.kt")
        assertTrue("计数自增", dao.contains("read_count = read_count + 1"))
        assertTrue("同时刷新最近读取时间", dao.contains("last_read_at = :now"))

        val prompts = read("app/androidrik/rikkahub/data/ai/GenerationPrompts.kt")
        assertTrue("打分必须吃读数信用", prompts.contains("memoryReadCredit(memory.readCount)"))
        assertTrue("打分必须吃最近读取时间", prompts.contains("memory.lastReadAt"))

        val factory = read("app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt")
        assertTrue("工具装配必须把 read 接到仓库（不接=读数永远是 0）", factory.contains("memoryRepository.readMemory("))
    }

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val file = srcRoot().resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }
}
