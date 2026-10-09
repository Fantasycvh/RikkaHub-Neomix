package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * 做梦框架（V3）的调度策略测试，见 `docs/design-memory-consolidation.md`。
 *
 * **为什么必须用虚拟时间**（`runTest` + `advanceTimeBy`）：被测的全是「什么时候该整理」——
 * 空闲 2 分钟后、攒够轮数就不等、切后台/离开对话立刻开始、失败后不再自动重试、一次只跑一个。
 * 用真实 `delay` 测这些要么慢要么飘；虚拟时间能把「2 分钟之后」精确推到，也能把「期间没有多跑一次」
 * 断言死。**仓库里这是第一处用 `kotlinx-coroutines-test` 的测试**（见 `app/build.gradle.kts` 的注释）。
 */
class MemoryConsolidationSchedulerTest {

    private val memoryId = "assistant-1"
    private val otherMemoryId = "assistant-2"
    private val chat = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    private val otherChat = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())

    private data class Call(val conversationIds: Set<Uuid>, val fullReview: Boolean)

    /** 扮演 worker（数据库 + 模型）：[pending] 是还没整理的轮数，整理成功后清掉 */
    private inner class FakeWorker : MemoryConsolidationWorker {
        val pending = mutableMapOf<Uuid, Int>()

        /** 不在映射里的对话都属于 [memoryId] */
        val memoryIdOf = mutableMapOf<Uuid, String>()
        val calls = mutableListOf<Call>()
        var failure: Exception? = null

        /** 整理卡在这里（测「运行中」的状态、保活、运行期间新结束的轮次） */
        var gate: CompletableDeferred<Unit>? = null

        /** 查数据库卡在这里（测「重新扫库期间又有轮次结束」） */
        var scanGate: CompletableDeferred<Unit>? = null

        /** 每次扫库是不是「手动整理」路径（频次上限只约束自动路径，见 findPending 契约） */
        val manualScans = mutableListOf<Boolean>()

        private fun item(id: Uuid, turns: Int) = PendingMemoryConversation(id, memoryIdOf[id] ?: memoryId, turns)

        override suspend fun pendingOf(conversation: Conversation) =
            pending[conversation.id]?.let { item(conversation.id, it) }

        override suspend fun memoryIdsWithUpdates() =
            pending.map { (id, turns) -> item(id, turns).memoryId }.distinct()

        override suspend fun findPending(memoryId: String, manual: Boolean): List<PendingMemoryConversation> {
            val found = pending.map { (id, turns) -> item(id, turns) }.filter { it.memoryId == memoryId }
            manualScans += manual
            scanGate?.await()
            return found
        }

        override suspend fun markConsolidated(conversation: Conversation) {
            pending.remove(conversation.id)
        }

        override suspend fun consolidate(
            memoryId: String,
            conversationIds: Set<Uuid>,
            fullReview: Boolean,
        ): MemoryConsolidationRun? {
            calls += Call(conversationIds, fullReview)
            gate?.await()
            failure?.let { throw it }
            conversationIds.forEach { pending.remove(it) }
            return MemoryConsolidationRun(
                memoryId = memoryId,
                finishedAt = 0,
                conversationIds = conversationIds,
                fullReview = fullReview,
                changedMemories = 1,
            )
        }
    }

    private val worker = FakeWorker()
    private var appVisible = true
    private var activeHolds = 0
    private var totalHolds = 0

    private fun TestScope.scheduler() = MemoryConsolidationScheduler(
        scope = backgroundScope,
        worker = worker,
        keepAlive = {
            activeHolds++
            totalHolds++
            AutoCloseable { activeHolds-- }
        },
        isAppVisible = { appVisible },
        idleDelay = 2.minutes,
        maxPendingTurns = 3,
    )

    /** 一轮对话结束：[pendingTurns] 是该对话累计的未整理轮数（worker 从数据库算出来的） */
    private fun MemoryConsolidationScheduler.finishTurn(conversation: Conversation, pendingTurns: Int) {
        worker.pending[conversation.id] = pendingTurns
        onTurnFinished(conversation)
    }

    // ---- 触发面 ----

    @Test
    fun `turns in quick succession are consolidated once after the chat goes idle`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 1)
        advanceTimeBy(1.minutes)
        scheduler.finishTurn(chat, pendingTurns = 2)
        advanceTimeBy(2.minutes - 1.seconds)
        assertTrue("还没到空闲时长就不该整理", worker.calls.isEmpty())
        assertEquals(2, scheduler.statusOf(memoryId).first().pendingTurns)

        advanceTimeBy(2.seconds)

        assertEquals(listOf(setOf(chat.id)), worker.calls.map { it.conversationIds })
        assertFalse("自动触发只整理待整理的对话，不做全库复查", worker.calls.single().fullReview)
        assertEquals(0, scheduler.statusOf(memoryId).first().pendingTurns)
    }

    @Test
    fun `reaching the turn limit consolidates without waiting`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()

        assertEquals("攒够 maxPendingTurns 就不再等空闲", 1, worker.calls.size)
    }

    @Test
    fun `leaving a conversation consolidates without waiting`() = runTest {
        val scheduler = scheduler()

        scheduler.onConversationLeft(otherChat.id)
        scheduler.finishTurn(chat, pendingTurns = 1)
        runCurrent()
        assertTrue("没有待整理内容的对话离开时不整理", worker.calls.isEmpty())

        scheduler.onConversationLeft(chat.id)
        runCurrent()

        assertEquals(1, worker.calls.size)
        // 已经整理过，原来的定时器不该再触发一次
        advanceTimeBy(5.minutes)
        assertEquals(1, worker.calls.size)
    }

    @Test
    fun `hiding the app consolidates pending turns without waiting`() = runTest {
        val scheduler = scheduler()

        scheduler.onAppHidden()
        scheduler.finishTurn(chat, pendingTurns = 1)
        runCurrent()
        assertTrue(worker.calls.isEmpty())

        scheduler.onAppHidden()
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertEquals("前台应用切后台不需要额外保活", 0, activeHolds)
    }

    @Test
    fun `one run covers every pending conversation of the store`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 1)
        scheduler.finishTurn(otherChat, pendingTurns = 1)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals("以记忆库为单位：一次覆盖库里所有待整理对话", listOf(setOf(chat.id, otherChat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `pending conversations found at startup are consolidated after the idle delay`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 2

        scheduler.resumePending()
        assertEquals("启动补漏要先把状态暴露出来", 2, scheduler.statusOf(memoryId).first().pendingTurns)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `startup resumes every memory store that has pending conversations`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 2
        worker.pending[otherChat.id] = 1
        worker.memoryIdOf[otherChat.id] = otherMemoryId

        scheduler.resumePending()
        assertEquals("每个记忆库各查各的：对话少的库不能被挤掉", 2, scheduler.statusOf(memoryId).first().pendingTurns)
        assertEquals(1, scheduler.statusOf(otherMemoryId).first().pendingTurns)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(
            setOf(setOf(chat.id), setOf(otherChat.id)),
            worker.calls.map { it.conversationIds }.toSet(),
        )
    }

    @Test
    fun `an unknown memory store reports the default status`() = runTest {
        val scheduler = scheduler()

        val status = scheduler.statusOf("never-touched").first()
        assertEquals(0, status.pendingTurns)
        assertFalse(status.running)
        assertNull(status.lastRun)
    }

    // ---- 保活 ----

    @Test
    fun `the process is kept alive for as long as a run lasts`() = runTest {
        val scheduler = scheduler()
        worker.gate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        assertEquals(1, activeHolds)

        worker.gate!!.complete(Unit)
        runCurrent()
        assertEquals("整理结束必须放开保活", 0, activeHolds)
    }

    @Test
    fun `a turn that finishes while the app is hidden keeps the process alive until it is consolidated`() = runTest {
        val scheduler = scheduler()
        appVisible = false
        worker.gate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 1)
        // 还没查出有没有待整理的内容就先占住保活（否则进程可能在查询期间被冻结）
        assertEquals(1, activeHolds)
        advanceTimeBy(2.minutes - 1.seconds)
        // 和在前台时一样等空闲，等的时候一直占着
        assertTrue(worker.calls.isEmpty())
        assertEquals(1, activeHolds)

        advanceTimeBy(2.seconds)
        assertEquals(1, worker.calls.size)
        assertTrue(activeHolds > 0)

        worker.gate!!.complete(Unit)
        runCurrent()
        assertEquals(0, activeHolds)
    }

    @Test
    fun `turns that finish in quick succession while the app is hidden are consolidated once`() = runTest {
        val scheduler = scheduler()
        appVisible = false

        scheduler.finishTurn(chat, pendingTurns = 1)
        advanceTimeBy(1.minutes)
        scheduler.finishTurn(chat, pendingTurns = 2)
        runCurrent()
        // 前一次的保活随被顶掉的定时器放开，中间没有断过（旧句柄释放与新句柄获取在同一帧）
        assertEquals(1, activeHolds)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(1, worker.calls.size)
        assertEquals(0, activeHolds)
    }

    @Test
    fun `reaching the turn limit while the app is hidden consolidates without waiting`() = runTest {
        val scheduler = scheduler()
        appVisible = false

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertEquals("不等空闲就整理 ⇒ 提前拿的保活必须放开", 0, activeHolds)
    }

    @Test
    fun `nothing is held once a hidden turn turns out to have nothing new`() = runTest {
        val scheduler = scheduler()
        appVisible = false

        // worker 说这个对话不用整理（隐身 / 停用助手 / 没有新轮次）
        scheduler.onTurnFinished(chat)
        runCurrent()

        assertTrue(worker.calls.isEmpty())
        assertEquals(1, totalHolds)
        assertEquals("提前占的保活不能泄漏", 0, activeHolds)
    }

    // ---- 失败与并发 ----

    @Test
    fun `a failed run is reported keeps the turns pending and is not retried on its own`() = runTest {
        val scheduler = scheduler()
        worker.failure = IllegalStateException("model unavailable")

        scheduler.finishTurn(chat, pendingTurns = 3)
        advanceTimeBy(10.minutes)

        assertEquals(1, worker.calls.size)
        val status = scheduler.statusOf(memoryId).first()
        assertEquals("model unavailable", status.lastRun?.error)
        assertEquals("失败后待整理的轮数必须留着", 3, status.pendingTurns)
        assertFalse(status.running)

        // 切后台、离开对话也不会再试（否则模型一直报错时会一次次重打）
        scheduler.onAppHidden()
        scheduler.onConversationLeft(chat.id)
        runCurrent()
        assertEquals(1, worker.calls.size)

        // 下一轮对话结束时连同之前的一起重试
        worker.failure = null
        scheduler.finishTurn(chat, pendingTurns = 4)
        runCurrent()
        assertEquals(2, worker.calls.size)
        assertNull(scheduler.statusOf(memoryId).first().lastRun?.error)
    }

    @Test
    fun `a cancelled run is not treated as a failure`() = runTest {
        val scheduler = scheduler()
        worker.failure = CancellationException("process going away")

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        assertEquals(1, worker.calls.size)
        assertFalse(scheduler.statusOf(memoryId).first().running)

        // 取消不是「整理失败」：不能像失败那样把记忆库挂起，下一轮就该重试
        worker.failure = null
        scheduler.finishTurn(chat, pendingTurns = 4)
        runCurrent()
        assertEquals(2, worker.calls.size)
    }

    @Test
    fun `turns that finish during a run are consolidated in a later run`() = runTest {
        val scheduler = scheduler()
        worker.gate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        assertTrue(scheduler.statusOf(memoryId).first().running)

        worker.pending[otherChat.id] = 1
        worker.gate!!.complete(Unit)
        runCurrent()
        assertFalse(scheduler.statusOf(memoryId).first().running)
        assertEquals("运行期间结束的轮次不能被这次整理清掉", 1, scheduler.statusOf(memoryId).first().pendingTurns)

        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id), setOf(otherChat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `a turn that finishes while the store is being rescanned is not lost`() = runTest {
        val scheduler = scheduler()
        worker.scanGate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        // 整理完了，正在重新查数据库；这时另一个对话的一轮结束了，查询结果里没有它
        assertEquals(1, worker.calls.size)
        scheduler.finishTurn(otherChat, pendingTurns = 1)
        runCurrent()

        worker.scanGate!!.complete(Unit)
        runCurrent()
        assertEquals("重新扫库的结果只能并入、不能覆盖", 1, scheduler.statusOf(memoryId).first().pendingTurns)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id), setOf(otherChat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `tapping organize again while it is starting does not queue a second review`() = runTest {
        val scheduler = scheduler()
        worker.scanGate = CompletableDeferred()

        scheduler.organize(memoryId)
        runCurrent()
        assertTrue(scheduler.statusOf(memoryId).first().running)
        scheduler.organize(memoryId)

        worker.scanGate!!.complete(Unit)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertFalse(scheduler.statusOf(memoryId).first().running)
    }

    // ---- 手动整理与通知 ----

    @Test
    fun `organizing the store reviews it even when no conversation is pending`() = runTest {
        val scheduler = scheduler()

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertTrue("手动整理要顺带清理整个记忆库", worker.calls.single().fullReview)
        assertTrue(worker.calls.single().conversationIds.isEmpty())
    }

    @Test
    fun `organizing the store also hands over the pending conversations`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 1)
        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(listOf(Call(setOf(chat.id), fullReview = true)), worker.calls)
    }

    @Test
    fun `manual organize tells the worker this scan is manual`() = runTest {
        val scheduler = scheduler()

        scheduler.organize(memoryId)
        runCurrent()

        assertTrue("手动整理必须带 manual 标记（频次上限只约束自动路径）", worker.manualScans.first())
        assertFalse(
            "整理之后的自动重扫不是手动路径（它要受频次上限约束，否则限流形同虚设）",
            worker.manualScans.last(),
        )
        assertTrue(worker.manualScans.size >= 2)
    }

    @Test
    fun `only automatic runs are announced`() = runTest {
        val scheduler = scheduler()
        val announced = mutableListOf<MemoryConsolidationRun>()
        backgroundScope.launch { scheduler.automaticRuns.collect { announced += it } }
        runCurrent()

        worker.pending[chat.id] = 1
        scheduler.organize(memoryId)
        runCurrent()
        assertEquals(1, worker.calls.size)
        assertTrue("手动整理的结果走状态流，不重复发通知", announced.isEmpty())

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        assertEquals(1, announced.size)
    }

    /**
     * **框架不许认识存储**（源码扫描，与 [ThinkingDegenerationWiringTest] 同款）。
     *
     * 这条约束是本批唯一的设计目标，也是最容易被顺手破坏的一条：一旦有人在调度器里查 `MemoryEntity`
     * 或读 `AssistantMemory`（"就查一下而已"），「只认 memoryId + worker 接口」就名存实亡，
     * V4 换 Markdown 文件库时这层必须跟着改——那时才发现，改动面已经从 worker 漏进了调度策略。
     */
    @Test
    fun `the scheduler only knows memory ids and the worker interface`() {
        val source = read("app/androidrik/rikkahub/data/ai/MemoryConsolidationScheduler.kt")

        listOf("MemoryEntity", "MemoryDAO", "MemoryRepository", "AssistantMemory", "ConversationEntity")
            .forEach { forbidden ->
                assertFalse(
                    "调度器不许认识存储（出现 $forbidden）：整理什么、怎么存都在 worker 后面",
                    source.contains(forbidden),
                )
            }
        assertTrue(
            "手动整理的入口必须只按 memoryId 寻址",
            source.contains("fun organize(memoryId: String)"),
        )
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
