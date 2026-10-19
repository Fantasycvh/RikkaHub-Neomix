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
 * **敏感头脱敏**：三个 provider 的凭据头（[SENSITIVE_HEADERS]）的值统一替换为 `[redacted]`，
 * 避免把 API key 明文落盘或暴露在内存日志 UI 里。非敏感头原样保留（排查需要看 `Content-Type` 等）。
 *
 * **预签名 URL 脱敏**：URL / 请求体 / 错误文本里的 `X-Amz-Signature` 等凭据级 query 参数
 * 由 [sanitizeSensitiveText] 替换，其余文本（含对象 key、状态码、诊断消息）保持原样。
 * 这里再做一次防御式脱敏：即使调用方绕过 [Logging.addLog] 直接格式化，也不会泄漏。
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

/**
 * 预签名 URL 里凭据级 query 参数（AWS SigV4 预签名）的名称部分。
 *
 * 这些值本身就是 bearer token：到期前拿到即可下载/写入对象，不能进内存日志、落盘日志、
 * 日志 UI 或被分享导出的诊断文本。值只替换为 `[redacted]`，参数名与其余文本保留。
 */
private const val SENSITIVE_SIGNED_QUERY_PARAM_NAMES =
    """(?i)(x-amz-(?:signature|credential|security-token))"""

/**
 * 值部分：普通 URL/JSON 内嵌 URL 到 `&`、引号、空白、尖括号、反斜杠、`#`、右括号等为止；
 * 一次 percent-encoded 的嵌套 URL（`X-Amz-Signature%3D...`）则在 `%26`/`%23`/`%3B`
 * （编码后的 `&`/`#`/`;`）前停下，同时仍允许值里的 `%2F`、`%2B` 等编码片段。
 */
private val SENSITIVE_SIGNED_QUERY_PARAM = Regex(
    """$SENSITIVE_SIGNED_QUERY_PARAM_NAMES(%3d|=)((?:(?!%26|%23|%3b)[^&"'<>\s\\#)\]},;])*)""",
)

/**
 * 把文本中出现的预签名凭据参数值替换为 `[redacted]`，返回新字符串（不做原地修改）。
 *
 * 覆盖标准 URL query、JSON 字符串里内嵌的 URL、以及一次 percent 编码的嵌套 URL；
 * 只替换凭据值，不删除消息本身——其余诊断信息（host、对象 key、状态、错误正文）保持可读。
 * 公开给日志入口与错误入库路径（如媒体生成失败原因）统一使用。
 */
fun sanitizeSensitiveText(text: String): String =
    if (text.isEmpty()) {
        text
    } else {
        SENSITIVE_SIGNED_QUERY_PARAM.replace(text) { match ->
            // 已经是脱敏结果就原样返回，保证幂等（重复经过日志入口/格式化不会产生 `[redacted]]`）。
            if (match.groupValues[3].startsWith("[redacted")) {
                match.value
            } else {
                "${match.groupValues[1]}${match.groupValues[2]}[redacted]"
            }
        }
    }

/**
 * 日志写入前的统一脱敏副本。
 *
 * - [LogEntry.TextLog]：消息里可能内嵌 presigned URL（例如异常文本）；
 * - [LogEntry.RequestLog]：URL、请求体、错误文本，以及（少数会携带 URL 的）请求/响应头值。
 *
 * 落盘与内存使用同一份副本，避免「文件干净、UI 脏」或反之。
 */
private fun LogEntry.sanitized(): LogEntry = when (this) {
    is LogEntry.TextLog -> copy(message = sanitizeSensitiveText(message))
    is LogEntry.RequestLog -> copy(
        url = sanitizeSensitiveText(url),
        requestHeaders = requestHeaders.mapValues { (_, value) -> sanitizeSensitiveText(value) },
        requestBody = requestBody?.let(::sanitizeSensitiveText),
        responseHeaders = responseHeaders.mapValues { (_, value) -> sanitizeSensitiveText(value) },
        error = error?.let(::sanitizeSensitiveText),
    )
}

/**
 * 敏感请求/响应头名（小写比较）：三个 provider 的凭据都在这里。
 * - `Authorization`：OpenAI 兼容（`Bearer <key>`）、Claude（`x-api-key` 之外的 OAuth 路径）
 * - `x-api-key`：Anthropic Claude
 * - `x-goog-api-key`：Google / Gemini
 * - `Proxy-Authorization`：全局/提供商代理认证（HTTP Basic，含 base64 编码的 user:pass）
 *
 * 注意：provider 的 URL 本身通常不带 key，但 S3 预签名 URL 的 query 里带凭据级参数，
 * 那段由 [sanitizeSensitiveText] 在日志写入前统一脱敏（见上）。
 */
private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "x-api-key",
    "x-goog-api-key",
    "proxy-authorization",
)

/** 返回脱敏后的头副本（敏感头的值替换为 `[redacted]`，非敏感头原样保留）。 */
fun Map<String, String>.redacted(): Map<String, String> =
    mapValues { (k, v) -> if (k.lowercase(Locale.US) in SENSITIVE_HEADERS) "[redacted]" else v }

private fun appendHeaders(builder: StringBuilder, title: String, headers: Map<String, String>) {
    if (headers.isEmpty()) return
    builder.append('[').append(title).append("]\n")
    headers.forEach { (key, value) -> builder.append("  ").append(key).append(": ").append(value).append('\n') }
}
