package app.androidrik.rikkahub.web

import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.web.dto.WebAuthTokenResponse
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test

class WebAuthenticationTest {
    @Test
    fun `empty password cannot issue tokens`() {
        assertNull(WebAuthSession { "" }.issue(""))
        assertNull(WebAuthSession { "   " }.issue("   "))
        assertNull(WebAuthSession { "password" }.issue("incorrect"))
    }

    @Test
    fun `password change clearing password and restart invalidate tokens`() {
        var password = "old-password"
        val session = WebAuthSession { password }
        val token = requireNotNull(session.issue(password)).token
        session.verifier().verify(token)
        password = "new-password"
        assertInvalid(session, token)
        val newToken = requireNotNull(session.issue(password)).token
        session.verifier().verify(newToken)
        password = ""
        assertInvalid(session, newToken)
        assertFalse(session.isConfigured())
        password = "new-password"
        assertInvalid(WebAuthSession { password }, newToken)
    }

    private fun assertInvalid(session: WebAuthSession, token: String) {
        try {
            session.verifier().verify(token)
            fail("Expected token to be revoked")
        } catch (_: JWTVerificationException) { }
    }

    @Test
    fun `explicit invalid authorization header cannot fall back to query token`() {
        assertEquals("token", extractWebAccessToken("bearer token", "fallback"))
        assertNull(extractWebAccessToken("Basic secret", "fallback"))
        assertNull(extractWebAccessToken("Bearer ", "fallback"))
        assertEquals("fallback", extractWebAccessToken(null, "fallback"))
    }

    @Test
    fun `login attempts bounded and reset after monotonic window`() {
        var now = 0L
        val attempts = WebLoginAttempts { now }
        repeat(10) { assertTrue(attempts.allowAttempt()) }
        assertFalse(attempts.allowAttempt())
        now = 59_999_999_999L
        assertFalse(attempts.allowAttempt())
        now++
        assertTrue(attempts.allowAttempt())
    }

    @Test
    fun `all data routes reject anonymous and revoke credentials live`() = testApplication {
        var password = "password"
        val session = WebAuthSession { password }
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            configureWebAuthentication(session)
            routing {
                route("/api") {
                    webAuthRoutes(session)
                    authenticate(WEB_AUTH_PROVIDER) {
                        get("/events") { call.respondText("protected settings") }
                        get("/files") { call.respondText("protected file") }
                    }
                }
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/events").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/files").status)
        val login = client.post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("{\"password\":\"password\"}")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = JsonInstant.decodeFromString<WebAuthTokenResponse>(login.bodyAsText()).token
        assertEquals(HttpStatusCode.OK, client.get("/api/events") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/files?access_token=$token").status)
        password = "changed"
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/events") { bearerAuth(token) }.status)
        password = ""
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/events").status)
    }
}
