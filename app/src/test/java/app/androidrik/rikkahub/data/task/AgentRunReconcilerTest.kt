package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动对账（P0.5）：判据必须是「本次进程启动时刻」，且未记录时不得误伤。 */
class AgentRunReconcilerTest {

    @After
    fun tearDown() {
        ProcessStartClock.resetForTest()
    }

    @Test
    fun `process start clock keeps the first mark`() {
        ProcessStartClock.markStarted(now = 1000L)
        ProcessStartClock.markStarted(now = 9999L)
        assertEquals(1000L, ProcessStartClock.startedAt)
    }

    @Test
    fun `reconcile marks unfinished runs created before process start`() = runBlocking {
        val dao = RecordingAgentRunDAO()
        val reconciler = AgentRunReconciler(
            agentRunDAO = dao,
            now = { 5_000L },
            processStartedAt = { 1_000L },
        )

        val affected = reconciler.reconcileProcessRestart()

        assertEquals(3, affected)
        assertEquals(1_000L, dao.processStartedAt)
        assertEquals(5_000L, dao.finishedAt)
        assertEquals(AgentRunEntity.STATUS_INTERRUPTED, dao.interruptedStatus)
        assertEquals(AgentRunEntity.ERROR_PROCESS_RESTART, dao.reason)
        assertEquals(AgentRunReconciler.UNFINISHED_STATUSES, dao.unfinishedStatuses)
    }

    @Test
    fun `reconcile does nothing when process start was not recorded`() = runBlocking {
        val dao = RecordingAgentRunDAO()
        val reconciler = AgentRunReconciler(
            agentRunDAO = dao,
            now = { 5_000L },
            processStartedAt = { 0L },
        )

        assertEquals(0, reconciler.reconcileProcessRestart())
        assertFalse("未记录启动时刻时不能拿 0 当判据", dao.called)
    }

    @Test
    fun `unfinished statuses only cover non-terminal states`() {
        val statuses = AgentRunReconciler.UNFINISHED_STATUSES
        assertTrue(AgentRunEntity.STATUS_RUNNING in statuses)
        assertTrue(AgentRunEntity.STATUS_PENDING in statuses)
        assertTrue(AgentRunEntity.STATUS_AWAITING_APPROVAL in statuses)
        assertFalse(AgentRunEntity.STATUS_COMPLETED in statuses)
        assertFalse(AgentRunEntity.STATUS_CANCELLED in statuses)
        assertFalse(AgentRunEntity.STATUS_INTERRUPTED in statuses)
    }

    /** 只记录对账参数；其余 DAO 方法不会被对账调用。 */
    private class RecordingAgentRunDAO : AgentRunDAO {
        var called = false
        var processStartedAt: Long = -1
        var finishedAt: Long = -1
        var interruptedStatus: String = ""
        var reason: String = ""
        var unfinishedStatuses: List<String> = emptyList()

        override suspend fun insertRun(run: AgentRunEntity) = Unit
        override suspend fun updateRun(run: AgentRunEntity) = Unit
        override fun observeRun(id: String): Flow<AgentRunEntity?> = emptyFlow()
        override suspend fun getRun(id: String): AgentRunEntity? = null
        override fun observeRuns(): Flow<List<AgentRunEntity>> = emptyFlow()
        override suspend fun deleteRun(id: String) = Unit

        override suspend fun markInterruptedBefore(
            processStartedAt: Long,
            finishedAt: Long,
            interruptedStatus: String,
            reason: String,
            unfinishedStatuses: List<String>,
        ): Int {
            called = true
            this.processStartedAt = processStartedAt
            this.finishedAt = finishedAt
            this.interruptedStatus = interruptedStatus
            this.reason = reason
            this.unfinishedStatuses = unfinishedStatuses
            return 3
        }
    }
}
