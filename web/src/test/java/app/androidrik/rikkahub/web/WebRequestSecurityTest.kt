package app.androidrik.rikkahub.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test

class WebRequestSecurityTest {
    private val policy = WebRequestPolicy(8080) { setOf("localhost", "127.0.0.1", "::1", "192.168.1.12", "rikka.local") }

    @Test
    fun `same origin and native clients are accepted`() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]", "192.168.1.12", "rikka.local")) {
            assertTrue(host, policy.allows("$host:8080", "http://$host:8080", "same-origin"))
            assertTrue(host, policy.allows("$host:8080", null, null))
        }
    }

    @Test
    fun `host origin scheme port and fetch metadata fail closed`() {
        for (host in listOf(null, "evil.example:8080", "localhost:80", "user@localhost:8080",
            "localhost:8080/", "localhost:8080?x", "localhost:8080#x", "localhost:8080 ", "localhost:8080\\evil")) {
            assertFalse("host=$host", policy.allows(host, null, null))
        }
        for (origin in listOf("null", "https://localhost:8080", "http://evil.example:8080",
            "http://localhost:3000", "http://127.0.0.1:8080", "http://localhost:8080/",
            "http://user@localhost:8080", "http://localhost:8080?q", "http://localhost:8080#x")) {
            assertFalse(origin, policy.allows("localhost:8080", origin, null))
        }
        assertFalse(policy.allows("localhost:8080", null, "cross-site"))
    }

    @Test
    fun `default http port is compared correctly`() {
        val defaultPort = WebRequestPolicy(80) { setOf("localhost") }
        assertTrue(defaultPort.allows("localhost", "http://localhost", null))
        assertFalse(policy.allows("localhost", "http://localhost", null))
    }

    @Test
    fun `local mdns hostname can be registered after startup`() {
        var extra = emptySet<String>()
        val dynamic = WebRequestPolicy(8080) { extra }
        assertFalse(dynamic.allows("phone.local:8080", null, null))
        extra = setOf("phone.local")
        assertTrue(dynamic.allows("phone.local:8080", null, null))
    }

    @Test
    fun `plugin blocks requests before handlers run and does not grant cors`() = testApplication {
        var hits = 0
        application {
            configureWebRequestSecurity(80, "127.0.0.1") { emptySet() }
            routing {
                get("/api/secret") { hits++; call.respondText("secret") }
            }
        }
        val evil = client.get("/api/secret") { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, evil.status)
        assertFalse(evil.bodyAsText().contains("secret"))
        assertNull(evil.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(0, hits)
        val rebound = client.get("/api/secret") { header(HttpHeaders.Host, "evil.example") }
        assertEquals(HttpStatusCode.Forbidden, rebound.status)
        assertEquals(0, hits)
        val preflight = client.options("/api/secret") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(HttpStatusCode.Forbidden, preflight.status)
        val valid = client.get("/api/secret") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
        }
        assertEquals(HttpStatusCode.OK, valid.status)
        assertEquals("secret", valid.bodyAsText())
        assertEquals("no-referrer", valid.headers["Referrer-Policy"])
        assertEquals(1, hits)
    }
}
