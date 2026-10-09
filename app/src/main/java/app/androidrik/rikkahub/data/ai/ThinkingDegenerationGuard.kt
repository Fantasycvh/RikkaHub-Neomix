package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/**
 * 思考退化护栏（**护栏家族第四员**）——堵「**零工具调用的退化**」这个家族盲区。
 *
 * 家族前三员的前提都落空时无人兜底：`ToolCallLoopGuard` 要求有工具调用、
 * `WorkspaceTurnBudget` 只计 `workspace_*`、`DenialGuard` 只计策略拒绝；而 `maxSteps`
 * 是**步数**，一次永不结束的调用根本走不到下一步（`readTimeout` 也会被持续到达的
 * delta 不断重置 ⇒ 永不触发）。真机/社区实测的三种形态（DSH discussion #5976）：
 *
 * 1. **reasoning-only**：一次调用只有推理、无正文无工具，连续多次；
 * 2. **restated-material**：连续调用在复述上一步的推理（换了措辞、实质相同），
 *    并且**伴随少量正文** ⇒ 看起来像在推进；
 * 3. **self-repeating**：单次调用内部逐字重复（「好。执行。好。」式短句循环）——
 *    这一种**不会自己结束**，一次可以烧到几十万字符/几百秒。
 *
 * 1、2 是**调用边界**判定（[onCallFinished]）：命中 ⇒ 调用方优雅停止本轮
 * （走正常结束路径、已生成内容照常落库，与家族另外三员同款）。
 * 3 必须**流中途断流**（[onDelta]）：否则拿不到调用边界。
 *
 * **判据选择（照社区插件的标定，2026-10-09）**：重复类判据一律用**逐字精确**判据
 * （相同 delta 计数 / 尾部逐字周期），**不用低熵比值**——实测同一批文本里，覆盖率类指标
 * 对退化样本读数 0.92–0.99，而对合法内容（markdown 表格 0.72、日志 0.76、CSS 0.82、
 * JSON dump 0.84）**只差 0.06**，不足以作为截断用户输出的边界；逐字周期在这些合法样本上
 * 恒为 0。周期还要求**含 ≥2 个不同字符**，否则长 `====` / `────` 分隔线会被误杀。
 *
 * **刻意不做**：不做模型 fallback、不自动重试、不自动续写——「静默地给一个已经退化的模型
 * 重新计费」比循环本身更糟（社区插件的同款取舍）。停止后由可见提示解释原因。
 * 也**先不做用户档位**（工具护栏有 `LoopGuardSensitivity` 三档）：默认值取保守量级，
 * 真出现误杀再加档位——加档位要连带 UI/持久化/6 语言，先让判据本身在真机站住。
 *
 * **已知取舍**：若某中转把**累积全文**当 delta 下发（协议违规），周期判据会在约 256 字符后断流；
 * 那种中转本就会让本仓 UI 显示重复文本，断流比继续烧更可取。
 *
 * 纯类、无 Android 依赖 ⇒ 可 JVM 单测（家族同款）。
 */
class ThinkingDegenerationGuard(
    /** 连续多少次「纯思考/复述上一步」的调用后停止本轮 */
    private val maxStalledCalls: Int = DEFAULT_MAX_STALLED_CALLS,
    /** 一次调用的推理少于这个长度时既不计数也不当作进展（避免一句「ok，继续」清空计数） */
    private val minReasoningChars: Int = DEFAULT_MIN_REASONING_CHARS,
    /** 跨调用复述判定：上一步推理的 shingle 集合被本步覆盖的比例阈值 */
    private val similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    /** 同一段文本连续出现多少次（逐字相同，trim 后比较）判定为退化 */
    private val maxIdenticalDeltas: Int = DEFAULT_MAX_IDENTICAL_DELTAS,
    /** 尾部逐字周期的观察窗口（同时也是要求的最小重复跨度） */
    private val cycleSpanChars: Int = DEFAULT_CYCLE_SPAN_CHARS,
    /** 周期的最大长度：超过它就不算「短句循环」（12/26 字符是实测形态的量级） */
    private val maxCyclePeriodChars: Int = DEFAULT_MAX_CYCLE_PERIOD_CHARS,
) {
    /** 调用内（流中途）状态：每次调用开始/结束时清零 */
    private var identicalRun = 0
    private var lastDeltaTrimmed: String? = null
    private val tail = StringBuilder()
    private var charsSinceCycleCheck = 0
    private var lastCut: Verdict.Repeating? = null

    /** 调用边界（跨调用）状态：随**轮**累积，[reset] 清零 */
    private var stalledCalls = 0
    private var lastReasoning: String? = null

    /** 本轮已计到的连续「纯思考/复述」调用数（提示与测试用） */
    val stalledCallCount: Int get() = stalledCalls

    /**
     * 取出并清除「本次调用内已断流」的记录（调用方在流结束后读，用于生成停止提示）。
     *
     * 与 [onCallFinished] 的调用顺序要紧：先 [takeCut] 再 [onCallFinished]——后者会清调用内状态。
     */
    fun takeCut(): Verdict.Repeating? {
        val cut = lastCut
        lastCut = null
        return cut
    }

    /**
     * 一次模型调用开始：清掉**调用内部**状态（跨调用的计数保留）。
     *
     * 调用方在每次发起 provider 流之前调用；也能在同一处不调用——[onCallFinished] 同样会重置。
     */
    fun onCallStarted() {
        resetIntraCall()
    }

    /**
     * 流中途喂一个 delta（`UIMessageChoice.delta`）。返回 [Verdict.Repeating] 时，
     * 调用方应**立刻中止本次流**（保留已生成内容），并按护栏停止本轮。
     *
     * 只看文本与推理增量：工具增量视为「有进展」，会清空调用内计数。
     */
    fun onDelta(delta: UIMessage?): Verdict {
        if (lastCut != null) return Verdict.Continue
        val parts = delta?.parts.orEmpty()

        if (parts.any { it is UIMessagePart.Tool }) {
            // 有工具调用=在干活：清掉调用内的重复证据
            identicalRun = 0
            lastDeltaTrimmed = null
            tail.clear()
            charsSinceCycleCheck = 0
            return Verdict.Continue
        }

        val text = parts.joinToString("") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Reasoning -> part.reasoning
                else -> ""
            }
        }.trim()
        if (text.isEmpty()) return Verdict.Continue

        identicalRun = if (text == lastDeltaTrimmed) identicalRun + 1 else 1
        lastDeltaTrimmed = text
        if (identicalRun >= maxIdenticalDeltas) {
            return Verdict.Repeating(Rule.IDENTICAL_DELTAS, identicalRun).also { lastCut = it }
        }

        tail.append(text.takeLast(cycleSpanChars))
        if (tail.length > cycleSpanChars) tail.delete(0, tail.length - cycleSpanChars)
        charsSinceCycleCheck += text.length
        if (tail.length >= cycleSpanChars && charsSinceCycleCheck >= CYCLE_CHECK_STRIDE) {
            charsSinceCycleCheck = 0
            val window = tail.toString()
            val period = minimalPeriod(window)
            if (period in 2..maxCyclePeriodChars && distinctChars(window, period) >= 2) {
                return Verdict.Repeating(Rule.REPEATING_CYCLE, period).also { lastCut = it }
            }
        }
        return Verdict.Continue
    }

    /**
     * 一次模型调用结束（拿到该步的完整助手消息后调用）。
     *
     * @param reasoning 该步助手消息里的推理文本（无推理传 null/空）
     * @param hasVisibleText 该步是否产出**可见正文/图片**
     * @param hasToolCalls 该步是否发起**工具调用**——工具调用算实打实的进展，直接清零计数：
     *   「复述推理 + 又调了个工具」在聊天场景是正常的多步推进，不该被当空转
     *   （工具类失控另有 [ToolCallLoopGuard] / [WorkspaceTurnBudget] 负责）
     */
    fun onCallFinished(
        reasoning: String?,
        hasVisibleText: Boolean,
        hasToolCalls: Boolean,
    ): Verdict {
        resetIntraCall()
        val current = reasoning?.takeIf { it.isNotBlank() }
        if (hasToolCalls) {
            stalledCalls = 0
            if (current != null) lastReasoning = current
            return Verdict.Continue
        }
        val longEnough = current != null && current.length >= minReasoningChars

        // 复述上一步：只在「本步有可见产出」时才有意义——没有产出的长推理已经按形态 1 计数
        val restated = longEnough && hasVisibleText &&
            lastReasoning?.let { containment(current!!, it) >= similarityThreshold } == true

        val stalled = if (hasVisibleText) restated else longEnough
        stalledCalls = if (stalled) stalledCalls + 1 else 0
        if (current != null) lastReasoning = current

        return if (stalled && stalledCalls >= maxStalledCalls) {
            Verdict.Stalled(stalledCalls, restated)
        } else {
            Verdict.Continue
        }
    }

    /** 新一轮（新的用户消息 / 重新生成）开始时清零全部状态。 */
    fun reset() {
        stalledCalls = 0
        lastReasoning = null
        resetIntraCall()
    }

    private fun resetIntraCall() {
        identicalRun = 0
        lastDeltaTrimmed = null
        tail.clear()
        charsSinceCycleCheck = 0
        lastCut = null
    }

    /** 命中规则（用于提示文案与日志区分） */
    enum class Rule {
        /** 同一段文本连续逐字出现（周期 1） */
        IDENTICAL_DELTAS,

        /** 尾部是一个 ≤ [maxCyclePeriodChars] 字符周期的逐字重复（短句循环） */
        REPEATING_CYCLE,
    }

    /** 判定结果。家族同款：Continue / 停止类。 */
    sealed interface Verdict {
        data object Continue : Verdict

        /** 连续 [stalledCalls] 次「纯思考/复述上一步」⇒ 本轮应优雅停止 */
        data class Stalled(val stalledCalls: Int, val restated: Boolean) : Verdict

        /** 调用内部逐字重复 ⇒ 立刻中止本次流（[repeated]：相同 delta 次数或周期长度） */
        data class Repeating(val rule: Rule, val repeated: Int) : Verdict
    }

    companion object {
        /** 3 次：与家族另外三员的阈值量级一致（第 2 次可疑、第 3 次基本可断定在原地打转） */
        const val DEFAULT_MAX_STALLED_CALLS = 3

        /** 2048：社区插件标定值；更短的推理不计数（短调用既不算退化也不算进展） */
        const val DEFAULT_MIN_REASONING_CHARS = 2048

        /** 0.8：跨调用复述的 containment 阈值（用包含度而非 Jaccard，见类注释） */
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.8

        /** 60：社区插件标定的连续相同 delta 阈值 */
        const val DEFAULT_MAX_IDENTICAL_DELTAS = 60

        /** 256：实测形态在 256–270 字符内可判定（跨度过短会误杀短片段） */
        const val DEFAULT_CYCLE_SPAN_CHARS = 256

        /** 64：实测周期是 12/26 字符量级；放宽到 64 仍远离正常文本的「周期性」 */
        const val DEFAULT_MAX_CYCLE_PERIOD_CHARS = 64

        private const val GRAM_SIZE = 8
        private const val GRAM_WINDOW_CHARS = 4000
        private const val CYCLE_CHECK_STRIDE = 32

        /** 尾部逐字周期的最小周期（KMP 前缀函数；O(n)） */
        internal fun minimalPeriod(text: String): Int {
            val n = text.length
            if (n < 2) return n
            val fail = IntArray(n)
            var k = 0
            for (i in 1 until n) {
                while (k > 0 && text[i] != text[k]) k = fail[k - 1]
                if (text[i] == text[k]) k++
                fail[i] = k
            }
            return n - fail[n - 1]
        }

        /** 首周期内的不同字符数（长 `====` / `────` 这类单字符重复不该被当循环） */
        internal fun distinctChars(text: String, period: Int): Int =
            text.take(period).toSet().size

        /** 两个文本的 shingle 包含度：小集合被大集合覆盖的比例（Jaccard 会被「新内容」稀释） */
        internal fun containment(a: String, b: String): Double {
            val ga = shingles(a)
            val gb = shingles(b)
            if (ga.isEmpty() || gb.isEmpty()) return 0.0
            val (small, large) = if (ga.size <= gb.size) ga to gb else gb to ga
            return small.count { it in large }.toDouble() / small.size
        }

        private fun shingles(text: String): Set<Int> {
            val s = text.takeLast(GRAM_WINDOW_CHARS)
            if (s.length < GRAM_SIZE) return emptySet()
            val out = HashSet<Int>(s.length - GRAM_SIZE + 1)
            for (i in 0..s.length - GRAM_SIZE) out.add(s.substring(i, i + GRAM_SIZE).hashCode())
            return out
        }
    }
}

/** 该消息里的推理文本（多段用换行连接） */
internal fun UIMessage.reasoningText(): String =
    parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("\n") { it.reasoning }

/** 该消息是否产出**可见正文/图片**（不含工具调用——那两者在护栏里语义不同） */
internal fun UIMessage.hasVisibleTextOutput(): Boolean = parts.any { part ->
    (part is UIMessagePart.Text && part.text.isNotBlank()) || part is UIMessagePart.Image
}

/** 该消息是否发起了工具调用（= 实打实的进展） */
internal fun UIMessage.hasToolCalls(): Boolean = parts.any { it is UIMessagePart.Tool }
