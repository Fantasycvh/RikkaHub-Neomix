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

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
        appendToFileIfEnabled(entry)
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
 */
internal fun formatLogEntry(entry: LogEntry): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    val builder = StringBuilder()
    builder.append("===== ").append(time).append(" | ").append(entry.tag).append(" =====\n")
    when (entry) {
        is LogEntry.TextLog -> {
            builder.append(entry.message).append('\n')
        }

        is LogEntry.RequestLog -> {
            builder.append(entry.method).append(' ').append(entry.url).append('\n')
            val meta = buildList {
                entry.responseCode?.let { add("status=$it") }
                entry.durationMs?.let { add("duration=${it}ms") }
            }
            if (meta.isNotEmpty()) builder.append(meta.joinToString(" ")).append('\n')
            entry.error?.let { builder.append("error: ").append(it).append('\n') }
            appendHeaders(builder, "request headers", entry.requestHeaders.redacted())
            entry.requestBody?.let { body ->
                builder.append("[request body]\n").append(body).append('\n')
            }
            appendHeaders(builder, "response headers", entry.responseHeaders.redacted())
        }
    }
    return builder.toString()
}

/**
 * 敏感请求/响应头名（小写比较）：三个 provider 的凭据都在这里。
 * - `Authorization`：OpenAI 兼容（`Bearer <key>`）、Claude（`x-api-key` 之外的 OAuth 路径）
 * - `x-api-key`：Anthropic Claude
 * - `x-goog-api-key`：Google / Gemini
 * - `Proxy-Authorization`：全局/提供商代理认证（HTTP Basic，含 base64 编码的 user:pass）
 *
 * URL 里不带 key（三个 provider 全走 header），故 URL 不脱敏。
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
