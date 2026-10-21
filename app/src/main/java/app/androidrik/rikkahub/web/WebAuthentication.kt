package app.androidrik.rikkahub.web

import app.androidrik.rikkahub.web.dto.ErrorResponse
import app.androidrik.rikkahub.web.dto.WebAuthTokenRequest
import app.androidrik.rikkahub.web.dto.WebAuthTokenResponse
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date

internal const val WEB_AUTH_PROVIDER = "auth-jwt"
private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 12L * 60 * 60 * 1000

/** Random per-server key prevents offline password guessing from a captured JWT. */
internal class WebAuthSession(private val password: () -> String) {
    private val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun algorithm(secret: String): Algorithm = Algorithm.HMAC256(
        MessageDigest.getInstance("SHA-256").digest(nonce + secret.toByteArray(Charsets.UTF_8))
    )

    fun verifier(): JWTVerifier = JWT.require(algorithm(password()))
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .build()

    fun isConfigured(): Boolean = password().isNotBlank()

    fun issue(suppliedPassword: String): WebAuthTokenResponse? {
        val currentPassword = password()
        if (currentPassword.isBlank() || !MessageDigest.isEqual(
                suppliedPassword.toByteArray(Charsets.UTF_8), currentPassword.toByteArray(Charsets.UTF_8)
            )
        ) return null
        val now = System.currentTimeMillis()
        val expiresAt = now + WEB_JWT_TTL_MILLIS
        val token = JWT.create()
            .withIssuer(WEB_JWT_ISSUER)
            .withAudience(WEB_JWT_AUDIENCE)
            .withSubject(WEB_JWT_SUBJECT)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(expiresAt))
            .sign(algorithm(currentPassword))
        return WebAuthTokenResponse(token = token, expiresAt = expiresAt)
    }
}

internal fun Application.configureWebAuthentication(session: WebAuthSession) {
    install(Authentication) {
        jwt(WEB_AUTH_PROVIDER) {
            realm = "rikkahub-web-api"
            verifier { session.verifier() }
            authHeader { call ->
                extractWebAccessToken(
                    call.request.headers[HttpHeaders.Authorization],
                    call.request.queryParameters["access_token"],
                )?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            validate { credential ->
                if (session.isConfigured() && credential.payload.subject == WEB_JWT_SUBJECT) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", 401))
            }
        }
    }
}

internal fun Route.webAuthRoutes(session: WebAuthSession) {
    // A single bounded limiter, not an unbounded map keyed by attacker-controlled addresses.
    val attempts = WebLoginAttempts()
    post("/auth/token") {
        if (!attempts.allowAttempt()) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many login attempts; try again later", 429))
            return@post
        }
        if (!session.isConfigured()) throw UnauthorizedException("Access password is not configured")
        val request = call.receive<WebAuthTokenRequest>()
        val token = session.issue(request.password) ?: throw UnauthorizedException("Invalid password")
        call.respond(HttpStatusCode.OK, token)
    }
}

internal class WebLoginAttempts(private val now: () -> Long = System::nanoTime) {
    private var windowStart = now()
    private var count = 0

    @Synchronized
    fun allowAttempt(): Boolean {
        val current = now()
        if (current - windowStart >= 60_000_000_000L) {
            windowStart = current
            count = 0
        }
        if (count >= 10) return false
        count++
        return true
    }
}

internal fun extractWebAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    // Never fall back to a URL token when an explicitly provided Authorization header is invalid.
    if (authorizationHeader != null) {
        if (!authorizationHeader.startsWith("Bearer ", ignoreCase = true)) return null
        return authorizationHeader.substring(7).trim().takeIf { it.isNotEmpty() }
    }
    // Media elements cannot attach headers. Referrer-Policy: no-referrer prevents URL token leaks.
    return queryToken?.trim()?.takeIf { it.isNotEmpty() }
}
