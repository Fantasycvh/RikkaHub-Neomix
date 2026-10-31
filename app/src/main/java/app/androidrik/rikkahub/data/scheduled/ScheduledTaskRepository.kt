package app.androidrik.rikkahub.data.scheduled

import app.androidrik.rikkahub.data.db.dao.ScheduledTaskDAO
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * 定时任务表的读写口（docs/design/design-scheduled-ai-tasks.md）。
 *
 * 职责刻意收窄：只管行数据与认领原子性。WorkManager 的排/撤（sweeper 队列）与执行注入
 * 分别在 [ScheduledTaskSweeperKeeper] 与执行层——增删改的调用方（UI/启动）负责在改完
 * `enabled` 后调一次 keeper 的对账。
 */
class ScheduledTaskRepository(
    private val dao: ScheduledTaskDAO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun flowAll(): Flow<List<ScheduledTaskEntity>> = dao.flowAll()

    suspend fun allTasks(): List<ScheduledTaskEntity> = dao.all()

    suspend fun byId(id: String): ScheduledTaskEntity? = dao.byId(id)

    suspend fun countEnabled(): Int = dao.countEnabled()

    suspend fun dueTasks(now: Long = clock()): List<ScheduledTaskEntity> = dao.due(now)

    suspend fun create(
        conversationId: String,
        title: String,
        prompt: String,
        scheduleType: ScheduleType,
        payload: SchedulePayload,
        missPolicy: MissPolicy = MissPolicy.RUN_LATE,
        idleOnly: Boolean = false,
        withGoal: Boolean = false,
        targetMode: TargetMode = TargetMode.EXISTING,
        assistantId: String? = null,
    ): ScheduledTaskEntity {
        val now = clock()
        val entity = ScheduledTaskEntity(
            id = Uuid.random().toString(),
            conversationId = conversationId,
            targetMode = targetMode.name,
            assistantId = assistantId,
            lastConversationId = null,
            title = title,
            prompt = prompt,
            scheduleType = scheduleType.name,
            schedulePayload = payload.encode(),
            nextRunAt = ScheduleMath.firstRunAt(scheduleType, payload, now),
            lastRunAt = null,
            lastStatus = null,
            missPolicy = missPolicy.name,
            idleOnly = idleOnly,
            withGoal = withGoal,
            enabled = true,
            createdAt = now,
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: String) {
        dao.deleteById(id)
        dao.deleteRunsOfTask(id) // 账本无外键，随任务显式清理
    }

    /**
     * 编辑任务（详情页保存）。目标会话不可改（执行锚点）；标题/提示词/周期/策略/idleOnly/withGoal 可改。
     *
     * 周期字段（type/payload）变了才重算 `nextRunAt`——只改标题或提示词不该把用户的既定
     * 槽位推走；周期变了从「现在」取第一个未来槽位（旧槽位对应的是旧周期，无意义）。
     */
    suspend fun update(
        id: String,
        title: String,
        prompt: String,
        scheduleType: ScheduleType,
        payload: SchedulePayload,
        missPolicy: MissPolicy,
        idleOnly: Boolean,
        withGoal: Boolean = false,
    ): Boolean {
        val existing = dao.byId(id) ?: return false
        val newPayload = payload.encode()
        val scheduleChanged = existing.scheduleType != scheduleType.name || existing.schedulePayload != newPayload
        dao.upsert(
            existing.copy(
                title = title.trim(),
                prompt = prompt.trim(),
                scheduleType = scheduleType.name,
                schedulePayload = newPayload,
                nextRunAt = if (scheduleChanged) {
                    ScheduleMath.firstRunAt(scheduleType, payload, clock())
                } else {
                    existing.nextRunAt
                },
                missPolicy = missPolicy.name,
                idleOnly = idleOnly,
                withGoal = withGoal,
            )
        )
        return true
    }

    suspend fun markTargetGone(id: String) = dao.markTargetGone(id)

    suspend fun markAssistantGone(id: String) = dao.markAssistantGone(id)

    /** NEW_EACH_RUN：记录本轮新建出来的会话 id（供列表/详情跳转） */
    suspend fun recordLastConversation(id: String, conversationId: String) =
        dao.recordLastConversation(id, conversationId)

    suspend fun recordStatus(id: String, status: String) = dao.recordStatus(id, status)

    // ---- 运行账本（P2）：执行层经这里写，UI 经这里读 ----

    /** 一轮执行开始（含 SKIP 静默跳过）。返回账本行 id，执行结束时传给 [finishRun]。 */
    suspend fun startRun(
        taskId: String,
        source: String,
        conversationId: String?,
        startedAt: Long = clock(),
    ): String {
        val run = ScheduledTaskRunEntity(
            id = Uuid.random().toString(),
            taskId = taskId,
            source = source,
            conversationId = conversationId,
            startedAt = startedAt,
        )
        dao.insertRun(run)
        dao.pruneRuns(taskId, RUNS_KEEP_PER_TASK)
        return run.id
    }

    /** 一轮执行落终态（status 见 [TaskRunStatus]；进程死亡时这步不会发生，finished_at 留空） */
    suspend fun finishRun(runId: String, status: String, finishedAt: Long = clock()) =
        dao.finishRun(runId, finishedAt, status)

    /** NEW_EACH_RUN：会话是执行中途才建出来的，账本行补记本轮实际会话 */
    suspend fun updateRunConversation(runId: String, conversationId: String) =
        dao.updateRunConversation(runId, conversationId)

    /** 详情页运行历史（新→旧，最近 [limit] 条） */
    fun flowRuns(taskId: String, limit: Int = RUNS_KEEP_PER_TASK): Flow<List<ScheduledTaskRunEntity>> =
        dao.flowRuns(taskId, limit)

    /** 任务删除时清账本（表间无外键，靠调用方显式清） */
    suspend fun deleteRunsOfTask(taskId: String) = dao.deleteRunsOfTask(taskId)

    /**
     * 认领（乐观锁）：`seen` 必须是本次 sweep 读到的 `next_run_at`。
     * 返回是否抢到；抢到即已推进（ONCE 已禁用），调用方只管执行。
     */
    suspend fun claim(task: ScheduledTaskEntity, now: Long = clock()): Boolean {
        val claimed = if (scheduleTypeOf(task) == ScheduleType.ONCE) {
            dao.claimOnce(task.id, task.nextRunAt, now)
        } else {
            val advanced = ScheduleMath.advance(
                scheduleTypeOf(task), SchedulePayload.decode(task.schedulePayload), task.nextRunAt, now,
            ) ?: return false
            dao.claim(task.id, task.nextRunAt, advanced, now)
        }
        return claimed == 1
    }

    private fun scheduleTypeOf(task: ScheduledTaskEntity): ScheduleType =
        runCatching { ScheduleType.valueOf(task.scheduleType) }.getOrDefault(ScheduleType.ONCE)

    companion object {
        /** 每任务保留的账本行数上限（一天一次的任务 ≈ 两个月的完整历史） */
        const val RUNS_KEEP_PER_TASK = 60
    }
}
