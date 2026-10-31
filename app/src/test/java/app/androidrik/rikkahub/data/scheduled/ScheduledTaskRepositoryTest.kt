package app.androidrik.rikkahub.data.scheduled

import app.androidrik.rikkahub.data.db.dao.ScheduledTaskDAO
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓储层认领语义测试（docs/design/design-scheduled-ai-tasks.md §五）。
 *
 * 没有给 SQLite 用 Robolectric——[FakeScheduledTaskDAO] 在内存里**复刻** claim 的
 * `WHERE next_run_at = :seen` 语义，测的是仓储层的调用形状；SQL 本身由 Room 编译期校验
 * + 真机验证兜底。若日后引入 Robolectric，应把本文件的 FAKE 换成 in-memory Room。
 */
class ScheduledTaskRepositoryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(date: String): Long =
        LocalDateTime.parse(date).atZone(zone).toInstant().toEpochMilli()

    private class FakeScheduledTaskDAO : ScheduledTaskDAO {
        val rows = ConcurrentHashMap<String, ScheduledTaskEntity>()
        private val flow = MutableStateFlow(0L)

        private fun bump() { flow.value += 1 }

        override fun flowAll(): Flow<List<ScheduledTaskEntity>> =
            flow.map { rows.values.sortedBy { it.createdAt } }

        override suspend fun byId(id: String): ScheduledTaskEntity? = rows[id]

        override suspend fun all(): List<ScheduledTaskEntity> = rows.values.sortedBy { it.createdAt }

        override suspend fun due(now: Long): List<ScheduledTaskEntity> =
            rows.values.filter { it.enabled && it.nextRunAt <= now }

        override suspend fun countEnabled(): Int = rows.values.count { it.enabled }

        override suspend fun upsert(entity: ScheduledTaskEntity) {
            rows[entity.id] = entity
            bump()
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
            bump()
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            rows[id] = rows[id]!!.copy(enabled = enabled)
            bump()
        }

        override suspend fun markTargetGone(id: String) {
            rows[id] = rows[id]!!.copy(enabled = false, lastStatus = "TARGET_GONE")
            bump()
        }

        override suspend fun markAssistantGone(id: String) {
            rows[id] = rows[id]!!.copy(enabled = false, lastStatus = "ASSISTANT_GONE")
            bump()
        }

        override suspend fun recordLastConversation(id: String, conversationId: String) {
            rows[id] = rows[id]!!.copy(lastConversationId = conversationId)
            bump()
        }

        override suspend fun claim(id: String, seen: Long, advanced: Long, claimedAt: Long): Int {
            val current = rows[id] ?: return 0
            if (!current.enabled || current.nextRunAt != seen) return 0
            rows[id] = current.copy(nextRunAt = advanced, lastRunAt = claimedAt)
            bump()
            return 1
        }

        override suspend fun claimOnce(id: String, seen: Long, claimedAt: Long): Int {
            val current = rows[id] ?: return 0
            if (!current.enabled || current.nextRunAt != seen) return 0
            rows[id] = current.copy(enabled = false, lastRunAt = claimedAt)
            bump()
            return 1
        }

        override suspend fun recordStatus(id: String, status: String) {
            rows[id] = rows[id]!!.copy(lastStatus = status)
            bump()
        }

        // ---- 运行账本（Fake：内存 map，供 Repository 层测试的调用形状）----

        val runs = ConcurrentHashMap<String, ScheduledTaskRunEntity>()

        override suspend fun insertRun(run: ScheduledTaskRunEntity) {
            runs[run.id] = run
        }

        override suspend fun finishRun(id: String, finishedAt: Long, status: String, gateRetries: Int) {
            runs[id] = runs[id]!!.copy(finishedAt = finishedAt, status = status, gateRetries = gateRetries)
        }

        override suspend fun updateRunConversation(id: String, conversationId: String) {
            runs[id] = runs[id]!!.copy(conversationId = conversationId)
        }

        override fun flowRuns(taskId: String, limit: Int): Flow<List<ScheduledTaskRunEntity>> =
            MutableStateFlow(0L).map {
                runs.values.filter { it.taskId == taskId }
                    .sortedByDescending { it.startedAt }
                    .take(limit)
            }

        override suspend fun allRunsOfTask(taskId: String): List<ScheduledTaskRunEntity> =
            runs.values.filter { it.taskId == taskId }.sortedByDescending { it.startedAt }

        override suspend fun deleteRunsOfTask(taskId: String) {
            runs.values.removeAll { it.taskId == taskId }
        }

        override suspend fun pruneRuns(taskId: String, keep: Int) {
            runs.values.filter { it.taskId == taskId }
                .sortedByDescending { it.startedAt }
                .drop(keep)
                .forEach { runs.remove(it.id) }
        }
    }

    private fun repository(dao: FakeScheduledTaskDAO, now: Long) =
        ScheduledTaskRepository(dao, clock = { now })

    private var seedCounter = 0

    private suspend fun seed(
        dao: FakeScheduledTaskDAO,
        type: ScheduleType,
        nextRunAt: Long,
    ): ScheduledTaskEntity {
        val entity = ScheduledTaskEntity(
            id = "task-${type.name}-${seedCounter++}",
            conversationId = "00000000-0000-0000-0000-000000000001",
            title = "t",
            prompt = "p",
            scheduleType = type.name,
            schedulePayload = SchedulePayload(hour = 8, minute = 0, intervalMinutes = 60).encode(),
            nextRunAt = nextRunAt,
            missPolicy = MissPolicy.RUN_LATE.name,
            idleOnly = false,
            enabled = true,
            createdAt = 0,
        )
        dao.upsert(entity)
        return entity
    }

    @Test
    fun `claim advances nextRunAt and rejects the second concurrent claim`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val task = seed(dao, ScheduleType.DAILY, nextRunAt = millis("2026-10-30T08:00"))
        val repo = repository(dao, now = millis("2026-10-30T08:05"))

        assertTrue(repo.claim(task))
        val after = dao.rows[task.id]!!
        assertEquals(millis("2026-10-31T08:00"), after.nextRunAt)

        // 用「看到时」的旧 next_run_at 再认领一次：乐观锁拒绝
        assertFalse(repo.claim(task))
    }

    @Test
    fun `claim ONCE disables the task`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val task = seed(dao, ScheduleType.ONCE, nextRunAt = millis("2026-10-30T08:00"))
        val repo = repository(dao, now = millis("2026-10-30T08:05"))

        assertTrue(repo.claim(task))
        val after = dao.rows[task.id]!!
        assertFalse(after.enabled)
        assertEquals(millis("2026-10-30T08:00"), after.nextRunAt)
    }

    @Test
    fun `claim skips disabled rows`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val task = seed(dao, ScheduleType.DAILY, nextRunAt = millis("2026-10-30T08:00"))
        dao.setEnabled(task.id, false)
        val repo = repository(dao, now = millis("2026-10-30T08:05"))

        assertFalse(repo.claim(task))
    }

    @Test
    fun `dueTasks only returns enabled and due rows`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val due = seed(dao, ScheduleType.DAILY, nextRunAt = millis("2026-10-30T08:00"))
        val future = seed(dao, ScheduleType.DAILY, nextRunAt = millis("2026-11-30T08:00"))
        val disabled = seed(dao, ScheduleType.DAILY, nextRunAt = millis("2026-10-30T07:00"))
        dao.setEnabled(disabled.id, false)
        val repo = repository(dao, now = millis("2026-10-30T09:00"))

        assertEquals(listOf(due.id), repo.dueTasks().map { it.id })
        assertNotNull(dao.byId(future.id))
    }

    @Test
    fun `create computes a future first run and persists the payload`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val repo = repository(dao, now = millis("2026-10-30T09:00"))

        val created = repo.create(
            conversationId = "00000000-0000-0000-0000-000000000002",
            title = "digest",
            prompt = "summarize",
            scheduleType = ScheduleType.DAILY,
            payload = SchedulePayload(hour = 8, minute = 0),
        )

        assertEquals("2026-10-31T08:00", LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(created.nextRunAt), zone,
        ).toString())
        assertEquals(SchedulePayload(hour = 8, minute = 0), SchedulePayload.decode(created.schedulePayload))
        assertTrue(created.enabled)
    }

    @Test
    fun `update keeps nextRunAt when only text fields change`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val repo = repository(dao, now = millis("2026-10-30T09:00"))
        val created = repo.create(
            conversationId = "c",
            title = "t",
            prompt = "p",
            scheduleType = ScheduleType.DAILY,
            payload = SchedulePayload(hour = 8, minute = 0),
        )
        val before = dao.byId(created.id)!!

        repo.update(created.id, title = "renamed", prompt = "new prompt", scheduleType = ScheduleType.DAILY,
            payload = SchedulePayload(hour = 8, minute = 0), missPolicy = MissPolicy.RUN_LATE, idleOnly = false)

        val after = dao.byId(created.id)!!
        assertEquals(before.nextRunAt, after.nextRunAt)
        assertEquals("renamed", after.title)
        assertEquals("new prompt", after.prompt)
    }

    @Test
    fun `update recomputes nextRunAt when the schedule changes`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val repo = repository(dao, now = millis("2026-10-30T09:00"))
        val created = repo.create(
            conversationId = "c",
            title = "t",
            prompt = "p",
            scheduleType = ScheduleType.DAILY,
            payload = SchedulePayload(hour = 8, minute = 0),
        )
        val before = dao.byId(created.id)!!

        // 改成每周三 22:00：nextRunAt 必须重算（今天是周四 → 下个周三）
        repo.update(created.id, title = "t", prompt = "p", scheduleType = ScheduleType.WEEKLY,
            payload = SchedulePayload(hour = 22, minute = 0, dayOfWeek = 3),
            missPolicy = MissPolicy.SKIP, idleOnly = true)

        val after = dao.byId(created.id)!!
        assertTrue(after.nextRunAt != before.nextRunAt)
        assertEquals("2026-11-04T22:00", LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(after.nextRunAt), zone,
        ).toString())
        assertEquals(MissPolicy.SKIP.name, after.missPolicy)
        assertTrue(after.idleOnly)
    }


    // ---- 运行账本（P2）----

    @Test
    fun `startRun and finishRun write ledger with terminal status`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val repo = repository(dao, now = millis("2026-10-30T08:00"))
        val runId = repo.startRun("task-1", source = "alarm", conversationId = null)
        val started = dao.runs[runId]!!
        assertEquals(null, started.status)
        assertEquals(null, started.finishedAt)

        repo.finishRun(runId, status = "OK")
        val finished = dao.runs[runId]!!
        assertEquals("OK", finished.status)
        assertEquals(millis("2026-10-30T08:00"), finished.finishedAt)
    }

    @Test
    fun `delete removes task and its run history`() = runTest {
        val dao = FakeScheduledTaskDAO()
        val repo = repository(dao, now = millis("2026-10-30T08:00"))
        val created = repo.create(
            conversationId = "c", title = "t", prompt = "p",
            scheduleType = ScheduleType.DAILY, payload = SchedulePayload(hour = 8, minute = 0),
        )
        repo.startRun(created.id, source = "alarm", conversationId = "c")
        assertTrue(dao.runs.isNotEmpty())

        repo.delete(created.id)
        assertNull(dao.byId(created.id))
        assertTrue(dao.runs.values.none { it.taskId == created.id })
    }
}
