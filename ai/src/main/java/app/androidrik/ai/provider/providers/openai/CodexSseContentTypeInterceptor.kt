package app.androidrik.ai.provider.providers.openai

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource

/**
 * 官方 Codex 后端的 SSE 响应有时不带 `Content-Type: text/event-stream`，而 okhttp
 * `EventSources` 的工厂会严格校验 Content-Type，缺失时直接 `Invalid content-type`（流式请求整体失败）。
 *
 * 只对 Codex 端点补头：其他端点的 Content-Type 已正确时原样放行，不被本拦截器掩盖真实错误。
 * 挂载方式见 `ResponseAPI.streamText`：只给订阅模式的 client 副本加，全局 client 与其他 provider 不受影响。
 */
internal class CodexSseContentTypeInterceptor(
    /** 端点判定（测试注入点）：默认只认官方 Codex 端点 */
    private val matches: (HttpUrl) -> Boolean = { it.isCodexEndpoint() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!matches(request.url)) return response
        val contentType = response.body?.contentType()
        if (contentType?.type == "text" && contentType.subtype == "event-stream") return response
        val body = response.body ?: return response
        return response.newBuilder().body(ForcedEventStreamBody(body)).build()
    }

    private class ForcedEventStreamBody(private val delegate: ResponseBody) : ResponseBody() {
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = delegate.source()
    }
}

private fun HttpUrl.isCodexEndpoint(): Boolean =
    host == "chatgpt.com" && encodedPath.startsWith("/backend-api/codex")
