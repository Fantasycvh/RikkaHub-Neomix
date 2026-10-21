package app.androidrik.rikkahub.data.ai

import app.androidrik.common.android.LogEntry
import app.androidrik.common.android.Logging
import app.androidrik.common.android.redacted
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

/**
 * 把每个 HTTP 请求/响应记进 [Logging]（内存 ring + 可选的落盘文件），供设置页「请求日志」排查。
 *
 * 两条硬约束：
 * 1. 凭据不能落日志：头在拦截器侧先 `redacted()`，[Logging.addLog] 入口还会按名 + 值再脱敏一次；
 * 2. 记录动作不能改变请求语义：请求体只在可安全重放时读取，one-shot / duplex body 只记标记
 *    （见 [captureBodyForLog]），否则「开日志」本身就会让请求失败。
 */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // 敏感头在落库前就脱敏（见 common/Logging.kt 的 SENSITIVE_HEADERS）：
        // 内存 UI（RequestLogDetail）与文件 sink 共用同一份 RequestLog，
        // 在源头脱敏确保两个展示面都不会明文暴露 API key。
        // `Logging.addLog` 入口还会再按名脱敏一次（防御其它调用方直接 logRequest），这里保留是为了
        // 让「拦截器自己产出的日志」一眼可读，也便于 `RequestLoggingInterceptorTest` 直接断言。
        val requestHeaders = request.headers.toMap().redacted()
        val requestBody = request.body.captureBodyForLog()

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap().redacted()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    /**
     * 只读地取请求体文本用于日志。
     *
     * **不能无条件 `writeTo`**：`isOneShot()` / `isDuplex()` 的 body 只允许被消费一次
     * （一次性流、HTTP/2 duplex 请求体）。日志为了展示提前写一遍，等真正发到网络层再写时
     * 流已经耗尽/已经动过，请求会直接失败——日志不能改变请求语义。
     * 因此这类 body 不读取，只留标记；其余 body 写失败（例如本地文件被删）也不能把
     * 一个原本能发出的请求变成日志层的异常，故降级为标记。
     */
    private fun RequestBody?.captureBodyForLog(): String? {
        if (this == null) return null
        if (isOneShot()) return ONE_SHOT_BODY_MARKER
        if (isDuplex()) return DUPLEX_BODY_MARKER
        return try {
            val buffer = Buffer()
            writeTo(buffer)
            buffer.readUtf8()
        } catch (_: Exception) {
            UNREADABLE_BODY_MARKER
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }

    private companion object {
        const val ONE_SHOT_BODY_MARKER = "[body not captured: one-shot request]"
        const val DUPLEX_BODY_MARKER = "[body not captured: duplex request]"
        const val UNREADABLE_BODY_MARKER = "[body not captured: unreadable]"
    }
}
