package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * 记忆整理的**触发方式**（`docs/design/design-memory-consolidation-trigger.md`，2026-11-11 决议）。
 *
 * **单选互斥**（决议 #2）：同一时刻只有一种触发策略生效，选中即独占——墙钟模式（ONCE 起）
 * 会停用轮末/切后台/启动补漏入口，用户配「每天 9 点」就是「只在 9 点花钱」的约定。
 * [IDLE] 是默认值且行为与历史版本完全一致。
 */
@Serializable
enum class MemoryTriggerMode {
    /** 轮末空闲（历史行为）：结束后等 [MemoryTriggerConfig.idleDelayMinutes]，攒够轮数不再等 */
    IDLE,

    /** 每 N 轮：单会话待整理轮数达到阈值立即整理，不挂空闲计时 */
    EVERY_N_TURNS,

    /** 第 N 轮：会话累计用户轮数首次达到阈值时整理一次，命中后失效（决议 #1） */
    TURN_N,

    // ---- 墙钟四模式（P1）：复用定时任务的 SchedulePayload/ScheduleMath ----
    ONCE,
    INTERVAL,
    DAILY,
    WEEKLY,
}

/** [MemoryTriggerMode] 是否属于墙钟模式（触发权来自闹钟而非聊天事件）。 */
val MemoryTriggerMode.isWallClock: Boolean
    get() = this == MemoryTriggerMode.ONCE ||
        this == MemoryTriggerMode.INTERVAL ||
        this == MemoryTriggerMode.DAILY ||
        this == MemoryTriggerMode.WEEKLY

/**
 * 触发配置（全局一份，存 Settings 的 DataStore JSON；`JsonInstant` ignoreUnknownKeys + 默认值
 * ⇒ 加字段零迁移）。每助手的 `Assistant.memoryConsolidationSince`（开关 + 基线）与本配置**正交**：
 * 那边管「要不要/从哪算」，这边管「什么时候」。
 */
@Serializable
data class MemoryTriggerConfig(
    val mode: MemoryTriggerMode = MemoryTriggerMode.IDLE,
    /** IDLE 模式的轮末空闲等待（分钟）；历史硬编码值 2 */
    val idleDelayMinutes: Int = 2,
    /** IDLE 的攒轮数捷径 / EVERY_N_TURNS 的触发阈值（单会话待整理轮数）；历史硬编码值 6 */
    val everyNTurns: Int = 6,
    /** TURN_N 的会话累计用户轮数阈值 */
    val triggerTurn: Int = 8,
    /** 墙钟模式的周期参数（P1）；复用定时任务的载荷类型，DST/补跑逻辑同源 */
    val schedule: SchedulePayload = SchedulePayload(),
    /** 墙钟模式已排程的槽位（advance 的输入）；重启后凭它 rearm。null = 未排程 */
    val nextRunAt: Long? = null,
    /** ONCE 已执行（认领后置 true；重新配置 ONCE 时复位） */
    val onceFired: Boolean = false,
    /**
     * 范围（决议 #5）：**空集 = 全部助手**；非空 = 仅这些助手名下的会话参与触发。
     * 按助手 id 选、按 memoryId（`__global__` 归并）去重由 [resolveMemoryIds] 负责。
     */
    val scopeAssistantIds: Set<Uuid> = emptySet(),
) {
    /** IDLE 模式的空闲等待（已钳制） */
    val idleDelay: Duration
        get() = idleDelayMinutes.coerceIn(MIN_IDLE_DELAY_MINUTES, MAX_IDLE_DELAY_MINUTES).minutes

    /** 攒轮数阈值（已钳制；两个模式共用） */
    val effectiveEveryNTurns: Int get() = everyNTurns.coerceIn(1, 100)

    /** 第 N 轮阈值（已钳制） */
    val effectiveTriggerTurn: Int get() = triggerTurn.coerceIn(1, 1000)

    companion object {
        const val MIN_IDLE_DELAY_MINUTES = 1
        const val MAX_IDLE_DELAY_MINUTES = 60
    }
}

/**
 * 助手是否在该触发配置的范围内（**会话级门控**，仓库层逐会话判；默认空集 = 全部）。
 *
 * 范围统一作用于**所有**模式（含 IDLE）：默认空集时行为与历史版本一致，而一旦用户勾了
 * 范围，「只有这些助手触发」在哪种模式下都该成立——按模式区别对待会变成查三处才知道答案。
 */
internal fun Assistant.inTriggerScope(config: MemoryTriggerConfig): Boolean =
    config.scopeAssistantIds.isEmpty() || id in config.scopeAssistantIds

/**
 * 解析触发配置实际作用的**记忆库**集合（UI 摘要与诊断用；纯函数）。
 *
 * 三道过滤：做梦开着（`memoryConsolidationEnabled`）→ 在范围内（[inTriggerScope]）→
 * 按 memoryId 去重（共享 `__global__` 库的多个助手只算一个库，决议 #5）。
 */
internal fun resolveMemoryIds(config: MemoryTriggerConfig, assistants: List<Assistant>): Set<String> =
    assistants.asSequence()
        .filter { it.memoryConsolidationEnabled() }
        .filter { it.inTriggerScope(config) }
        .map { it.memoryId() }
        .toSet()
