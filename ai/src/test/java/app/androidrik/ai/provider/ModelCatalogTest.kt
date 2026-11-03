package app.androidrik.ai.provider

import app.androidrik.ai.provider.providers.ClaudeProvider
import app.androidrik.ai.provider.providers.GoogleProvider
import app.androidrik.ai.provider.providers.OpenAIProvider
import app.androidrik.ai.provider.providers.chatgpt.ChatGPTProvider
import app.androidrik.ai.provider.providers.grok.GrokProvider
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelCatalogTest {
    private fun client() = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Test fun `explicit empty catalog is complete but missing or malformed collections fail`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val setting = ProviderSetting.OpenAI(baseUrl = server.url("/v1").toString())
            val provider = OpenAIProvider(client())
            server.enqueue(MockResponse(body = """{"data":[]}"""))
            assertTrue(provider.fetchModelCatalog(setting) is CatalogFetchResult.Complete)
            for (body in listOf("{}", "<html>bad gateway</html>", """{"data":null}""")) {
                server.enqueue(MockResponse(body = body))
                assertTrue(provider.fetchModelCatalog(setting) is CatalogFetchResult.Failed)
            }
            server.enqueue(MockResponse(body = """{"data":[{"id":"valid"},{"name":"missing-id"}]}"""))
            val partial = provider.fetchModelCatalog(setting) as CatalogFetchResult.Partial
            assertEquals(listOf("valid"), partial.entries.map { it.modelId })
        }
    }

    @Test fun `HTTP failures are classified without retaining server secrets`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val setting = ProviderSetting.OpenAI(baseUrl = server.url("/v1").toString())
            val provider = OpenAIProvider(client())
            for ((status, kind) in listOf(401 to CatalogErrorKind.AUTHENTICATION, 429 to CatalogErrorKind.RATE_LIMIT, 503 to CatalogErrorKind.SERVER)) {
                server.enqueue(MockResponse(code = status, body = "secret-token-123"))
                val error = (provider.fetchModelCatalog(setting) as CatalogFetchResult.Failed).error
                assertEquals(kind, error.kind)
                assertEquals(status, error.httpStatus)
                assertFalse(error.toString().contains("secret-token"))
            }
            server.enqueue(MockResponse(code = 404))
            assertTrue(provider.fetchModelCatalog(setting) is CatalogFetchResult.Unsupported)
        }
    }

    @Test fun `Google follows page tokens and keeps raw non-chat ids with pinned credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"models":[{"name":"models/chat","supportedGenerationMethods":["generateContent"]},{"name":"models/other"}],"nextPageToken":"cursor-2"}"""))
            server.enqueue(MockResponse(body = """{"models":[{"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""))
            val result = GoogleProvider(client()).fetchModelCatalog(ProviderSetting.Google(
                baseUrl = server.url("/v1beta").toString(), apiKeys = listOf(ApiKeyEntry("first"), ApiKeyEntry("second")))) as CatalogFetchResult.Complete
            assertEquals(listOf("chat", "other", "embed"), result.entries.map { it.modelId })
            assertEquals(listOf("chat", "embed"), result.candidateModels().map { it.modelId })
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("first", first.headers["x-goog-api-key"])
            assertEquals("first", second.headers["x-goog-api-key"])
            assertTrue(second.target.contains("pageToken=cursor-2"))
            assertEquals("no-cache", first.headers["Cache-Control"])
        }
    }

    @Test fun `Google failure is not a complete empty list and a later failure preserves the first page`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = GoogleProvider(client())
            val setting = ProviderSetting.Google(baseUrl = server.url("/v1beta").toString())
            server.enqueue(MockResponse(code = 500))
            assertTrue(provider.fetchModelCatalog(setting) is CatalogFetchResult.Failed)
            server.enqueue(MockResponse(body = """{"models":[{"name":"models/first"}],"nextPageToken":"second"}"""))
            server.enqueue(MockResponse(code = 429))
            val result = provider.fetchModelCatalog(setting) as CatalogFetchResult.Partial
            assertEquals(listOf("first"), result.entries.map { it.modelId })
            assertEquals(429, result.error?.httpStatus)
        }
    }

    @Test fun `Claude uses after_id and repeated cursors cannot become complete`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body = """{"data":[{"id":"first"}],"has_more":true,"last_id":"first"}"""
            server.enqueue(MockResponse(body = body))
            server.enqueue(MockResponse(body = body))
            val result = ClaudeProvider(client()).fetchModelCatalog(ProviderSetting.Claude(baseUrl = server.url("/v1").toString())) as CatalogFetchResult.Partial
            assertEquals(IncompleteReason.PAGINATION, result.reason)
            server.takeRequest()
            assertTrue(server.takeRequest().target.contains("after_id=first"))
        }
    }

    @Test fun `Claude full pagination preserves exact case and removes duplicates`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"data":[{"id":"A"}],"has_more":true,"last_id":"A"}"""))
            server.enqueue(MockResponse(body = """{"data":[{"id":"A"},{"id":"a"}],"has_more":false}"""))
            val result = ClaudeProvider(client()).fetchModelCatalog(ProviderSetting.Claude(baseUrl = server.url("/v1").toString())) as CatalogFetchResult.Complete
            assertEquals(listOf("A", "a"), result.entries.map { it.modelId })
        }
    }

    @Test fun `unknown OpenAI pagination cannot be used as complete evidence`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"data":[{"id":"first"}],"has_more":true}"""))
            assertTrue(OpenAIProvider(client()).fetchModelCatalog(ProviderSetting.OpenAI(baseUrl = server.url("/v1").toString())) is CatalogFetchResult.Partial)
        }
    }

    @Test fun `page and entry limits are partial even with successful HTTP responses`() = runBlocking {
        val scope = CatalogScope("provider", listOf("test"))
        val pages = collectCatalogPages(scope, maxPages = 2) { CatalogPage(listOf(CatalogEntry("x")), next = (it ?: "") + "x") }
        assertEquals(IncompleteReason.LIMIT, (pages as CatalogFetchResult.Partial).reason)
        val entries = collectCatalogPages(scope, maxEntries = 1) { CatalogPage(listOf(CatalogEntry("x"), CatalogEntry("y"))) }
        assertEquals(IncompleteReason.LIMIT, (entries as CatalogFetchResult.Partial).reason)
        assertEquals(1, entries.entries.size)
    }

    @Test fun `cancellation is propagated instead of becoming a catalog failure`() = runBlocking {
        try {
            collectCatalogPages(CatalogScope("p", emptyList())) { throw CancellationException("cancelled") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }

    private fun rewrittenClient(server: MockWebServer) = client().newBuilder().addInterceptor { chain ->
        val original = chain.request()
        val url = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
        chain.proceed(original.newBuilder().url(url).build())
    }.build()

    @Test fun `ChatGPT retains hidden ids but exposes only visible candidates and rejects bad schemas`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val credentials = AccountCredentialProvider { _, _ -> OAuthCredential("token", accountId = "account") }
            val provider = ChatGPTProvider(rewrittenClient(server), credentials)
            server.enqueue(MockResponse(body = """{"models":[{"slug":"visible","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}"""))
            val result = provider.fetchModelCatalog(ProviderSetting.ChatGPT()) as CatalogFetchResult.Complete
            assertEquals(listOf("visible", "hidden"), result.entries.map { it.modelId })
            assertEquals(listOf("visible"), result.candidateModels().map { it.modelId })
            assertEquals("account", server.takeRequest().headers["ChatGPT-Account-ID"])
            server.enqueue(MockResponse(body = "{}"))
            assertTrue(provider.fetchModelCatalog(ProviderSetting.ChatGPT()) is CatalogFetchResult.Failed)
        }
    }

    @Test fun `Grok fixes credentials for both sources and a failing source yields partial`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var credentialReads = 0
            val credentials = AccountCredentialProvider { _, _ -> credentialReads++; OAuthCredential("fixed") }
            val provider = GrokProvider(rewrittenClient(server), credentials)
            server.enqueue(MockResponse(body = """{"data":[{"id":"grok-chat"},{"id":"grok-hidden","hidden":true}]}"""))
            server.enqueue(MockResponse(code = 503))
            val result = provider.fetchModelCatalog(ProviderSetting.Grok()) as CatalogFetchResult.Partial
            assertEquals(1, credentialReads)
            assertEquals(listOf("grok-chat", "grok-hidden"), result.entries.map { it.modelId })
            repeat(2) { assertEquals("Bearer fixed", server.takeRequest().headers["Authorization"]) }
        }
    }
    @Test fun `transport timeout remains failure even if it has no response body`() = runBlocking {
        val result = collectCatalogPages(CatalogScope("p", listOf("test"))) {
            throw java.net.SocketTimeoutException("request url contains secret")
        } as CatalogFetchResult.Failed
        assertEquals(CatalogErrorKind.TIMEOUT, result.error.kind)
        assertFalse(result.error.message.contains("secret"))
    }

    @Test fun `Grok requires both complete catalogs and gives CLI metadata precedence`() {
        val scope = CatalogScope("p", listOf("grok-cli", "grok-api"))
        val cli = CatalogFetchResult.Complete(listOf(CatalogEntry("id", "CLI")), scope)
        val api = CatalogFetchResult.Complete(listOf(CatalogEntry("id", "API"), CatalogEntry("other")), scope)
        val result = app.androidrik.ai.provider.providers.grok.mergeGrokCatalogResults(scope, cli, api) as CatalogFetchResult.Complete
        assertEquals(listOf("CLI", "other"), result.entries.map { it.displayName })
        val failure = CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.NETWORK, "network"))
        assertTrue(app.androidrik.ai.provider.providers.grok.mergeGrokCatalogResults(scope, failure, failure) is CatalogFetchResult.Failed)
    }

    @Test fun `Vertex resource ids and unsupported model kinds are kept for existence checks`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"publisherModels":[{"name":"publishers/google/models/vision"}]}"""
        ) as kotlinx.serialization.json.JsonObject
        val page = parseGoogleCatalog(root, vertex = true)
        assertEquals("vision", page.entries.single().modelId)
        assertFalse(page.entries.single().selectable)
    }

}
