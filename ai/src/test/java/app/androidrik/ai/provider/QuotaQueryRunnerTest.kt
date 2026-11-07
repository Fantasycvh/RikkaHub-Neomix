package app.androidrik.ai.provider

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API Key 类提供商的额度查询执行器：单接口（上游形态）与多接口+表达式（本仓扩展）。
 *
 * 用自定义 interceptor 模拟服务端，锁定「模板渲染 → 请求头合并 → 取值/表达式求值 → 数字格式化」。
 */
class QuotaQueryRunnerTest {
    private val requests: MutableList<Request> = java.util.concurrent.CopyOnWriteArrayList()

    private fun client(handler: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val (code, body) = handler(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun openAiSetting(
        baseUrl: String = "https://api.example.com/v1",
        quotaQuery: QuotaQuery,
        customHeaders: List<CustomHeader> = emptyList(),
    ) = ProviderSetting.OpenAI(
        baseUrl = baseUrl,
        customHeaders = customHeaders,
        quotaQuery = quotaQuery,
    )

    @Test
    fun `disabled query returns null without any request`() = runBlocking {
        val result = client { 500 to "" }.runQuotaQuery(
            openAiSetting(quotaQuery = QuotaQuery(enabled = false)),
        ) { "sk-1" }
        assertNull(result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `single endpoint renders url and headers with one key and formats number`() = runBlocking {
        val setting = openAiSetting(
            customHeaders = listOf(CustomHeader("Authorization", "provider"), CustomHeader("X-Extra", "1")),
            quotaQuery = QuotaQuery(
                enabled = true,
                url = "{{baseUrl}}/credits?key={{ apiKey }}",
                headers = listOf(CustomHeader("Authorization", "Bearer {{apiKey}}"), CustomHeader(" ", "ignored")),
                resultPath = "data.total_usage",
            ),
        )
        var keys = 0
        val quota = client { _ -> 200 to """{"data":{"total_usage":12.5}}""" }
            .runQuotaQuery(setting) { keys++; "sk-1" }

        assertEquals(1, keys)
        assertEquals("12.50", (quota as ProviderQuota.Balance).value)
        val request = requests.single()
        assertEquals("https://api.example.com/v1/credits?key=sk-1", request.url.toString())
        // 查询配置的 Authorization 覆盖提供商级的
        assertEquals("Bearer sk-1", request.header("Authorization"))
        // 提供商级其它头仍保留；空白名头被忽略
        assertEquals("1", request.header("X-Extra"))
        assertEquals(0, request.headers(" ").size)
    }

    @Test
    fun `post query sends rendered json body`() = runBlocking {
        val setting = openAiSetting(
            quotaQuery = QuotaQuery(
                enabled = true,
                method = QuotaQueryMethod.POST,
                url = "{{baseUrl}}/balance",
                body = """{"key":"{{apiKey}}"}""",
                resultPath = "balance",
            ),
        )
        val quota = client { _ -> 200 to """{"balance":3}""" }.runQuotaQuery(setting) { "sk-1" }

        val request = requests.single()
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("POST", request.method)
        assertEquals("""{"key":"sk-1"}""", buffer.readUtf8())
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertEquals("3.00", (quota as ProviderQuota.Balance).value)
    }

    @Test
    fun `non numeric value passes through unchanged`() = runBlocking {
        val setting = openAiSetting(
            quotaQuery = QuotaQuery(enabled = true, url = "{{baseUrl}}/quota", resultPath = "data.tier"),
        )
        val quota = client { _ -> 200 to """{"data":{"tier":"free"}}""" }.runQuotaQuery(setting) { "k" }
        assertEquals("free", (quota as ProviderQuota.Balance).value)
    }

    @Test
    fun `multi source mode evaluates expression over source variables`() = runBlocking {
        val setting = openAiSetting(
            quotaQuery = QuotaQuery(
                enabled = true,
                sources = listOf(
                    QuotaSource(name = "quota", apiPath = "/api/user/self", resultPath = "data.quota"),
                    QuotaSource(name = "usage", apiPath = "/api/user/self", resultPath = "data.used"),
                ),
                expression = "quota - usage",
            ),
        )
        val quota = client { request ->
            val path = request.url.encodedPath
            when {
                path.startsWith("/v1/api/user") -> 200 to """{"data":{"quota":100,"used":30}}"""
                else -> 404 to "{}"
            }
        }.runQuotaQuery(setting) { "k" }

        assertEquals("70.00", (quota as ProviderQuota.Balance).value)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.toString().startsWith("https://api.example.com/v1/api/user/self") })
    }

    @Test
    fun `multi source absolute url is used as is`() = runBlocking {
        val setting = openAiSetting(
            quotaQuery = QuotaQuery(
                enabled = true,
                sources = listOf(
                    QuotaSource(name = "q", apiPath = "https://other.example.com/quota", resultPath = "q"),
                ),
                expression = "q",
            ),
        )
        client { _ -> 200 to """{"q":3.14159}""" }.runQuotaQuery(setting) { "k" }
        assertEquals("https://other.example.com/quota", requests.single().url.toString())
    }

    @Test
    fun `http error is surfaced with status and body`() = runBlocking {
        val setting = openAiSetting(
            quotaQuery = QuotaQuery(enabled = true, url = "{{baseUrl}}/credits", resultPath = "x"),
        )
        val error = runCatching {
            client { _ -> 401 to """{"error":"bad key"}""" }.runQuotaQuery(setting) { "k" }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("401"))
        assertTrue(error.message!!.contains("bad key"))
    }

    @Test
    fun `supportsQuota follows the query switch and the account sign in`() {
        assertFalse(ProviderSetting.OpenAI().supportsQuota)
        assertTrue(ProviderSetting.OpenAI(quotaQuery = QuotaQuery(enabled = true)).supportsQuota)
        assertFalse(ProviderSetting.ChatGPT().supportsQuota)
        assertTrue(ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "a")).supportsQuota)
        assertFalse(ProviderSetting.Grok().supportsQuota)
        assertTrue(ProviderSetting.Grok(credential = OAuthCredential(accessToken = "a")).supportsQuota)
    }

    @Test
    fun `unknown template variable fails before sending`() = runBlocking {
        val setting = openAiSetting(
            quotaQuery = QuotaQuery(enabled = true, url = "{{baseUrl}}/{{privateKey}}", resultPath = "x"),
        )
        val error = runCatching { client { 200 to "{}" }.runQuotaQuery(setting) { "k" } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(requests.isEmpty())
    }
}
