package app.androidrik.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import app.androidrik.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 工具审批丢失修复（上游 55506496 适配）的会话层语义验证：
 * - setJob(cancelPrevious=false)：连续审批不清前任，决策串行落地
 * - cancelJobs()：stopGeneration 全链取消（含排队等待前任的协程）
 * - afterPreviousGeneration：等前任完成后再落地本决策
 */
class ConversationSessionTest {

    private fun newSession(scope: CoroutineScope) = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(Uuid.random()),
        scope = scope,
        onIdle = {},
    )

    @Test
    fun `beginNewTurn clears the previous reply message id`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            assertNull(session.replyMessageId.value)
            session.replyMessageId.value = Uuid.random()
            session.beginNewTurn()
            assertNull(session.replyMessageId.value)

            // A later round must clear its own reply too, not just the initial round.
            session.replyMessageId.value = Uuid.random()
            session.beginNewTurn()
            assertNull(session.replyMessageId.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `setJob records generation start time before the lazy job runs`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            var seenByJob: Long? = null
            val job = scope.launch(start = CoroutineStart.LAZY) {
                seenByJob = session.generationStartedAt.value
            }
            assertNull(session.generationStartedAt.value)
            val beforeRegistration = System.currentTimeMillis()
            session.setJob(job)
            val recorded = session.generationStartedAt.value
            val afterRegistration = System.currentTimeMillis()

            assertNotNull(recorded)
            assertTrue(recorded!! in beforeRegistration..afterRegistration)
            assertNull("Registering a LAZY job must not execute its body", seenByJob)
            job.start()
            job.join()
            assertEquals(recorded, seenByJob)
            // Keep the timestamp after completion so immediate failures belong to this round.
            assertEquals(recorded, session.generationStartedAt.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `setJob null does not fabricate or replace a generation start time`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            session.setJob(null)
            assertNull(session.generationStartedAt.value)
            session.generationStartedAt.value = 123L
            session.setJob(null)
            assertEquals(123L, session.generationStartedAt.value!!)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `cleanup resets floating generation state and cancels its job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            val job = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(job)
            session.replyMessageId.value = Uuid.random()
            session.processingStatus.value = "Processing"
            assertNotNull(session.generationStartedAt.value)

            session.cleanup()

            assertTrue(job.isCancelled)
            assertNull(session.getJob())
            assertNull(session.generationJob.value)
            assertFalse(session.isGenerating)
            assertNull(session.replyMessageId.value)
            assertNull(session.generationStartedAt.value)
            assertNull(session.processingStatus.value)
            // Repeated cleanup must keep the reset state.
            session.cleanup()
            assertNull(session.replyMessageId.value)
            assertNull(session.generationStartedAt.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `cancelPrevious=false keeps predecessor alive for serialized approval`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            val gate = CompletableDeferred<Unit>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) { gate.await() }
            }
            session.setJob(first)

            // 第二个审批不 cancel 前任（55506496 核心：连续审批不再互相打断）
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { /* 决策落地 */ }
            }
            session.setJob(second, cancelPrevious = false)
            second.start()  // LAZY 协程需显式启动后 isActive 才为 true

            assertFalse("前任不应被新审批取消", first.isCancelled)
            assertTrue("新任应存活", second.isActive)
            gate.complete(Unit)
            first.join()
            second.join()
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `cancelJobs stops waiting approval chain`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            var executed = false
            val first = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(first)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { executed = true }
            }
            session.setJob(second, cancelPrevious = false)
            second.start()

            // stopGeneration 全链取消：等待者（second）先取消，前任（first）随后
            session.cancelJobs().forEach { it.join() }

            assertFalse("排队审批不应在 stop 后执行", executed)
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `cancelPrevious=true still cancels predecessor on replace`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope)
        try {
            val first = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(first)
            // 发送新消息等场景保持默认语义：cancel 前任
            val second = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(second)
            second.start()
            assertTrue("默认 setJob 应取消前任", first.isCancelled)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
