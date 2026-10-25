package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isProviderSigned
import app.androidrik.rikkahub.data.ai.prompts.isCompactionCheckpointPreamble
import java.util.Locale

/**
 * 上下文净化（P0 dry-run 实现）：`docs/design/design-context-purification.md` revision 5。
 *
 * 把「已判定退化的思考残留」从**发送窗口的副本**里拿掉——落库与 UI 一概不动。
 * P0 阶段结果只用于观察（`PromptInspectPage` 的 dry-run 卡 + `purge.dryrun` 日志），
 * **不改发送**；P1 在同一落点把副本换成实发（设计稿 §4.5）。
 *
 * 作用域（§4.1 revision 5）：发送窗口内、检查点之后、最后一个「真实 USER」之前的全部
 * **已完成**消息——在途轮（`u_last` 起）不碰，顺带避开设计稿 §14 的累积推理陷阱。
 *
 * 规则（§4.2，全部**消息内**判据；containment / minimalPeriod / distinctChars 与护栏同源，
 * 同包直接复用，阈值不另立）：
 * - **C1a 周期截断**：Reasoning part 尾部存在 ≥ 256 字符的逐字周期（周期 2..64、首周期
 *   ≥ 2 个不同字符）⇒ 截到周期起点，保留前缀；
 * - **C1b 块级去重**：同一 part 内按 512 字符切块，后块被更早块 containment ≥ 0.8 覆盖
 *   ⇒ 截到该块起点；
 * - **C1c part 级去重**：同一条消息里，后一个 Reasoning part 被更早保留的 Reasoning
 *   part 覆盖 ≥ 0.8 ⇒ 整段去掉（保留最早一份）；
 * - **C2 工具空转**：同消息内 (toolName, trim 后 input) 重复且 output 等价（同为空，或
 *   文本逐字相等）⇒ 丢掉后续重复的 Tool part。input 不做 JSON 规范化、不同错误文本不算
 *   等价——**宁少清、不误删**（§4.2「output 等价条件」）。
 *
 * 不变式（§4.2，由单测钉住）：只处理非合成 ASSISTANT 消息 ⇒ USER / 合成 / 检查点消息
 * 原样返回；`isProviderSigned()` 的 Reasoning part **只看不碰**（本会命中的规则改记
 * [Rule.SIGNED_SKIP]）；幂等；同输入同输出；改动只产副本（`copy(parts = ...)`）。
 *
 * 纯类、无 Android 依赖 ⇒ 可 JVM 单测（护栏同款）。
 */
internal object ContextPurifier {
    enum class Rule { CYCLE_TAIL, BLOCK_DUP, PART_DUP, TOOL_IDLE, SIGNED_SKIP }

    data class Decision(
        val rule: Rule,
        /** 窗口内消息下标（窗口 = assembled 去掉 u_last 起的在途轮；含被跳过的检查点，与实发列表对齐） */
        val messageIndex: Int,
        /** 消息内 part 下标；SIGNED_SKIP 指向被放过的签名 part */
        val partIndex: Int,
        val detail: String,
    )

    data class Result(
        /** 窗口消息条数（0 = 无已完成轮次可清） */
        val windowCount: Int,
        /** 整个 assembled 的净化副本（窗口外消息与未命中消息共享原实例）；P0 不用于发送 */
        val messages: List<UIMessage>,
        val decisions: List<Decision>,
        /** 仅诊断用：被截掉/去掉的字符数（不是账单口径，§7） */
        val savedChars: Int,
        /** 窗口内容指纹（id + 关键文本 hash）：同 hash 不同决策 ⇒ 翻转（§4.1 观察项） */
        val windowHash: Int,
    )

    /** C1b 块大小（§4.2「~512 字符」） */
    private const val BLOCK_CHARS = 512

    private val spanChars get() = ThinkingDegenerationGuard.DEFAULT_CYCLE_SPAN_CHARS
    private val maxPeriodChars get() = ThinkingDegenerationGuard.DEFAULT_MAX_CYCLE_PERIOD_CHARS
    private val similarity get() = ThinkingDegenerationGuard.DEFAULT_SIMILARITY_THRESHOLD

    /**
     * 对组装好的发送消息列表做净化（只看副本）。窗口语义见类注释；
     * `assembled` 形如 [buildSendWindowMessages][app.androidrik.rikkahub.data.ai.buildSendWindowMessages]
     * 的产物：检查点在前（本函数显式跳过）、其余在后。
     */
    fun purgeSendWindow(assembled: List<UIMessage>): Result {
        val uLast = assembled.indexOfLast { it.isPurgeBoundaryUser() }
        if (uLast <= 0) {
            // 首条对话 / 窗口为空：无已完成轮次可清（§4.1 理由 3）
            return Result(0, assembled, emptyList(), 0, 0)
        }
        val window = assembled.subList(0, uLast)
        val decisions = mutableListOf<Decision>()
        val out = ArrayList<UIMessage>(assembled.size)
        var savedChars = 0
        var hash = 1
        window.forEachIndexed { index, message ->
            hash = hash * 31 + message.purgeFingerprint()
            if (message.role == MessageRole.ASSISTANT && !message.isSynthetic && !message.isCheckpointLike()) {
                val purged = purgeMessage(message, index, decisions)
                savedChars += purged.savedChars
                out.add(purged.message)
            } else {
                out.add(message)
            }
        }
        out.addAll(assembled.subList(uLast, assembled.size))
        return Result(window.size, out, decisions, savedChars, hash)
    }

    /** 最后一个「真实 USER」——只在途轮起点的定位用（§4.1 revision 5；判法同 [memoryQueryOf]） */
    private fun UIMessage.isPurgeBoundaryUser(): Boolean =
        !isSynthetic && !isContextCheckpoint && role == MessageRole.USER

    /** 原生合成检查点（isSynthetic 前导）或导入检查点（isContextCheckpoint 标记）都不进净化 */
    private fun UIMessage.isCheckpointLike(): Boolean =
        isContextCheckpoint ||
            (isSynthetic && role == MessageRole.USER &&
                parts.any { it is UIMessagePart.Text && it.text.isCompactionCheckpointPreamble() })

    private class MessagePurge(val message: UIMessage, val savedChars: Int)

    private fun purgeMessage(message: UIMessage, messageIndex: Int, decisions: MutableList<Decision>): MessagePurge {
        var savedChars = 0
        var changed = false

        // ---- C1a / C1b：逐 Reasoning part，产出净化后文本 ----
        val reasoningText = arrayOfNulls<String>(message.parts.size)
        message.parts.forEachIndexed { i, part ->
            if (part !is UIMessagePart.Reasoning) return@forEachIndexed
            var text = part.reasoning
            cycleStart(text)?.let { (period, start) ->
                if (part.isProviderSigned()) {
                    decisions += Decision(Rule.SIGNED_SKIP, messageIndex, i,
                        "C1a period=$period tail=${text.length - start} chars (signed, untouched)")
                } else {
                    decisions += Decision(Rule.CYCLE_TAIL, messageIndex, i,
                        "period=$period cut ${text.length}->$start")
                    savedChars += text.length - start
                    text = text.substring(0, start)
                    changed = true
                }
            }
            blockDupStart(text)?.let { (start, score) ->
                if (part.isProviderSigned()) {
                    decisions += Decision(Rule.SIGNED_SKIP, messageIndex, i,
                        "C1b block dup ${score.format2()} (signed, untouched)")
                } else {
                    decisions += Decision(Rule.BLOCK_DUP, messageIndex, i,
                        "dup of earlier block ${score.format2()} cut ${text.length}->$start")
                    savedChars += text.length - start
                    text = text.substring(0, start)
                    changed = true
                }
            }
            reasoningText[i] = text
        }

        // ---- C1c（跨 Reasoning part）+ C2（跨 Tool part）：重建 parts ----
        val rebuilt = ArrayList<UIMessagePart>(message.parts.size)
        val keptReasoning = mutableListOf<String>()
        val seenTools = mutableListOf<Triple<String, String, String>>() // (toolName, trimmedInput, outputText)
        message.parts.forEachIndexed { i, part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    val text = reasoningText[i] ?: part.reasoning
                    val coveredBy = keptReasoning.indexOfFirst {
                        ThinkingDegenerationGuard.containment(text, it) >= similarity
                    }
                    when {
                        coveredBy < 0 -> {
                            keptReasoning += text
                            if (text !== part.reasoning) {
                                rebuilt += part.copy(reasoning = text)
                            } else {
                                rebuilt += part
                            }
                        }
                        part.isProviderSigned() -> {
                            decisions += Decision(Rule.SIGNED_SKIP, messageIndex, i,
                                "C1c covered by reasoning #$coveredBy (signed, untouched)")
                            keptReasoning += text
                            rebuilt += part
                        }
                        else -> {
                            decisions += Decision(Rule.PART_DUP, messageIndex, i,
                                "covered by reasoning #$coveredBy, dropped")
                            savedChars += text.length
                            changed = true
                        }
                    }
                }
                is UIMessagePart.Tool -> {
                    val outputText = toolOutputText(part)
                    val dup = seenTools.firstOrNull {
                        it.first == part.toolName &&
                            it.second == part.input.trim() &&
                            outputsEquivalent(it.third, outputText)
                    }
                    if (dup != null) {
                        decisions += Decision(Rule.TOOL_IDLE, messageIndex, i,
                            "${part.toolName}(${part.input.trim().take(24)}) idle-dup of earlier call")
                        savedChars += part.input.length + outputText.length
                        changed = true
                    } else {
                        seenTools += Triple(part.toolName, part.input.trim(), outputText)
                        rebuilt += part
                    }
                }
                else -> rebuilt += part
            }
        }
        return if (!changed) MessagePurge(message, savedChars) else MessagePurge(message.copy(parts = rebuilt), savedChars)
    }

    /** C1a：尾部逐字周期的 (周期, 截断点)；不命中返回 null。判据参数与护栏完全同源。 */
    private fun cycleStart(text: String): Pair<Int, Int>? {
        if (text.length < spanChars) return null
        val window = text.takeLast(spanChars)
        val period = ThinkingDegenerationGuard.minimalPeriod(window)
        if (period !in 2..maxPeriodChars) return null
        if (ThinkingDegenerationGuard.distinctChars(window, period) < 2) return null
        // 周期后缀向前扩展到最长：截断点 = 周期性最早仍成立处
        var start = text.length - period
        while (start > 0 && text[start - 1] == text[start - 1 + period]) start--
        return if (text.length - start >= spanChars) period to start else null
    }

    /** C1b：首个被更早块覆盖的块的 (起点, containment)；不命中返回 null */
    private fun blockDupStart(text: String): Pair<Int, Double>? {
        if (text.length < BLOCK_CHARS * 2) return null
        val blocks = text.chunked(BLOCK_CHARS)
        for (i in 1 until blocks.size) {
            for (j in 0 until i) {
                val score = ThinkingDegenerationGuard.containment(blocks[i], blocks[j])
                if (score >= similarity) return i * BLOCK_CHARS to score
            }
        }
        return null
    }

    private fun toolOutputText(tool: UIMessagePart.Tool): String =
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    /** 等价 = 同为空，或文本逐字相等；不同错误文本不算等价（宁少清、不误删） */
    private fun outputsEquivalent(a: String, b: String): Boolean =
        (a.isBlank() && b.isBlank()) || a == b

    /** 窗口指纹：String.hashCode 在不可变实例上已缓存，重复请求近似免费 */
    private fun UIMessage.purgeFingerprint(): Int {
        var h = id.hashCode()
        for (part in parts) {
            h = h * 31 + when (part) {
                is UIMessagePart.Text -> part.text.hashCode()
                is UIMessagePart.Reasoning -> part.reasoning.hashCode()
                is UIMessagePart.Tool -> {
                    var t = part.input.hashCode()
                    part.output.forEach { op ->
                        t = t * 31 + when (op) {
                            is UIMessagePart.Text -> op.text.hashCode()
                            is UIMessagePart.Image -> op.url.hashCode()
                            else -> op.javaClass.name.hashCode()
                        }
                    }
                    t
                }
                else -> part.javaClass.name.hashCode()
            }
        }
        return h
    }

    private fun Double.format2(): String = String.format(Locale.ROOT, "%.2f", this)
}
