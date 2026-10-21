package app.androidrik.rikkahub.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import java.net.NetworkInterface
import java.net.URI
import java.util.Locale

/** No DNS resolution: only literal device addresses and explicitly registered local names are trusted. */
internal class WebRequestPolicy(
    private val port: Int,
    private val allowedHosts: () -> Set<String>,
) {
    fun allows(hostHeader: String?, origin: String?, fetchSite: String?): Boolean {
        if (fetchSite.equals("cross-site", ignoreCase = true)) return false
        val target = parseAuthority(hostHeader) ?: return false
        if (target.port != port || target.host !in allowedHosts().map(::normalizeHost)) return false
        if (origin == null) return true // Native/CLI clients still have to authenticate.
        val source = runCatching { URI(origin) }.getOrNull() ?: return false
        if (source.scheme != "http" || source.rawUserInfo != null || source.rawQuery != null ||
            source.rawFragment != null || !source.rawPath.isNullOrEmpty()
        ) return false
        val sourceHost = source.host ?: return false
        val sourcePort = if (source.port == -1) 80 else source.port
        return normalizeHost(sourceHost) == target.host && sourcePort == target.port
    }

    private fun parseAuthority(value: String?): Authority? {
        if (value.isNullOrBlank() || value.any { it.isWhitespace() } || '/' in value || '\\' in value) return null
        val uri = runCatching { URI("http://$value") }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.rawPath.isNullOrEmpty()
        ) return null
        return Authority(normalizeHost(host), if (uri.port == -1) 80 else uri.port)
    }

    private data class Authority(val host: String, val port: Int)
}

private fun normalizeHost(host: String): String = host.removePrefix("[").removeSuffix("]")
    .trimEnd('.').lowercase(Locale.ROOT)

internal fun localWebHosts(bindHost: String): Set<String> = buildSet {
    add("localhost")
    add("127.0.0.1")
    add("::1")
    if (bindHost != "0.0.0.0" && bindHost != "::") add(bindHost)
    // Fail closed if interface enumeration is unavailable. Loopback remains usable.
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
            network.inetAddresses.toList().forEach { address ->
                address.hostAddress?.substringBefore('%')?.let(::add)
            }
        }
    }
}

internal fun Application.configureWebRequestSecurity(
    port: Int,
    bindHost: String,
    additionalAllowedHosts: () -> Set<String>,
) {
    val policy = WebRequestPolicy(port) { localWebHosts(bindHost) + additionalAllowedHosts() }
    install(createApplicationPlugin("WebRequestSecurity") {
        onCall { call ->
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("X-Frame-Options", "DENY")
            if (!policy.allows(
                    call.request.headers[HttpHeaders.Host],
                    call.request.headers[HttpHeaders.Origin],
                    call.request.headers["Sec-Fetch-Site"],
                )
            ) {
                call.respondText("Forbidden request origin or host", status = HttpStatusCode.Forbidden)
            }
        }
    })
}
