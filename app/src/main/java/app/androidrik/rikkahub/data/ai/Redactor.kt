package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/**
 * 脱敏管线判定层（`docs/design/drafts/design-redaction-pipeline.md` §3，纯函数无 Android 依赖）。
 *
 * 三层按序执行（设计稿 §3.1，2026-11-21 拍板「标记块约定 + 高置信正则白名单混合」）：
 * - **L3 私钥块**：`-----BEGIN … PRIVATE KEY-----` 到块尾（无块尾 ⇒ 到文本末尾，**截断容忍**）
 *   整块替换——先于 L1/L2 跑，防止块内 base64 体被白名单误伤、误计；
 * - **L1 已知密钥登记**：调用方传入的精确值集合（`collectCredentials` 收集：供应商
 *   apiKey/apiKeys + 供应商/助手两级 customHeaders 值，≥[CREDENTIAL_MIN_CHARS] 字符；
 *   OAuth 账号凭证**不登记**——登录态不进对话历史）。最长优先交替，防「短 key 是长 key
 *   前缀」的半截替换；
 * - **L2 高置信正则白名单**（§3.1 初始集，2026-11-21 拍板确认）：sk- / AIza / AKIA / gh?_ /
 *   github_pat_ / JWT 三段式。lookbehind 挡 `task-abc` 这类标识符误伤；通用 Bearer / 任意
 *   长 hex **不进**白名单（中低置信，误伤面大）。
 *
 * **脱敏产物统一为自闭合标记** `<redacted kind="…" />`——不携带任何原值片段（设计约束：
 * **标记文本不得包含任何白名单可命中片段**，幂等性因此不靠识别标记：二次脱敏时原值已不在、
 * 标记不命中任何层 ⇒ 原样通过，单测钉住）。
 *
 * 作用面（拍板 2，§3.2）：**仅压缩 prompt 侧**（`compressToCheckpoint` 的 contentToCompress +
 * priorContext，落点在 `summaryAsText(2000)` 截断**之后**）与**导出/备份文本出口**（拍板 §5.1
 * 「仅导出/备份」）。落库 / UI / 普通 chat 发送不感知。
 */
internal object Redactor {

    data class Result(
        /** 脱敏后的文本（无命中 = 原样返回同一内容） */
        val text: String,
        /** 按 kind 的命中计数（审计用；不含任何原值） */
        val counts: Map<String, Int>,
    )

    /** 登记值的最低长度：更短的串误伤/噪声比大于收益（§3.1） */
    const val CREDENTIAL_MIN_CHARS: Int = 8

    internal const val KIND_CREDENTIAL = "credential"
    internal const val KIND_API_KEY = "api-key"
    internal const val KIND_TOKEN = "token"
    internal const val KIND_PRIVATE_KEY = "private-key"

    internal const val MARKER_CREDENTIAL = """<redacted kind="credential"/>"""
    internal const val MARKER_API_KEY = """<redacted kind="api-key"/>"""
    internal const val MARKER_TOKEN = """<redacted kind="token"/>"""
    internal const val MARKER_PRIVATE_KEY = """<redacted kind="private-key"/>"""

    private val PRIVATE_KEY_HEAD = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")
    private val PRIVATE_KEY_TAIL = Regex("""-----END [A-Z ]*PRIVATE KEY-----""")

    private val WHITELIST: List<Pair<String, Regex>> = listOf(
        KIND_API_KEY to Regex("""(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}"""),
        KIND_API_KEY to Regex("""(?<![A-Za-z0-9_-])AIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])"""),
        KIND_API_KEY to Regex("""(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![0-9A-Z])"""),
        KIND_TOKEN to Regex("""(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{36,}(?![A-Za-z0-9_])"""),
        KIND_TOKEN to Regex("""(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9_]{20,}"""),
        KIND_TOKEN to Regex(
            """(?<![A-Za-z0-9_])eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_.])"""
        ),
    )

    fun redact(text: String, credentials: Set<String>): Result {
        if (text.isEmpty()) return Result(text, emptyMap())
        val counts = mutableMapOf<String, Int>()
        var current = redactPrivateKeys(text, counts)
        current = redactRegistered(current, credentials, counts)
        current = redactWhitelist(current, counts)
        return Result(current, counts.toMap())
    }

    /**
     * L3：私钥块整块替换，循环处理多个块；**截断容忍**——只有块头没有块尾（导出/摘要
     * 中途截断）时替换到文本末尾，绝不把块体留在产物里。
     */
    private fun redactPrivateKeys(text: String, counts: MutableMap<String, Int>): String {
        var cursor = 0
        val out = StringBuilder()
        while (cursor < text.length) {
            val head = PRIVATE_KEY_HEAD.find(text, cursor) ?: break
            val tail = PRIVATE_KEY_TAIL.find(text, head.range.last + 1)
            val endExclusive = tail?.range?.last?.plus(1) ?: text.length
            out.append(text, cursor, head.range.first)
            out.append(MARKER_PRIVATE_KEY)
            counts.merge(KIND_PRIVATE_KEY, 1, Int::plus)
            cursor = endExclusive
            if (tail == null) break
        }
        if (cursor == 0) return text
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** L1：登记值精确匹配（最长优先，防短 key 是长 key 前缀时半截替换） */
    private fun redactRegistered(text: String, credentials: Set<String>, counts: MutableMap<String, Int>): String {
        val sorted = credentials
            .filter { it.length >= CREDENTIAL_MIN_CHARS }
            .sortedByDescending { it.length }
        if (sorted.isEmpty()) return text
        val pattern = Regex(sorted.joinToString("|") { Regex.escape(it) })
        var hits = 0
        val out = pattern.replace(text) {
            hits++
            MARKER_CREDENTIAL
        }
        if (hits > 0) counts.merge(KIND_CREDENTIAL, hits, Int::plus)
        return out
    }

    private fun redactWhitelist(text: String, counts: MutableMap<String, Int>): String {
        var current = text
        for ((kind, pattern) in WHITELIST) {
            var hits = 0
            current = pattern.replace(current) {
                hits++
                markerFor(kind)
            }
            if (hits > 0) counts.merge(kind, hits, Int::plus)
        }
        return current
    }

    private fun markerFor(kind: String): String = when (kind) {
        KIND_API_KEY -> MARKER_API_KEY
        KIND_TOKEN -> MARKER_TOKEN
        else -> MARKER_CREDENTIAL
    }
}

/**
 * L1 登记的密钥源（§3.1，拍板 2026-11-21 确认）：供应商 apiKey/apiKeys（[splitEffectiveKeys]
 * 同规则拆分）+ 供应商/助手两级 customHeaders 的值；不足 [Redactor.CREDENTIAL_MIN_CHARS]
 * 字符的值不登记（误伤/噪声比大于收益）；OAuth 账号凭证不登记。
 */
internal fun collectCredentials(providers: List<ProviderSetting>, assistants: List<Assistant>): Set<String> {
    val raw = buildSet {
        providers.forEach { provider ->
            splitEffectiveKeys(provider.effectiveApiKeys).forEach { add(it) }
            provider.customHeaders.forEach { add(it.value) }
        }
        assistants.forEach { assistant ->
            assistant.customHeaders.forEach { add(it.value) }
        }
    }
    return raw.filterTo(HashSet()) { it.length >= Redactor.CREDENTIAL_MIN_CHARS }
}

/**
 * 导出语义的消息级脱敏（§4.2，拍板「仅导出/备份」+ 默认开）：替换 Text/Reasoning/Notice
 * part 的文本（含 [UIMessagePart.Tool].output 内嵌同类 part），媒体/文件引用等非文本 part
 * 原样共享实例；无命中的消息共享原实例（零成本快速路径）。
 *
 * 返回（脱敏副本, 命中总数）——副本进导出/分享/Web API 产物，命中数给完成提示。
 * 落库 / UI 不调用本函数。
 */
internal fun List<UIMessage>.redactedForExport(credentials: Set<String>): Pair<List<UIMessage>, Int> {
    if (credentials.isEmpty()) return this to 0
    var totalHits = 0
    val out = map { message ->
        var messageHits = 0
        val parts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val r = Redactor.redact(part.text, credentials)
                    if (r.counts.isEmpty()) part else {
                        messageHits += r.counts.values.sum()
                        part.copy(text = r.text)
                    }
                }
                is UIMessagePart.Reasoning -> {
                    val r = Redactor.redact(part.reasoning, credentials)
                    if (r.counts.isEmpty()) part else {
                        messageHits += r.counts.values.sum()
                        part.copy(reasoning = r.text)
                    }
                }
                is UIMessagePart.Notice -> {
                    val r = Redactor.redact(part.text, credentials)
                    if (r.counts.isEmpty()) part else {
                        messageHits += r.counts.values.sum()
                        part.copy(text = r.text)
                    }
                }
                is UIMessagePart.Tool -> {
                    var toolHits = 0
                    val output = part.output.map { outputPart ->
                        when (outputPart) {
                            is UIMessagePart.Text -> {
                                val r = Redactor.redact(outputPart.text, credentials)
                                if (r.counts.isEmpty()) outputPart else {
                                    toolHits += r.counts.values.sum()
                                    outputPart.copy(text = r.text)
                                }
                            }
                            is UIMessagePart.Reasoning -> {
                                val r = Redactor.redact(outputPart.reasoning, credentials)
                                if (r.counts.isEmpty()) outputPart else {
                                    toolHits += r.counts.values.sum()
                                    outputPart.copy(reasoning = r.text)
                                }
                            }
                            else -> outputPart
                        }
                    }
                    if (toolHits == 0) part else {
                        messageHits += toolHits
                        part.copy(output = output)
                    }
                }
                else -> part
            }
        }
        if (messageHits == 0) message else {
            totalHits += messageHits
            message.copy(parts = parts)
        }
    }
    return out to totalHits
}
