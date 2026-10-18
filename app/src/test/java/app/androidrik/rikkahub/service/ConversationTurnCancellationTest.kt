package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「轮」的后台任务归属（`ConversationSession.backgroundTaskOwner` / `beginNewTurn` /
 * `onTurnCancelled`）。
 *
 * 要钉住的语义只有两条，但两条都容易被后来的重构悄悄改坏：
 * 1. **令牌在 `setJob` 注册那一刻捕获**，不是取消回调执行时现读——`regenerateAtMessage`
 *    是「先 cancel 前任、再换新令牌」，现读会拿到下一轮的令牌，孤儿进程就永远收不掉。
 * 2. **只有「取消」才回调**，正常走完不回调——正常结束后台任务继续跑正是
 *    `workspace_run_background` 的契约。
 */
class ConversationTurnCancellationTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun newSession(
        scope: CoroutineScope,
        onTurnCancelled: (String) -> Unit = {},
    ) = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random(), assistantId = Uuid.random()),
        scope = scope,
        onIdle = {},
        onTurnCancelled = onTurnCancelled,
    )

    @Test
    fun `beginNewTurn rotates the background task owner`() {
        val session = newSession(newScope())
        val first = session.backgroundTaskOwner

        session.beginNewTurn()

        assertNotEquals(
            "换了新的一轮就必须换令牌，否则上一轮的取消会杀掉这一轮的任务",
            first,
            session.backgroundTaskOwner,
        )
    }

    @Test
    fun `cancelling a turn reports the token captured when the job was registered`() = runBlocking {
        val scope = newScope()
        val reported = CompletableDeferred<String>()
        val session = newSession(scope) { reported.complete(it) }

        session.beginNewTurn()
        val tokenOfThisTurn = session.backgroundTaskOwner
        val job = scope.launch { awaitCancellation() }
        session.setJob(job)

        // 复现 regenerate 的时序：令牌**先**换新，前任的取消回调**后**到。
        // 回调里若现读 backgroundTaskOwner，报出来的会是下一轮的令牌。
        session.beginNewTurn()
        job.cancel()

        assertEquals(
            "取消回调必须用注册时捕获的令牌，否则孤儿进程收不掉",
            tokenOfThisTurn,
            reported.await(),
        )
    }

    @Test
    fun `a normally finished turn does not report a cancellation`() = runBlocking {
        val scope = newScope()
        val reported = CompletableDeferred<String>()
        val session = newSession(scope) { reported.complete(it) }

        session.beginNewTurn()
        val job = scope.launch { /* 正常走完 */ }
        session.setJob(job)
        job.join()
        // 完成回调是异步派发的：给一拍再断言
        delay(50)

        assertFalse("正常结束不该收掉后台任务（那正是它存在的意义）", reported.isCompleted)
    }

    @Test
    fun `each turn gets an independent token so earlier turns are unaffected`() {
        val session = newSession(newScope())
        val tokens = buildSet {
            repeat(5) {
                session.beginNewTurn()
                add(session.backgroundTaskOwner)
            }
        }

        assertEquals(5, tokens.size)
    }
}
