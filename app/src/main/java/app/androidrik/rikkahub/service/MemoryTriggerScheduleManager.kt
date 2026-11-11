package app.androidrik.rikkahub.service

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.MemoryTriggerConfig
import app.androidrik.rikkahub.data.ai.MemoryTriggerMode
import app.androidrik.rikkahub.data.ai.claim
import app.androidrik.rikkahub.data.ai.firstNextRun
import app.androidrik.rikkahub.data.ai.isWallClock
import app.androidrik.rikkahub.data.ai.resolveMemoryIds
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.scheduled.MissDecision
import app.androidrik.rikkahub.data.scheduled.ScheduleMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 记忆整理墙钟触发的**排程对账层**（design-memory-consolidation-trigger.md §3.4/§六）。
 *
 * 核心方法只有两个：
 * - [reconcile]（配置流驱动）：把「配置想要的闹钟状态」对账到系统——排首槽、错过重排、
 *   模式切走/ONCE 完成撤闹钟。**只排闹钟，不直接执行**：BOOT/后台态下直接
 *   startForegroundService 不在 BG-FGS 豁免名单里，而闹钟广播带豁免——错过的槽位
 *   一律排「现在」的闹钟，让 [MemoryTriggerAlarmReceiver] 去拉服务。
 * - [executeDueRun]（前台服务调用）：认领（[ScheduleMath.advance] 推进槽位并回写）→
 *   逐库 [MemoryConsolidationScheduler.runScheduled]。**先认领后执行**（与定时任务同款）：
   进程死在半路只是丢一轮，不会双跑；建议闸门命中时槽位照常推进（决议 #3）。
 *
 * 全部状态就两份：`nextRunAt/onceFired` 持久化在配置 JSON（进程死亡后凭它重排），
 * [armed] 是内存里的「系统当前排的闹钟」快照（防同一槽位反复重排）。
 */
class MemoryTriggerScheduleManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val scheduler: app.androidrik.rikkahub.data.ai.MemoryConsolidationScheduler,
    private val appScope: AppScope,
) {
    private val alarmScheduler = MemoryTriggerAlarmScheduler(context)

    /** 系统里当前排着的闹钟（mode + nextRunAt）；null = 没排 */
    private var armed: Pair<MemoryTriggerMode, Long>? = null

    /** app 启动时调用：配置流的常驻观察者，任何配置变化都会触发一次对账 */
    fun start() {
        appScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                runCatching { reconcile(settings.memoryTriggerConfig) }
                    .onFailure { Log.w(TAG, "reconcile failed", it) }
            }
        }
    }

    /** 无观察者上下文的一次性对账（BOOT receiver 用） */
    suspend fun reconcileCurrent() {
        val settings = settingsStore.settingsFlow.first()
        reconcile(settings.memoryTriggerConfig)
    }

    private suspend fun reconcile(config: MemoryTriggerConfig) {
        val now = System.currentTimeMillis()
        if (!config.mode.isWallClock || (config.mode == MemoryTriggerMode.ONCE && config.onceFired)) {
            if (armed != null) {
                alarmScheduler.cancel()
                armed = null
            }
            return
        }
        var next = config.nextRunAt
        if (next == null) {
            // 切进墙钟模式后还没有首槽：算一个写回，本轮 return（collect 会带新配置再进来对账）。
            // ONCE 的时刻缺失/已过去 ⇒ firstNextRun 返回 null，保持不排程（等用户重选）。
            val first = config.firstNextRun(now) ?: return
            updateConfig(config) { it.copy(nextRunAt = first) }
            return
        }
        val key = config.mode to next
        if (armed == key) return
        // 错过的槽位也走「排闹钟」这条路：alarmTriggerAt 会把它抬到 now+slop，
        // 闹钟广播的临时豁免允许后台拉起前台服务，执行侧再决定跑还是按错过策略跳过
        alarmScheduler.arm(next, now)
        armed = key
        Log.i(TAG, "armed: mode=${config.mode} next=$next (now=$now)")
    }

    /**
     * 到点执行（[MemoryTriggerRunService] 调用）：认领 → 逐库整理。
     * 重复唤醒（闹钟与重排闹钟同时到期）安全：认领用「读配置 → advance → 条件回写」，
     * 第二次进来时 `nextRunAt` 已被推走，due 判定不成立直接返回。
     */
    suspend fun executeDueRun() {
        val settings = settingsStore.settingsFlow.first()
        val config = settings.memoryTriggerConfig
        if (!config.mode.isWallClock) return
        val now = System.currentTimeMillis()
        val due = config.nextRunAt ?: return
        if (due > now + MemoryTriggerAlarmScheduler.SLOP_MS) {
            Log.i(TAG, "not due yet (next=$due, now=$now), skip")
            return
        }
        // 错过判定：宽限（6h）内照跑；超窗 RUN_LATE 补跑一次——整理积压没有时效性，
        // 晚了补跑仍有益，所以策略固定 RUN_LATE（SKIP 在本策略下不可达，留作防御）
        val decision = ScheduleMath.decideMiss(due, now, MissPolicy.RUN_LATE)
        claim(config)
        if (decision == MissDecision.SKIP) return

        val memoryIds = resolveMemoryIds(config, settings.assistants)
        if (memoryIds.isEmpty()) {
            Log.i(TAG, "no memory store in scope, skip run")
            return
        }
        memoryIds.forEach { memoryId ->
            runCatching { scheduler.runScheduled(memoryId) }
                .onFailure { Log.w(TAG, "runScheduled($memoryId) failed", it) }
        }
    }

    /** 认领：推进 `nextRunAt`（ONCE 认领即完成）。用户中途改了配置就放弃本次回写 */
    private suspend fun claim(config: MemoryTriggerConfig) {
        val claimed = config.claim(System.currentTimeMillis())
        if (claimed == config) return
        settingsStore.update { settings ->
            // 读改写之间用户可能改了配置（模式切换/重选时刻）：整对象比较，变了就放弃
            if (settings.memoryTriggerConfig != config) return@update settings
            settings.copy(memoryTriggerConfig = claimed)
        }
    }

    private suspend fun updateConfig(
        config: MemoryTriggerConfig,
        transform: (MemoryTriggerConfig) -> MemoryTriggerConfig,
    ) {
        settingsStore.update { settings ->
            if (settings.memoryTriggerConfig != config) return@update settings
            settings.copy(memoryTriggerConfig = transform(settings.memoryTriggerConfig))
        }
    }

    companion object {
        private const val TAG = "MemoryTriggerSchedule"
    }
}
