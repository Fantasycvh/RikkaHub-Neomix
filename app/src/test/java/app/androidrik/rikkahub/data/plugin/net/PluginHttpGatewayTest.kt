package app.androidrik.rikkahub.data.plugin.net

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginHttpGateway] 的 JVM 单测。
 *
 * **全程离线**:用 [OkHttpClient] + 应用层 [Interceptor] 伪造响应,拦截器在
 * `RetryAndFollowUpInterceptor` 之前短路,所以既不发 DNS 也不建连接;域名解析通过
 * 构造参数 `resolver` 注入固定答案。故意不引入 MockWebServer(项目没有该测试依赖)。
 *
 * 覆盖的是"拒绝路径"与"协议语义":白名单/内网/端口/scheme/method/请求头/重定向逐跳复检、
 * 响应体上限、状态与响应头投影。
 */
class PluginHttpGatewayTest {

    private val calls = mutableListOf<Request>()

    private fun gateway(respond: (Request) -> Response): PluginHttpGateway {
        val interceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                calls += request
                return respond(request)
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return PluginHttpGateway(client) { listOf(InetAddress.getByName(PUBLIC_IP)) }
    }

    private fun response(
        request: Request,
        code: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        location: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("text/plain; charset=utf-8".toMediaType()))
        if (location != null) builder.header("Location", location)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    // ---- 白名单 / 内网 / 端口 / scheme / method / 请求头:都在发请求之前拒绝 ----

    @Test
    fun `rejects host outside the plugin whitelist`() = runBlocking {
        val result = gateway { response(it) }.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://evil.example.net/steal"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects blocked internal hosts even when whitelisted`() = runBlocking {
        val gateway = gateway { response(it) }
        val blocked = listOf(
            "https://127.0.0.1/",
            "https://10.0.0.8/",
            "https://localhost/",
            "https://192.168.1.2/",
            "https://[::1]/",
        )
        blocked.forEach { url ->
            val result = gateway.request(
                pluginId = "plugin.test",
                allowedHosts = setOf("127.0.0.1", "10.0.0.8", "localhost", "192.168.1.2", "[::1]"),
                request = PluginHttpRequest(url = url),
            )
            assertTrue("$url should be rejected", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects a domain whose dns answer contains an internal address`() = runBlocking {
        val interceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                calls += chain.request()
                return response(chain.request())
            }
        }
        val gateway = PluginHttpGateway(
            OkHttpClient.Builder().addInterceptor(interceptor).build(),
        ) { listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.1.2.3")) }

        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/data"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `reports dns failure as io failure`() = runBlocking {
        val gateway = PluginHttpGateway(
            OkHttpClient.Builder().build(),
        ) { throw UnknownHostException("no.such.host") }

        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/data"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `rejects non http schemes`() = runBlocking {
        val gateway = gateway { response(it) }
        listOf("ftp://api.example.com/x", "file:///etc/passwd", "javascript:alert(1)", "not a url", "").forEach { url ->
            val result = gateway.request(
                pluginId = "plugin.test",
                allowedHosts = setOf("api.example.com"),
                request = PluginHttpRequest(url = url),
            )
            assertTrue("'$url' should be rejected", result.isFailure)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects unsupported http methods`() = runBlocking {
        val gateway = gateway { response(it) }
        listOf("TRACE", "CONNECT", "OPTIONS", "").forEach { method ->
            val result = gateway.request(
                pluginId = "plugin.test",
                allowedHosts = setOf("api.example.com"),
                request = PluginHttpRequest(method = method, url = "https://api.example.com/x"),
            )
            assertTrue("'$method' should be rejected", result.isFailure)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects ports outside 80 443 8443`() = runBlocking {
        val gateway = gateway { response(it) }
        listOf(
            "https://api.example.com:8080/x",
            "http://api.example.com:81/x",
            "https://api.example.com:22/x",
        ).forEach { url ->
            val result = gateway.request(
                pluginId = "plugin.test",
                allowedHosts = setOf("api.example.com"),
                request = PluginHttpRequest(url = url),
            )
            assertTrue("$url should be rejected", result.isFailure)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects urls carrying userinfo`() = runBlocking {
        val result = gateway { response(it) }.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://user:secret@api.example.com/x"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects overrides of host cookie and authorization headers`() = runBlocking {
        val gateway = gateway { response(it) }
        listOf("Host", "host", "Cookie", "cookie", "Authorization", "authorization").forEach { name ->
            val result = gateway.request(
                pluginId = "plugin.test",
                allowedHosts = setOf("api.example.com"),
                request = PluginHttpRequest(
                    url = "https://api.example.com/x",
                    headers = mapOf(name to "attacker-controlled"),
                ),
            )
            assertTrue("header '$name' should be rejected", result.isFailure)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `rejects get requests with a body`() = runBlocking {
        val result = gateway { response(it) }.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(method = "GET", url = "https://api.example.com/x", body = "nope"),
        )
        assertTrue(result.isFailure)
        assertTrue(calls.isEmpty())
    }

    // ---- 重定向:逐跳重新校验 ----

    @Test
    fun `rejects redirect to a host outside the whitelist`() = runBlocking {
        val gateway = gateway { request ->
            response(request, code = 302, location = "https://evil.example.net/steal")
        }
        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/start"),
        )
        assertTrue(result.isFailure)
        assertEquals(1, calls.size)
        assertEquals("api.example.com", calls.single().url.host)
    }

    @Test
    fun `rejects https to http downgrade on redirect`() = runBlocking {
        val gateway = gateway { request ->
            response(request, code = 301, location = "http://api.example.com/next")
        }
        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/start"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, calls.size)
    }

    @Test
    fun `rejects redirect to an internal address even when whitelisted`() = runBlocking {
        val gateway = gateway { request ->
            response(request, code = 302, location = "https://127.0.0.1/internal")
        }
        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com", "127.0.0.1"),
            request = PluginHttpRequest(url = "https://api.example.com/start"),
        )
        assertTrue(result.isFailure)
        assertEquals(1, calls.size)
    }

    @Test
    fun `redirect status decides whether the method is replayed or downgraded to get`() = runBlocking {
        // 303/302:非幂等方法降级为 GET;307:原样重放(POST + body)
        val cases = mapOf(303 to "GET", 302 to "GET", 307 to "POST")
        cases.forEach { (code, expectedMethod) ->
            val seenMethods = mutableListOf<String>()
            var hop = 0
            val gateway = gateway { request ->
                seenMethods += request.method
                hop += 1
                if (hop == 1) {
                    response(request, code = code, location = "/after")
                } else {
                    response(request, code = 200, body = "ok")
                }
            }
            val result = gateway.request(
                pluginId = "plugin.test",
                allowedHosts = setOf("api.example.com"),
                request = PluginHttpRequest(
                    method = "POST",
                    url = "https://api.example.com/submit",
                    body = """{"a":1}""",
                ),
            )
            assertEquals("HTTP $code should succeed", 200, result.getOrThrow().status)
            assertEquals(listOf("POST", expectedMethod), seenMethods)
        }
    }

    @Test
    fun `follows at most three redirects and returns the final response`() = runBlocking {
        var hop = 0
        val gateway = gateway { request ->
            hop += 1
            when (hop) {
                1 -> response(request, code = 302, location = "https://api.example.com/step2")
                2 -> response(request, code = 307, location = "/step3")
                3 -> response(request, code = 308, location = "https://api.example.com:8443/final")
                else -> response(
                    request,
                    code = 200,
                    body = "done",
                    headers = mapOf("X-Hop" to "4"),
                )
            }
        }
        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/start"),
        )
        val response = result.getOrThrow()
        assertEquals(200, response.status)
        assertEquals("done", response.body)
        // 响应头 key 为小写(OkHttp 5 规范形式)
        assertEquals("4", response.headers["x-hop"])
        assertEquals(4, calls.size)
        assertEquals("https", calls.last().url.scheme)
        assertEquals(8443, calls.last().url.port)
    }

    @Test
    fun `rejects a redirect chain longer than three hops`() = runBlocking {
        val gateway = gateway { request ->
            response(request, code = 302, location = "https://api.example.com/loop")
        }
        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/start"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        // 初始请求 + 跟随 3 跳;第 4 次响应仍是重定向 ⇒ 拒绝,不再发第 5 次
        assertEquals(4, calls.size)
    }

    // ---- 成功路径与响应投影 ----

    @Test
    fun `success path returns status headers and body`() = runBlocking {
        var seen: Request? = null
        val gateway = gateway { request ->
            seen = request
            response(
                request,
                code = 201,
                body = "created",
                headers = mapOf("X-Test" to "yes", "Content-Type" to "application/json"),
            )
        }
        val result = gateway.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(
                method = "POST",
                url = "https://api.example.com:8443/items",
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"name":"rikka"}""",
            ),
        )
        val response = result.getOrThrow()
        val captured = checkNotNull(seen) { "拦截器没有收到请求" }
        assertEquals(201, response.status)
        assertEquals("created", response.body)
        // 响应头 key 为小写(OkHttp 5 规范形式)
        assertEquals("yes", response.headers["x-test"])
        assertEquals("application/json", response.headers["content-type"])
        assertEquals("POST", captured.method)
        assertEquals("application/json", captured.header("Content-Type"))
        assertEquals(8443, captured.url.port)
    }

    @Test
    fun `rejects response bodies larger than one mib`() = runBlocking {
        val oversized = "x".repeat((1024 * 1024) + 1)
        val result = gateway { request -> response(request, code = 200, body = oversized) }.request(
            pluginId = "plugin.test",
            allowedHosts = setOf("api.example.com"),
            request = PluginHttpRequest(url = "https://api.example.com/big"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("1 MiB"))
    }

    private companion object {
        /** 公网地址字面量:测试 resolver 固定返回它,保证不触真实 DNS。 */
        const val PUBLIC_IP = "93.184.216.34"
    }
}
