package app.androidrik.rikkahub.data.ai

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 持久计数器（`design-context-purification.md` §7 缺口）的口径测试。
 *
 * 重点不是「写了个 json」，而是 **§12 已定 1 的双口径算得对**：
 * - 命中会话占比的分母排除「护栏停止过的会话」（§14 假阳性来源）；
 * - 中位数按**会话**取（同一会话多步重复命中不能把量累计放大），残留量取会话内单次最大值；
 * - 会话表有上限、损坏文件不崩、没数据时返回 null。
 */
class PurgeObservationStoreTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("purge-observation-test").toFile().also { roots += it }

    private fun result(
        savedChars: Int,
        decisions: Int = if (savedChars > 0) 1 else 0,
    ): ContextPurifier.Result = ContextPurifier.Result(
        windowCount = 3,
        messages = emptyList(),
        decisions = List(decisions) {
            ContextPurifier.Decision(
                rule = ContextPurifier.Rule.CYCLE_TAIL,
                messageIndex = 0,
                partIndex = 0,
                detail = "test",
            )
        },
        savedChars = savedChars,
        windowHash = 42,
    )

    private fun record(
        dir: File,
        session: String,
        savedChars: Int,
        flipped: Boolean = false,
        maxSessions: Int = 2000,
    ) = PurgeObservationStore.record(
        filesDir = dir,
        sessionKey = session,
        groupKey = "Test Provider/model-x",
        result = result(savedChars),
        flipped = flipped,
        maxSessions = maxSessions,
    )

    @Test
    fun `counts requests hits decisions savedChars and flips`() {
        val dir = tempDir()
        record(dir, "s1", savedChars = 100)
        record(dir, "s1", savedChars = 0)
        record(dir, "s2", savedChars = 300, flipped = true)
        record(dir, "s3", savedChars = 0)

        val summary = requireNotNull(PurgeObservationStore.summary(dir))
        assertEquals(4, summary.requests)
        assertEquals(2, summary.hitRequests)
        assertEquals(2, summary.decisions)
        assertEquals(400L, summary.savedCharsSum)
        assertEquals(300, summary.savedCharsMax)
        assertEquals(1, summary.flips)
        assertEquals("s1/model-x 分组", 1, summary.groups.size)
        val group = requireNotNull(summary.groups["Test Provider/model-x"])
        assertEquals(4, group.requests)
        assertEquals(2, group.hitRequests)
    }

    @Test
    fun `session residue uses the max single request not the sum`() {
        val dir = tempDir()
        // 同一窗口在工具循环的每一步都会重复命中：累加会放大 N 倍
        record(dir, "s1", savedChars = 100)
        record(dir, "s1", savedChars = 100)
        record(dir, "s1", savedChars = 500)

        val summary = requireNotNull(PurgeObservationStore.summary(dir))
        assertEquals(1, summary.hitSessions)
        assertEquals("命中会话残留量 = 会话内单次最大值", 500, summary.hitSessionSavedCharsMedian)
    }

    @Test
    fun `median is taken across hit sessions`() {
        val dir = tempDir()
        record(dir, "a", savedChars = 100)
        record(dir, "b", savedChars = 300)
        record(dir, "c", savedChars = 5000)
        record(dir, "miss", savedChars = 0)

        val summary = requireNotNull(PurgeObservationStore.summary(dir))
        assertEquals(4, summary.eligibleSessions)
        assertEquals(3, summary.hitSessions)
        assertEquals("100/300/5000 的中位数", 300, summary.hitSessionSavedCharsMedian)
        assertEquals(0.75, summary.hitSessionRatio, 1e-9)
    }

    @Test
    fun `guard stopped sessions are excluded from ratio and median`() {
        val dir = tempDir()
        record(dir, "hit-small", savedChars = 100)
        record(dir, "hit-degenerate", savedChars = 5000) // 该会话护栏停止过 ⇒ 假阳性来源
        record(dir, "guard-only", savedChars = 0)
        record(dir, "miss", savedChars = 0)
        PurgeObservationStore.markGuardStopped(dir, "hit-degenerate")
        PurgeObservationStore.markGuardStopped(dir, "guard-only")

        val summary = requireNotNull(PurgeObservationStore.summary(dir))
        assertEquals(2, summary.guardStoppedSessions)
        assertEquals(2, summary.eligibleSessions)
        assertEquals(1, summary.hitSessions)
        assertEquals("被排除会话不得进入中位数", 100, summary.hitSessionSavedCharsMedian)
        assertEquals(0.5, summary.hitSessionRatio, 1e-9)
    }

    @Test
    fun `data survives a reload from disk`() {
        val dir = tempDir()
        record(dir, "s1", savedChars = 1234)
        // 写盘是异步的（不能阻塞生成协程）；summary 会先 flush 写队列再读
        val first = requireNotNull(PurgeObservationStore.summary(dir))
        assertTrue("flush 后必须已经落盘", File(dir, PurgeObservationStore.FILE_NAME).isFile)

        // summary 每次都从磁盘读 ⇒ 相当于进程重启后的结算
        val second = requireNotNull(PurgeObservationStore.summary(dir))
        assertEquals(first, second)
        assertEquals(1234, second.hitSessionSavedCharsMedian)
    }

    @Test
    fun `corrupt file is reset instead of crashing`() {
        val dir = tempDir()
        File(dir, PurgeObservationStore.FILE_NAME).writeText("{ not json ")
        record(dir, "s1", savedChars = 10)

        val summary = requireNotNull(PurgeObservationStore.summary(dir))
        assertEquals(1, summary.requests)
        assertEquals(1, summary.hitSessions)
    }

    @Test
    fun `no data returns null`() {
        assertNull(PurgeObservationStore.summary(tempDir()))
    }

    @Test
    fun `session table is bounded`() {
        val dir = tempDir()
        repeat(10) { index -> record(dir, "session-$index", savedChars = index * 10, maxSessions = 3) }

        val summary = requireNotNull(PurgeObservationStore.summary(dir))
        assertTrue("会话表必须被上限兜住，实际 ${summary.eligibleSessions}", summary.eligibleSessions <= 3)
        assertEquals("淘汰不影响请求总数", 10, summary.requests)
    }

    @Test
    fun `unwritable location does not throw`() {
        val blocker = File(tempDir(), "blocker").apply { writeText("I am a file") }
        // filesDir 传一个普通文件 ⇒ 落盘失败，但观察不能影响生成主流程
        val result = runCatching { record(blocker, "s1", savedChars = 10) }
        assertTrue("落盘失败也不能抛（观察数据不允许影响生成）", result.isSuccess)
        // 没有落盘就没有可结算的数据：summary 如实返回 null，而不是假装有
        assertNull(PurgeObservationStore.summary(blocker))
    }
}
