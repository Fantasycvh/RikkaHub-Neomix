package app.androidrik.rikkahub.data.ai

/**
 * LoopGuard 灵敏度三档（peer ⑤ 可配置化）：映射到 [ToolCallLoopGuard] 的三参。
 *
 * **刻意只做档位、不做自由数字**：调松 = 防失控护栏失效，自由输入会让「防失控」
 * 名存实亡；三档把可表达空间收窄在安全范围内（宽松档也只是「多容忍几轮重试」，
 * 窗口与优雅停止语义不变）。
 */
enum class LoopGuardSensitivity {
    /** 严格：成功 2 / 失败 3——最快止损，适合省 token 优先 */
    STRICT,

    /** 标准：成功 3 / 失败 4（默认，与原写死行为一致） */
    STANDARD,

    /** 宽松：成功 6 / 失败 8——给长重试链留空间（如不稳定的远端工具） */
    LOOSE,
}

/** 档位 → (窗口, 成功阈值, 失败阈值)；窗口三档一致（覆盖交替循环形态） */
fun LoopGuardSensitivity.guardThresholds(): Triple<Int, Int, Int> = when (this) {
    LoopGuardSensitivity.STRICT -> Triple(12, 2, 3)
    LoopGuardSensitivity.STANDARD -> Triple(
        ToolCallLoopGuard.DEFAULT_WINDOW_SIZE,
        ToolCallLoopGuard.DEFAULT_SUCCESS_THRESHOLD,
        ToolCallLoopGuard.DEFAULT_FAILURE_THRESHOLD,
    )
    LoopGuardSensitivity.LOOSE -> Triple(12, 6, 8)
}

/** 宽松解析设置里存的档位名（未知值回退标准档） */
fun parseLoopGuardSensitivity(name: String?): LoopGuardSensitivity =
    name?.let { runCatching { LoopGuardSensitivity.valueOf(it) }.getOrNull() }
        ?: LoopGuardSensitivity.STANDARD

/**
 * 工具调用失控护栏（LoopGuard）。
 *
 * **要解决的问题**：模型有时会卡在「同一工具 + 同一参数」反复调用——每次都不产生新信息，
 * 却持续消耗 token，直到 `maxSteps`（当前 256）才停。用户在界面上的观感是「转了很久、
 * 一直在调同一个东西、最后什么也没说」。本护栏在**同一签名于滑动窗口内重复达阈值**时给出
 * 停止判定，让这一轮提前、可见地结束，而不是闷跑到步数上限。
 *
 * 出处：`docs/peer-apps-benchmark.md` 附录 + `docs/handoff-2026-09-04.md` 待办
 * 「LoopGuard + forget 补丁」。三条约束照搬自同源实现，改之前先读那份文档：
 *
 * 1. **用滑动窗口，不用「连续相同」**：模型常在一次响应里下发多条并行调用
 *    （Gemini 系实测会下发内容相同、toolCallId 不同的两条），窗口才能把它们与跨步重复
 *    一起算。签名直接复用本仓既有的 [toolCallSignature]——它已把「键序/空白不同的等价 JSON」
 *    归一为同一指纹（`JsonObject.toString()` 保留插入顺序，不归一就会漏判）。
 * 2. **失败调用要单独计**（见下）。
 * 3. **轮询类工具豁免**：以「观察时间/状态」为本质的工具，其结果本就每次不同，
 *    同参数重复调用是正常轮询而非失控（见 [POLLING_EXEMPT_TOOLS]）。
 *
 * 判定是**优雅停止**而非报错终止：当轮的工具结果照常回填（协议要求每个 toolCallId 都有结果），
 * 然后结束这一轮并由上层给用户一条可见提示。用户再发一条消息即开始新一轮、护栏重置——
 * 因此即便误判，代价也只是「再点一次」，不会丢已生成内容。
 *
 * ## 关于「forget() 补丁」的**有意偏离**（真机实测后修正，勿改回去）
 *
 * 同源实现的补丁语义是：失败调用（超时/异常/网络中断）**从指纹窗口移除**，理由是
 * 「失败的调用没有可信结果，网络恢复后相同参数重试是合理恢复路径」。
 *
 * 按字面实现后被真机实测证伪：一次诱导测试里模型反复读一个**不存在的文件**，
 * 每次都以异常失败 ⇒ 每次记录都被立刻移除 ⇒ 窗口恒为 1、**永远不触发**，
 * 模型可以把这个必然失败的调用重试到 256 步。日志证据：
 * `record tool=workspace_read_file window=1 repeats=1` 连续出现。
 *
 * 根因是「**确定性失败**（文件不存在）与**瞬态失败**（网络抖动）在工具层无法区分」。
 * 故这里保留补丁的**意图**（失败不能算作「成功调用的重复」），但改变实现：
 * 失败调用**不删除、而是标记**，并用更高的阈值单独计数（[failureThreshold]）。
 * 于是：网络抖动的少数几次重试照旧放行，而「反复撞同一个必然失败」也会被拦下。
 * 对应的 API 是 [markFailed]。
 *
 * ### 精确语义（按「同类重复次数」计，`markFailed` 只影响已发生的历史）
 *
 * | 情形 | 停下的时刻 |
 * |---|---|
 * | 相同调用连续成功 | 第 **3** 次请求时（[successThreshold]；窗口含本次） |
 * | 相同调用反复失败 | 允许 **4** 次失败重试，第 **5** 次相同请求时停（[failureThreshold]） |
 *
 * 也就是「失败不算成功重复」⇒ 失败后重试会重新获得成功路径的额度，但失败自身也有上限。
 *
 * 本类**无副作用、无 Android 依赖**，可直接上 JVM 单测。
 *
 * **状态挂在会话上，不是挂在单次 `generateText` 调用上**（真机验证后的修正）：
 * 一次「轮」可以跨多次 `generateText`——工具审批会让循环 break 并等用户；用户批准后是
 * **新的** `generateText` 调用续跑同一轮（真机实测每次都重新 `start step #0`）。
 * 若护栏建在单次调用内，每次批准就重置、永远累积不到阈值。故由
 * [app.androidrik.rikkahub.service.ConversationSession] 持有、在**新一轮用户消息**
 * （或重新生成）时重置，跨审批续跑保持累积。
 */
class ToolCallLoopGuard(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val successThreshold: Int = DEFAULT_SUCCESS_THRESHOLD,
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {

    /** 窗口中的一次调用：签名 + 是否已失败。失败是**标记**而非移除（见类注释的偏离说明）。 */
    private class Attempt(val signature: String) {
        var failed: Boolean = false
    }

    /** 已记录的调用（滑动窗口，先进先出）。 */
    private val window = ArrayDeque<Attempt>()

    /**
     * 记录一次**即将发起**的工具调用并给出判定。
     *
     * 调用点必须在执行之前；执行失败时用 [markFailed] 标记本次（同源实现的 `forget()` 补丁）。
     *
     * @param toolName 工具名（用于豁免判定与提示文案）
     * @param signature [toolCallSignature] 的产物
     */
    fun record(toolName: String, signature: String): Verdict {
        // 轮询类工具不参与计数：进了窗口反而会挤掉真正的循环证据
        if (toolName in POLLING_EXEMPT_TOOLS) return Verdict.Continue

        val attempt = Attempt(signature)
        window.addLast(attempt)
        while (window.size > windowSize) window.removeFirst()

        val succeeded = window.count { it.signature == signature && !it.failed }
        val failed = window.count { it.signature == signature && it.failed }

        // 常驻诊断：护栏是「静默生效」的机制，出问题时（误杀/该杀没杀）没有这行日志无从判断。
        // 每次工具调用一条、走 logcat（不进内存环形缓冲），开销可忽略。
        // 本行的价值已在真机事故中兑现：正靠它看出「window 恒为 1」才定位到 forget 语义漏洞。
        android.util.Log.i(
            TAG,
            "record tool=$toolName instance=${hashCode()} window=${window.size} " +
                "succeeded=$succeeded/$successThreshold failed=$failed/$failureThreshold",
        )

        return when {
            succeeded >= successThreshold -> Verdict.Loop(toolName, succeeded, failed = false)
            // 失败阈值更高：给「网络抖动后重试」留出余量，但反复撞同一个必然失败也要拦
            failed >= failureThreshold -> Verdict.Loop(toolName, failed, failed = true)
            else -> Verdict.Continue
        }
    }

    /**
     * 标记最近一次该签名的调用为**失败**（同源实现的 `forget()` 补丁，语义见类注释的偏离说明）。
     *
     * 只标记**最近一次**：更早的成功调用仍是有效历史，一并抹掉会让护栏对
     * 「成功—失败—成功—失败…」这类真实循环失明。
     */
    fun markFailed(signature: String) {
        val index = window.indexOfLast { it.signature == signature && !it.failed }
        if (index >= 0) window[index].failed = true
    }

    /** 清空窗口（新一轮开始时调用）。 */
    fun reset() {
        // 常驻诊断：与 record 配套——「新一轮从什么状态开始」是判断误杀的关键前提
        if (window.isNotEmpty()) {
            android.util.Log.i(TAG, "reset instance=${hashCode()} (discarded ${window.size} entries)")
        }
        window.clear()
    }

    /** 护栏判定结果。 */
    sealed interface Verdict {
        /** 未命中，继续循环。 */
        data object Continue : Verdict

        /**
         * 判定为失控循环。
         *
         * @param toolName 被反复调用的工具名（供提示文案）
         * @param repeats 窗口内该类调用的出现次数（≥ 对应阈值）
         * @param failed 命中的是「反复失败」路径（true）还是「反复成功」路径（false）
         */
        data class Loop(
            val toolName: String,
            val repeats: Int,
            val failed: Boolean,
        ) : Verdict
    }

    companion object {
        private const val TAG = "ToolCallLoopGuard"

        /** 滑动窗口长度。12 足以覆盖「两个工具交替调用」的循环形态，又不会被久远历史误伤。 */
        const val DEFAULT_WINDOW_SIZE = 12

        /**
         * 成功路径阈值：同一签名在窗口内出现 3 次即停。
         * 取 3 是因为两次可能是模型的正常重试（例如先读失败再读一次）。
         */
        const val DEFAULT_SUCCESS_THRESHOLD = 3

        /**
         * 失败路径阈值：比成功多给一次机会（4 次）——网络抖动后重试同一调用是合理恢复路径，
         * 但第 4 次仍以相同方式失败，基本可断定卡在一个必然失败的调用上。
         */
        const val DEFAULT_FAILURE_THRESHOLD = 4

        /**
         * 轮询类工具（同参数重复调用是正常轮询，不参与计数）。
         *
         * - `get_time_info`：结果**按定义**每次都在变，模型连问几次时间属正常行为。
         * - `workspace_task_status`：长任务（构建/下载）的进度查询，同一 `taskId` 反复查是**唯一**
         *   的正确用法；不豁免的话，第 3 次轮询就会被判成失控循环，把「后台任务」这个功能直接废掉。
         *
         * 刻意**不**把 `workspace_shell` / `workspace_run_background` 算进来：同一条命令反复执行
         * 且没有新输出，正是要拦的失控形态；确实需要轮询构建/下载进度时，模型会带上会变的参数
         * （如 `tail` 不同行数）或用不同命令，不会触发同一签名。
         */
        val POLLING_EXEMPT_TOOLS = setOf(
            "get_time_info",
            "workspace_task_status",
            // peer⑤③ 缩水版元工具：只读地图查询，同参重复调用是合法的「先查再调」节奏
            "get_tool_map",
        )
    }
}
