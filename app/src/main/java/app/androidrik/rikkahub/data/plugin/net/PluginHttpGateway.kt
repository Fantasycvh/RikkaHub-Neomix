package app.androidrik.rikkahub.data.plugin.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

private const val TAG = "PluginHttpGateway"

/**
 * JS 插件的**唯一**出站通道(受控 HTTP 网关)。
 *
 * 调用点:`JsPluginRuntime` 的 `rikka.http()`,已按插件清单的 `networkHosts` 传入白名单。
 * 这个类不信任调用方传进来的任何东西,逐项重做校验:
 *
 * 1. 只允许 `http`/`https`,方法只允许 GET/POST/PUT/PATCH/DELETE/HEAD;
 * 2. URL 无 userinfo,端口只允许 80/443/8443;
 * 3. host 必须命中白名单([UrlPolicy.hostAllowed])且不是内网/保留地址([UrlPolicy.isBlockedHost]);
 * 4. 域名先解析,解析出的**任一** IP 命中内网/保留段即拒绝(DNS rebinding 基础防护);
 *    连接层再挂 [GuardedDns] 复核一次,避免"校验时是公网、连接时变内网";
 * 5. 手动跟随重定向(最多 3 跳),**每一跳**重做 2-4 的校验,并禁止 https → http 降级;
 * 6. 连接/读/写各 10s,响应体上限 1 MiB;
 * 7. Host/Cookie/Authorization 三个请求头不允许插件覆盖(直接拒绝)。
 *
 * 所有失败都折叠成 [Result.failure](IllegalArgumentException / IOException 等),
 * 绝不向调用方抛异常(协程取消除外)。
 *
 * @param callFactory 生产由 Koin 注入共享的 `OkHttpClient`;测试可注入替身。
 *   若它是 [OkHttpClient],网关会派生一个**独立**客户端:关掉自动重定向(改手动)、
 *   收紧超时、挂 DNS 复核;原有拦截器/代理/连接池等设置保留。
 * @param resolver 域名解析注入点。默认系统解析;单测替换成固定答案,路径测试不碰真实网络。
 */
class PluginHttpGateway(
    private val callFactory: Call.Factory,
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {

    private val baseClient: OkHttpClient? = callFactory as? OkHttpClient

    private val client: Call.Factory = baseClient?.newBuilder()
        ?.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        ?.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        ?.writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 重定向必须由本网关手动跟随,才能逐跳重做白名单/内网校验
        ?.followRedirects(false)
        ?.followSslRedirects(false)
        // 连接层的地址复核:即使校验与连接之间发生 DNS rebinding,也不放行内网地址
        ?.dns(GuardedDns())
        ?.build()
        ?: callFactory

    /**
     * 执行一次受控请求。
     *
     * @param pluginId 仅用于日志/错误归属,不参与放行判断。
     * @param allowedHosts 插件清单声明的 `networkHosts`;空集 = 全部拒绝。
     */
    suspend fun request(
        pluginId: String,
        allowedHosts: Set<String>,
        request: PluginHttpRequest,
    ): Result<PluginHttpResponse> = withContext(Dispatchers.IO) {
        try {
            Result.success(execute(allowedHosts, request))
        } catch (cancelled: CancellationException) {
            // 协程取消不是"请求失败",不能折叠进 Result(调用方靠它判断停止)
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "plugin $pluginId http request failed: ${error.javaClass.simpleName}: ${error.message}")
            Result.failure(error)
        }
    }

    private fun execute(allowedHosts: Set<String>, spec: PluginHttpRequest): PluginHttpResponse {
        var method = normalizeMethod(spec.method)
        validateHeaders(spec.headers)

        var url = validateUrl(spec.url, allowedHosts, previous = null)
        var body = spec.body
        var redirects = 0

        while (true) {
            val call = newCall(url, method, spec.headers, body)
            val response = call.execute()
            try {
                val location = if (response.code in REDIRECT_CODES) response.header("Location") else null
                if (location == null) return readResponse(response)

                if (redirects >= MAX_REDIRECTS) {
                    throw IOException("重定向超过 $MAX_REDIRECTS 跳,已中止: $url")
                }
                val next = url.resolve(location)
                    ?: throw IllegalArgumentException("非法重定向地址: $location")
                // 每一跳都重新做完整校验(白名单 / 内网 / 端口 / 无 userinfo / DNS / 降级)
                validateUrl(next.toString(), allowedHosts, previous = url)
                redirects += 1

                // 浏览器语义:303 一律 GET;301/302 的非 GET/HEAD 降级为 GET(丢弃 body);307/308 原样重放
                if (response.code == 303 && method != "HEAD") {
                    method = "GET"
                    body = null
                } else if (response.code in 301..302 && method != "GET" && method != "HEAD") {
                    method = "GET"
                    body = null
                }
                url = next
            } finally {
                response.close()
            }
        }
    }

    // ---- 请求构造 ----

    private fun newCall(url: HttpUrl, method: String, headers: Map<String, String>, body: String?): Call {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.method(method, requestBody(method, headers, body)).build())
        if (baseClient == null) {
            // 非 OkHttpClient 工厂没法配连接/读写超时,退化为整体调用上限(3 个阶段各 10s)
            runCatching { call.timeout().timeout(TOTAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        }
        return call
    }

    private fun requestBody(method: String, headers: Map<String, String>, body: String?): RequestBody? =
        when (method) {
            "GET", "HEAD" -> {
                require(body == null) { "$method 请求不允许携带 body" }
                null
            }
            "DELETE" -> body?.toRequestBody(contentType(headers))
            else -> (body ?: "").toRequestBody(contentType(headers))
        }

    private fun contentType(headers: Map<String, String>): MediaType? =
        headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.let { runCatching { it.toMediaTypeOrNull() }.getOrNull() }

    // ---- 校验 ----

    private fun normalizeMethod(raw: String): String {
        val method = raw.trim().uppercase(Locale.ROOT)
        require(method in ALLOWED_METHODS) {
            "不支持的请求方法 '${raw.trim()}'(只允许 ${ALLOWED_METHODS.joinToString("/")})"
        }
        return method
    }

    private fun validateHeaders(headers: Map<String, String>) {
        headers.keys.forEach { name ->
            require(name.lowercase(Locale.ROOT) !in FORBIDDEN_HEADER_NAMES) {
                "不允许插件覆盖请求头 '$name'"
            }
        }
    }

    private fun validateUrl(rawUrl: String, allowedHosts: Set<String>, previous: HttpUrl?): HttpUrl {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "URL 不能为空" }
        val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("非法 URL: $rawUrl")
        require(url.scheme == "http" || url.scheme == "https") { "只允许 http/https: $rawUrl" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "URL 不允许携带 userinfo(用户名/密码)" }
        require(url.port in ALLOWED_PORTS) {
            "端口 ${url.port} 不允许(只允许 ${ALLOWED_PORTS.joinToString("/")})"
        }
        if (previous != null) {
            require(!(previous.isHttps && !url.isHttps)) { "禁止从 https 降级到 http: $url" }
        }

        val host = url.host
        require(host.isNotEmpty()) { "URL 缺少 host: $rawUrl" }
        require(UrlPolicy.hostAllowed(host, allowedHosts)) { "host '$host' 不在插件的 networkHosts 白名单内" }
        require(!UrlPolicy.isBlockedHost(host)) { "host '$host' 指向内网/保留地址,已拒绝" }

        // 解析并复核每个地址:任一命中内网/保留段即拒绝
        resolveChecked(host)
        return url
    }

    /** 解析 + 内网复核;连接层的 [GuardedDns] 复用同一逻辑。 */
    private fun resolveChecked(host: String): List<InetAddress> {
        val addresses = try {
            resolver(host)
        } catch (unknown: UnknownHostException) {
            throw IOException("域名解析失败: $host", unknown)
        } catch (io: IOException) {
            throw io
        }
        require(addresses.isNotEmpty()) { "域名解析无结果: $host" }
        val blocked = addresses.firstOrNull { UrlPolicy.isBlockedAddress(it) }
        if (blocked != null) {
            throw IllegalArgumentException("host '$host' 解析到内网/保留地址 ${blocked.hostAddress},已拒绝")
        }
        return addresses
    }

    // ---- 响应读取 ----

    private fun readResponse(response: Response): PluginHttpResponse {
        val body: ResponseBody? = response.body
        val bytes = body?.let { readBodyLimited(it) } ?: ByteArray(0)
        val charset = body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return PluginHttpResponse(
            status = response.code,
            headers = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") },
            body = bytes.toString(charset),
        )
    }

    private fun readBodyLimited(body: ResponseBody): ByteArray {
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw IOException("响应体超过 1 MiB 上限($declaredLength 字节),已中止")
        }
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw IOException("响应体超过 1 MiB 上限,已中止")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    /** 连接层 DNS:与预检用同一 resolver,并再挡一次内网地址(DNS rebinding)。 */
    private inner class GuardedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolveChecked(hostname)
    }

    private companion object {
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val ALLOWED_PORTS = setOf(80, 443, 8443)
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        val FORBIDDEN_HEADER_NAMES = setOf("host", "cookie", "authorization")

        const val MAX_REDIRECTS = 3
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 10L
        const val WRITE_TIMEOUT_SECONDS = 10L
        const val TOTAL_TIMEOUT_MILLIS = 30_000L
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
