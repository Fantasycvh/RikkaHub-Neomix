package app.androidrik.common.android

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_RECENT_LOGS = 100

/** 落盘日志文件名（可读文本格式，见 [formatLogEntry]） */
private const val LOG_FILE_NAME = "rikkahub-logs.txt"

/** 轮转备份文件名（当前文件超限时整体改名到此，旧备份直接丢弃） */
private const val LOG_FILE_BACKUP = "rikkahub-logs.1.txt"

/** 单文件上限：超过则轮转，防止长期开启写满磁盘（含备份最多占用 2×） */
private const val MAX_LOG_FILE_BYTES = 4L * 1024 * 1024

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    @Volatile
    private var requestLoggingEnabled = false

    // ---- 落盘日志（「导出到文件」开关）----
    // 目的：内存日志上限 100 条且进程退出即失；logcat 又有单条 ~4KB 截断，
    // 大请求体（40K token 级）根本看不到。落盘可取回**完整**报文用于排查。
    @Volatile
    private var fileLoggingEnabled = false
    @Volatile
    private var logDirectory: File? = null

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    // ---- 落盘 API ----

    /** 指定落盘目录（App 启动时调用一次）。目录不存在会被创建。 */
    fun initLogDirectory(dir: File) {
        logDirectory = dir
        runCatching { dir.mkdirs() }
    }

    fun isFileLoggingEnabled(): Boolean = fileLoggingEnabled

    fun setFileLoggingEnabled(enabled: Boolean) {
        fileLoggingEnabled = enabled
    }

    /** 当前落盘文件（不存在或为空返回 null），供「分享 / 显示大小」用。 */
    fun getLogFile(): File? = logDirectory
        ?.let { File(it, LOG_FILE_NAME) }
        ?.takeIf { it.exists() && it.length() > 0 }

    /** 清空落盘日志（当前 + 备份）。 */
    fun clearLogFile() {
        val dir = logDirectory ?: return
        runCatching {
            File(dir, LOG_FILE_NAME).takeIf { it.exists() }?.delete()
            File(dir, LOG_FILE_BACKUP).takeIf { it.exists() }?.delete()
        }
    }

    // ---- 实时订阅（日志页用）----
    // common 模块**没有 coroutines 依赖**（对齐 turbo 的取舍）：故用回调列表而非 Flow。
    // CopyOnWriteArrayList：写少读多，且遍历时不加锁 ⇒ 回调里再打日志也不会自锁。
    // 通知在 recentLogs 的锁**外**执行（见 addLog），避免第三方回调持锁期间卡住日志写入。
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 注册「有新日志」回调；返回反注册器（调用方负责在生命周期结束时调用，如 DisposableEffect）。 */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun addLog(entry: LogEntry) {
        // 在进内存 ring 与落盘文件之前统一脱敏：两个展示面（日志 UI / 文件）共用同一份日志，
        // 只对落盘脱敏还不够——内存里也得是干净副本（见 sanitizeSensitiveText）。
        val safe = entry.sanitized()
        synchronized(recentLogs) {
            recentLogs.add(0, safe)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
        appendToFileIfEnabled(safe)
        // 通知放锁外：回调方通常会 getRecentLogs()（自己要加锁），在锁内回调会与它相互等待
        listeners.forEach { it() }
    }

    /**
     * 追加一条到落盘文件。IO 失败静默吞掉——日志不该影响主流程
     * （调用方可能是 OkHttp 网络线程，宁可丢一条日志也不能上抛）。
     */
    private fun appendToFileIfEnabled(entry: LogEntry) {
        if (!fileLoggingEnabled) return
        val dir = logDirectory ?: return
        runCatching {
            val file = File(dir, LOG_FILE_NAME)
            rotateIfNeeded(file)
            file.appendText(formatLogEntry(entry))
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_LOG_FILE_BYTES) return
        val backup = File(file.parentFile, LOG_FILE_BACKUP)
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
    }
}

/**
 * 把一条日志格式化为**可读文本块**（纯函数，便于单测）。
 *
 * 为什么不用 JSONL：请求体本身就是一大段 JSON，再被 JSON 转义进单行会变得难读；
 * 排查场景（贴进 issue / 聊天）可读性优先，故用带分隔线的文本块、正文原样保留。
 *
 * **敏感头脱敏**：凭据头（[SENSITIVE_HEADERS]）的值统一替换为 `[redacted]`，
 * 避免把 API key 明文落盘或暴露在内存日志 UI 里。非敏感头原样保留（排查需要看 `Content-Type` 等）。
 *
 * **凭据级 query 参数脱敏**：URL / 请求体 / 错误文本里的 `X-Amz-Signature`、`key=`、
 * `api_key=`、`access_token=` 等凭据参数由 [sanitizeSensitiveText] 替换，其余文本
 * （含对象 key、状态码、诊断消息）保持原样。写入 ring 与格式化各调用一次，保证两个展示面一致。
 */
internal fun formatLogEntry(entry: LogEntry): String {
    val safe = entry.sanitized()
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(safe.timestamp))
    val builder = StringBuilder()
    builder.append("===== ").append(time).append(" | ").append(safe.tag).append(" =====\n")
    when (safe) {
        is LogEntry.TextLog -> {
            builder.append(safe.message).append('\n')
        }

        is LogEntry.RequestLog -> {
            builder.append(safe.method).append(' ').append(safe.url).append('\n')
            val meta = buildList {
                safe.responseCode?.let { add("status=$it") }
                safe.durationMs?.let { add("duration=${it}ms") }
            }
            if (meta.isNotEmpty()) builder.append(meta.joinToString(" ")).append('\n')
            safe.error?.let { builder.append("error: ").append(it).append('\n') }
            appendHeaders(builder, "request headers", safe.requestHeaders.redacted())
            safe.requestBody?.let { body ->
                builder.append("[request body]\n").append(body).append('\n')
            }
            appendHeaders(builder, "response headers", safe.responseHeaders.redacted())
        }
    }
    return builder.toString()
}

private const val REDACTED_VALUE = "[redacted]"

/** 已脱敏值的开头；用它判断「这条已经处理过」，从而保证 [sanitizeSensitiveText] 幂等。 */
private const val REDACTED_PREFIX = "[redacted"

/**
 * AWS SigV4 预签名参数名（bare 匹配，不带左边界）。
 *
 * 这几个值本身就是 bearer token 级别凭据（到期前可下载/写入对象）。保留旧的「裸文本」匹配：
 * 它们也可能出现在异常消息、JSON 字符串深处的非 URL 上下文里，历史用例依赖这一点。
 */
private const val SENSITIVE_AWS_QUERY_PARAM_NAMES =
    "x-amz-(?:signature|credential|security-token)"

/**
 * 凭据级 query / form 参数名里「本身就是复合词」的部分（大小写不敏感）。
 *
 * 其余是各家 API 常见的「把密钥放进 query」写法，典型是 Vertex API key 模式的 `?key=AIza...`
 * （见 ai 模块 GoogleProvider）、OAuth 授权码/刷新令牌、以及把 `api_key` 拼进 URL 的自建网关。
 */
private const val SENSITIVE_COMPOUND_QUERY_PARAM_NAMES =
    "(?:" +
        "api[_-]?key|api[_-]?secret" +
        "|access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|bearer[_-]?token|session[_-]?token" +
        "|access[_-]?key|secret[_-]?key|signing[_-]?key|private[_-]?key" +
        "|client[_-]?secret" +
        ")"

/**
 * 单独出现的短参数名（`?key=`、`?token=` 等）。
 *
 * 必须带 [SENSITIVE_QUERY_PARAM_BOUNDARY]：否则 `monkey=`、`mykey=`、`hockey=` 会被误当成
 * `key=`，而 `max_tokens=`、`token_count=` 也会被 `token` 误伤。
 */
private const val SENSITIVE_SHORT_QUERY_PARAM_NAMES = "(?:key|token|secret|password|passwd)"

/**
 * 复合参数名的左边界：前一个字符不是标识符字符（`myapi_key`、`monkey_client_secret` 不能命中）。
 *
 * 为什么还要 `(?<=%26)` / `(?<=%3[fF])`：一次 percent 编码的嵌套 URL 里参数名前面是 `%26`/`%3F`，
 * 它们的最后一个字符是数字/字母，会被 `(?<![A-Za-z0-9_-])` 误否；把这两种编码分隔符作为
 * 额外的零宽断言分支，既保住 `%26api_key%3D...`，又不用写变长 lookbehind
 * （Android 对变长 lookbehind 支持不一致）。每个分支都是固定长度，兼容性安全。
 */
private const val SENSITIVE_QUERY_PARAM_LEFT_BOUNDARY =
    """(?:(?<![A-Za-z0-9_-])|(?<=%26)|(?<=%3[fF]))"""

/**
 * 短参数名边界：`?`/`&`（query 参数分隔符）、字符串开头，或 URL 编码后的 `%26`/`%3F` 分隔符。
 *
 * 为什么**不**把空格/`;`/引号也当边界：
 * 1. 诊断文本里的 `key=` 不一定是凭据——历史用例明确要求保留
 *    `S3 upload failed ...; key=rikkahub_uploads/abc.png` 这种对象 key（见 LogSanitizationTest）；
 * 2. 编码后的嵌套 URL 里 `%26` 以数字结尾，`(?<![A-Za-z0-9_-])` 这类 lookbehind 会把
 *    `%26key=` 一并否掉；把边界做成捕获组、替换时原样保留更稳，也避免 Android 对变长
 *    lookbehind 的支持差异。
 *
 * 代价：`;key=secret`（legacy form 分隔符）不覆盖——按当前约定凭据只从 query/form 的
 * `?`/`&` 分隔位置识别，宁可漏打码也不误伤诊断信息。
 */
private const val SENSITIVE_QUERY_PARAM_BOUNDARY = """(?:^|[?&]|%26|%3[fF])"""

/**
 * 值部分：普通 URL/JSON 内嵌 URL 到 `&`、引号、空白、尖括号、反斜杠、`#`、右括号等为止；
 * 一次 percent-encoded 的嵌套 URL（`X-Amz-Signature%3D...`）则在 `%26`/`%23`/`%3B`
 * （编码后的 `&`/`#`/`;`）前停下，同时仍允许值里的 `%2F`、`%2B` 等编码片段。
 */
private const val SENSITIVE_QUERY_PARAM_VALUE =
    """(?:(?!%26|%23|%3[bB])[^&"'<>\s\\#)\]},;])*"""

private val SENSITIVE_AWS_QUERY_PARAM = Regex(
    """(?i)($SENSITIVE_AWS_QUERY_PARAM_NAMES)(%3d|=)($SENSITIVE_QUERY_PARAM_VALUE)""",
)

private val SENSITIVE_COMPOUND_QUERY_PARAM = Regex(
    """(?i)$SENSITIVE_QUERY_PARAM_LEFT_BOUNDARY($SENSITIVE_COMPOUND_QUERY_PARAM_NAMES)(%3d|=)""" +
        "($SENSITIVE_QUERY_PARAM_VALUE)",
)

private val SENSITIVE_SHORT_QUERY_PARAM = Regex(
    """(?i)($SENSITIVE_QUERY_PARAM_BOUNDARY)($SENSITIVE_SHORT_QUERY_PARAM_NAMES)(%3d|=)""" +
        "($SENSITIVE_QUERY_PARAM_VALUE)",
)

/**
 * 把文本中出现的凭据级参数值替换为 `[redacted]`，返回新字符串（不做原地修改）。
 *
 * 覆盖范围：标准 URL query、form 编码正文、JSON 字符串里内嵌的 URL、一次 percent 编码的嵌套 URL；
 * 参数名包含 AWS SigV4 预签名（保留旧裸文本匹配）、`api_key`/`api-key`/`apiKey`、`access_token`/
 * `refresh_token`/`id_token`/`token`、`client_secret`、以及 query 位置单独出现的
 * `key`/`secret`/`password` 等。复合名带左边界（`myapi_key` 不命中），短名只在 `?`/`&`/开头 或
 * 编码后的 `%26`/`%3F` 之后识别（见 [SENSITIVE_QUERY_PARAM_LEFT_BOUNDARY] /
 * [SENSITIVE_QUERY_PARAM_BOUNDARY]）。
 *
 * **不覆盖** JSON 对象里的 `"key": "..."` 写法（分隔符不是 `=`/`%3D`）：正文里的普通 JSON 字段
 * 不能被无端抹掉（`{"key":"cache-key"}` 之类是合法诊断信息）；需要打码的凭据按当前约定都走
 * query/header，不靠猜 JSON 语义。
 *
 * 只替换凭据值，不删除消息本身——其余诊断信息（host、对象 key、状态码、错误正文）保持可读；
 * 已脱敏的文本再跑一遍结果不变（幂等），因此内存日志与落盘/格式化两条路径可以各调用一次。
 */
fun sanitizeSensitiveText(text: String): String {
    if (text.isEmpty()) return text
    val awsRedacted = SENSITIVE_AWS_QUERY_PARAM.replace(text) { match ->
        redactParamValue(match, valueGroup = 3)
    }
    val compoundRedacted = SENSITIVE_COMPOUND_QUERY_PARAM.replace(awsRedacted) { match ->
        redactParamValue(match, valueGroup = 3)
    }
    return SENSITIVE_SHORT_QUERY_PARAM.replace(compoundRedacted) { match ->
        redactParamValue(match, valueGroup = 4)
    }
}

/** 只把 [valueGroup] 匹配到的凭据值替换成 `[redacted]`，参数名、分隔符与其余文本原样保留。 */
private fun redactParamValue(match: MatchResult, valueGroup: Int): String {
    val value = match.groups[valueGroup] ?: return match.value
    if (value.value.startsWith(REDACTED_PREFIX)) return match.value
    val start = value.range.first - match.range.first
    val end = value.range.last + 1 - match.range.first
    return match.value.substring(0, start) + REDACTED_VALUE + match.value.substring(end)
}

/**
 * 日志写入前的统一脱敏副本。
 *
 * - [LogEntry.TextLog]：消息里可能内嵌预签名 URL / 带 key 的 URL（例如异常文本）；
 * - [LogEntry.RequestLog]：URL、请求体、错误文本，以及请求/响应头。**头在这里就按名脱敏**，
 *   不能只依赖 [formatLogEntry] 的 `.redacted()`：内存 ring（日志 UI）读的是这里产出的副本，
 *   漏在这一层就等于让 UI 直接看到明文凭据。
 *
 * 落盘与内存使用同一份副本，避免「文件干净、UI 脏」或反之。
 */
private fun LogEntry.sanitized(): LogEntry = when (this) {
    is LogEntry.TextLog -> copy(message = sanitizeSensitiveText(message))
    is LogEntry.RequestLog -> copy(
        url = sanitizeSensitiveText(url),
        requestHeaders = requestHeaders.redactedAndSanitized(),
        requestBody = requestBody?.let(::sanitizeSensitiveText),
        responseHeaders = responseHeaders.redactedAndSanitized(),
        error = error?.let(::sanitizeSensitiveText),
    )
}

/**
 * 敏感请求/响应头名（小写比较）。凭据头一律按名整体替换，不尝试解析值的格式：
 * - `Authorization`：OpenAI 兼容（`Bearer <key>`）、Claude（`x-api-key` 之外的 OAuth 路径）
 * - `x-api-key` / `api-key`：Anthropic Claude、MiMo ASR 等
 * - `x-goog-api-key`：Google / Gemini
 * - `x-subscription-token`：Brave Search
 * - `Proxy-Authorization`：全局/提供商代理认证（HTTP Basic，含 base64 编码的 user:pass）
 * - `X-Auth-Token`、`Cookie` / `Set-Cookie`：会话凭据
 *
 * 注意：provider 的 URL 本身通常不带 key，但 Vertex API key 模式会把 key 放进 query，
 * S3 预签名 URL 的 query 里也带凭据级参数；那段由 [sanitizeSensitiveText] 在日志写入前统一脱敏（见上）。
 */
private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "x-api-key",
    "api-key",
    "x-goog-api-key",
    "x-subscription-token",
    "x-auth-token",
    "proxy-authorization",
    "cookie",
    "set-cookie",
)

/**
 * 入库前的头脱敏：敏感头整体打码；其余头值再做一次文本脱敏
 * （有的服务把 URL 塞进 `X-Debug-*` 之类的普通头里，URL 中可能带凭据）。
 */
private fun Map<String, String>.redactedAndSanitized(): Map<String, String> =
    mapValues { (name, value) ->
        if (name.lowercase(Locale.US) in SENSITIVE_HEADERS) REDACTED_VALUE else sanitizeSensitiveText(value)
    }

/** 返回脱敏后的头副本（敏感头的值替换为 `[redacted]`，非敏感头原样保留）。 */
fun Map<String, String>.redacted(): Map<String, String> =
    mapValues { (k, v) -> if (k.lowercase(Locale.US) in SENSITIVE_HEADERS) REDACTED_VALUE else v }

private fun appendHeaders(builder: StringBuilder, title: String, headers: Map<String, String>) {
    if (headers.isEmpty()) return
    builder.append('[').append(title).append("]\n")
    headers.forEach { (key, value) -> builder.append("  ").append(key).append(": ").append(value).append('\n') }
}
