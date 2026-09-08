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
