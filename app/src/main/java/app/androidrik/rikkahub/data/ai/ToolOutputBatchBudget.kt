package app.androidrik.rikkahub.data.ai

/**
 * 工具输出批次预算协调器（design-tool-output-budget 阶段 2：A1 两级限幅 + A2 执行前预留/结清 + A3 零额度合法降级）。
 *
 * 解决的问题：
 * 1. **A1 两级限幅 `min(单次批次份额, 全局上限)`**：单给全局上限，N 个并行工具合起来仍能撑爆窗口；
 *    只给份额，窗口很空时单个工具可能吃掉半个窗口。两者取小兼顾单条上限与整批不超窗；
 * 2. **A2 执行前预留、完成后结清**：并发车道里谁先返回是不确定的，事后分配会随完成顺序抖动；
 *    执行前登记预留保证份额确定性，执行完按实际 [WirePayloadMeasure.wireTokens] 结清并释放盈余，
 *    推进下一轮模型请求前通过 [assertAllSettled] 核验无未结清预留；
 * 3. **A3 零额度是合法降级态**：当剩余窗口为 0 时返回 `budgetChars = 0`（而非抛错），
 *    下游 [ToolOutputCompressor] 走 `MINIMAL_ENVELOPE` 返回最小可用信封（指针 + 提示）。
 *
 * 线程安全：所有状态变更经内部锁串行，支持并行工具车道并发结清。
 */
internal class ToolOutputBatchBudget(
    private val windowTokens: Int,
    initialUsedTokens: Int,
    batchSize: Int,
) {
    private val lock = Any()
    private val safeBatchSize = batchSize.coerceAtLeast(1)
    private var settledTokens = initialUsedTokens.coerceAtLeast(0)
    private var reservedPendingTokens = 0
    private var totalReservedSlots = 0
    private val activeReservations = linkedMapOf<String, ToolBudgetReservation>()

    data class ToolBudgetReservation(
        val toolCallId: String,
        /** 本次预留的 token 份额（0 = 零额度降级态 A3） */
        val reservedTokens: Int,
        /** 两级限幅后的最终字符预算（`min(批次份额字符, 全局档位上限)`；0 = 零额度最小信封） */
        val budgetChars: Int,
        /** 全局单次档位上限（诊断与 search_web 基础档豁免判定用） */
        val globalCapChars: Int,
    )

    /**
     * 在工具执行**之前**为其预留预算份额（A1 + A2）。
     * 同一 `toolCallId` 重复调用时幂等返回既有预留。
     */
    fun reserve(toolCallId: String, currentUsedTokens: Int = settledTokens): ToolBudgetReservation = synchronized(lock) {
        activeReservations[toolCallId]?.let { return it }

        val effectiveUsed = maxOf(settledTokens, currentUsedTokens.coerceAtLeast(0))
        val globalCapChars = toolOutputBudgetFor(effectiveUsed + reservedPendingTokens, windowTokens)
        val remainingSlots = (safeBatchSize - totalReservedSlots).coerceAtLeast(1)

        // 剩余窗口按未预留槽位均分；大窗口单工具时份额远大于 globalCapChars ⇒ 自然取 globalCapChars；
        // 小窗口或多并发批次时份额低于 globalCapChars ⇒ 两级限幅生效，防并发合力超窗。
        val remainingWindowTokens = (windowTokens - effectiveUsed - reservedPendingTokens).coerceAtLeast(0)
        val perCallShareTokens = remainingWindowTokens / remainingSlots
        val perCallShareChars = (perCallShareTokens.toLong() * 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val effectiveBudgetChars = if (remainingWindowTokens <= 0) {
            0
        } else {
            minOf(perCallShareChars, globalCapChars)
        }
        val reservedTokens = if (effectiveBudgetChars <= 0) {
            0
        } else {
            (effectiveBudgetChars / 4).coerceAtLeast(1)
        }

        val reservation = ToolBudgetReservation(
            toolCallId = toolCallId,
            reservedTokens = reservedTokens,
            budgetChars = effectiveBudgetChars,
            globalCapChars = globalCapChars,
        )
        activeReservations[toolCallId] = reservation
        reservedPendingTokens += reservedTokens
        totalReservedSlots++
        return reservation
    }

    /**
     * 工具执行完成（成功或失败，D2 共用通道）后按实际线载荷 token 结清预留，盈余自动回池。
     * 未预留或已结清的 id 幂等忽略。
     */
    fun settle(toolCallId: String, actualWireTokens: Int) = synchronized(lock) {
        val reservation = activeReservations.remove(toolCallId) ?: return
        reservedPendingTokens = (reservedPendingTokens - reservation.reservedTokens).coerceAtLeast(0)
        settledTokens += actualWireTokens.coerceAtLeast(0)
    }

    /** 取消或跳过执行时释放预留（不计入已消费 token）。 */
    fun release(toolCallId: String) = synchronized(lock) {
        val reservation = activeReservations.remove(toolCallId) ?: return
        reservedPendingTokens = (reservedPendingTokens - reservation.reservedTokens).coerceAtLeast(0)
    }

    /** 当前未结清预留数量（推进下一轮模型请求前应为 0）。 */
    fun unsettledCount(): Int = synchronized(lock) { activeReservations.size }

    /**
     * 结清守卫（A2）：推进下一步模型请求前核验无悬空预留。
     * 正常流程下恒为空；若因异常路径遗漏，清表并返回遗漏数供日志告警。
     */
    fun assertAllSettled(throwOnLeak: Boolean = false): Int = synchronized(lock) {
        val leaked = activeReservations.size
        if (leaked > 0) {
            val ids = activeReservations.keys.toList()
            activeReservations.clear()
            reservedPendingTokens = 0
            if (throwOnLeak) {
                error("Unsettled tool output budget reservations before next step: $ids")
            }
        }
        leaked
    }
}
