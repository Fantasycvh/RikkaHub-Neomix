package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole

/**
 * 压缩 / 截断切点的**共享安全判定**——`docs/design/drafts/design-context-boundaries-and-overflow-recovery.md` §2.1。
 *
 * 仿 OpenHands `manipulation_indices` 的「删除区间不得切开原子单元」，只取本仓必需子集：
 * 按 **toolCallId 精确配对**，不做四性质全量。消费方（同一份判定，各自保留候选计算与滞回）：
 * - [limitContext]：条数阶梯候选 + 本判定（发送侧同步安全网）；
 * - 压缩 planner（app 模块 `estimateCompressionPlan` / `midTurnCompressionPlan`）：token 驱动候选 + 本判定；
 * - 物理清理守卫六（app 模块）：删除集/保留集间的遗留配对检查（同一配对定义的集合版）。
 *
 * 三条判据（编号对应设计稿 §2.1）：
 * - **R1 原子配对**（协议完整性，硬约束）：切点不得把「调用 ↔ 结果」拆到两侧。结果侧引用 =
 *   已执行 [UIMessagePart.Tool]（自含 call+output，但同 id 的另一份调用残留在丢弃侧时仍是
 *   双写骑线——call/result 分处两条消息的形态见 `MessageTest` 既有契约）与旧版
 *   [UIMessagePart.ToolResult]；调用侧 = [UIMessagePart.Tool]（任意状态）与旧版
 *   [UIMessagePart.ToolCall]。[UIMessagePart.ServerTool] 自含（call/output 同 part 原地更新），
 *   不参与配对。骑线 ⇒ 回退到**最早**涉事调用所在消息（一次跳转同时修完多起骑线）。
 *   用工具名或「前面有一个未执行工具」推断配对会误关联——设计稿明令禁止，本判定只认 id。
 * - **R2 轮语义**（仅 `preferUserTurn`）：边界带**未收束调用**（未执行 Tool / 旧版 ToolCall）⇒
 *   窗口不得以半截工具调用开场，回退到其所属真实 USER 轮起点；无 USER 可退时维持现状
 *   （发送出口配平会给挂起调用合成结果，协议不破）。
 * - **R3 轮边界偏好**（仅 `preferUserTurn`，NMoi D143）：合法切点**优先**取真实 USER 轮起点——
 *   「压缩后窗口以助手消息开头」会让模型读到「自己回答了一个看不见的问题」。外部通知
 *   （注解载体）与导入检查点不算轮起点。
 *
 * R3 对齐会把候选下标之后的区段纳入保留区，可能**新引入**骑线——R1 的扫描范围是整个保留区
 * `[aligned, size)`（迟到结果/对齐新增区都能兜住），命中则整体回退后重新对齐，迭代到稳定。
 * 常规追加消息（调用紧随其结果、结果原地回填进同一 part）不会翻转既有判定结果，阶梯前缀
 * 稳定性保持；只有病态数据（结果远落后于调用）才会一次性回退切点。
 *
 * 合约：只向前（下标减小）调整——不破坏保留条数下界；同输入同输出（确定性）。
 * 返回 0 = 找不到合法切点（limitContext 视为不截断；压缩 planner 视为「无可压缩」）；
 * 返回 `candidate` 本身 = 候选已合法且无更优轮起点。
 */
fun List<UIMessage>.alignCutIndex(candidate: Int, preferUserTurn: Boolean = true): Int {
    if (candidate <= 0) return 0
    if (candidate >= size) return size
    var cut = candidate
    loop@ while (cut > 0) {
        // R3：先对齐到最近的「真实 USER 轮起点」；无 USER 可退时维持候选（协议完整性兜底）
        val aligned = if (preferUserTurn) nearestUserTurnStartAtOrBefore(cut) ?: cut else cut
        // R1：保留区内结果侧引用的调用落在丢弃侧 ⇒ 骑线，回退到调用所在消息重新对齐
        val straddled = firstStraddledCallJump(aligned)
        if (straddled != null) {
            cut = straddled
            continue@loop
        }
        // R2：对齐点自身带未收束调用 ⇒ 回退其 USER 轮起点；无 USER 可退时维持
        if (preferUserTurn && this[aligned].hasUnfinishedToolCalls()) {
            val userTurn = nearestUserTurnStartAtOrBefore(aligned - 1)
            if (userTurn != null) {
                cut = userTurn
                continue@loop
            }
        }
        return aligned
    }
    return 0
}

/**
 * 是否携带**未收束**的工具调用：未执行的现代 [UIMessagePart.Tool]（待审批 / 执行中 /
 * 结果未写回），或旧版 [UIMessagePart.ToolCall]（调用本体恒无内嵌结果）。
 *
 * 中途压缩与切点对齐用它识别「进行中的工具批」——这类批次必须整批留在原文侧
 * （设计稿 §2.1），进摘要会让下一步模型失去调用现场。
 */
@Suppress("DEPRECATION")
fun UIMessage.hasUnfinishedToolCalls(): Boolean = parts.any { part ->
    (part is UIMessagePart.Tool && !part.isExecuted) || part is UIMessagePart.ToolCall
}

/**
 * 真实 USER 轮起点：窗口应从它开场的消息。外部通知（USER 仅为载体，[isExternalNotice]）
 * 与导入检查点（[UIMessage.isContextCheckpoint]）不算——前者语义是系统，后者本身是摘要边界。
 */
private fun UIMessage.isRealUserTurnStart(): Boolean =
    role == MessageRole.USER && !isExternalNotice && !isContextCheckpoint

/** `[from, 0]` 区间内最近的 USER 轮起点；`from` 为负或区间内没有 ⇒ null */
private fun List<UIMessage>.nearestUserTurnStartAtOrBefore(from: Int): Int? {
    for (index in from downTo 0) {
        if (this[index].isRealUserTurnStart()) return index
    }
    return null
}

/**
 * 保留区 `[alignedStart, size)` 内是否有「结果侧引用的调用落在丢弃侧 `[0, alignedStart)`」的骑线；
 * 有 ⇒ 返回**最早**涉事调用所在消息下标（一次跳转同时修完多起骑线），无 ⇒ null。
 */
@Suppress("DEPRECATION")
private fun List<UIMessage>.firstStraddledCallJump(alignedStart: Int): Int? {
    if (alignedStart <= 0) return null
    // 丢弃侧调用索引表：id → 首次出现下标（升序扫描 putIfAbsent；跳到最早一份可同时保住重名调用）
    val droppedCallIndexById = HashMap<String, Int>()
    for (index in 0 until alignedStart) {
        for (part in this[index].parts) when (part) {
            is UIMessagePart.Tool -> droppedCallIndexById.putIfAbsent(part.toolCallId, index)
            is UIMessagePart.ToolCall -> droppedCallIndexById.putIfAbsent(part.toolCallId, index)
            else -> Unit
        }
    }
    if (droppedCallIndexById.isEmpty()) return null
    var jump: Int? = null
    for (index in alignedStart until size) {
        for (part in this[index].parts) {
            val resultId = when (part) {
                is UIMessagePart.Tool -> if (part.isExecuted) part.toolCallId else null
                is UIMessagePart.ToolResult -> part.toolCallId
                else -> null
            } ?: continue
            val callIndex = droppedCallIndexById[resultId]
            if (callIndex != null && (jump == null || callIndex < jump)) jump = callIndex
        }
    }
    return jump
}
