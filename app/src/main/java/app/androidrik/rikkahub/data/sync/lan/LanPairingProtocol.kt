package app.androidrik.rikkahub.data.sync.lan

import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

internal const val LAN_PROTOCOL = 1
internal const val PAIR_MAX_BYTES = 2_048
internal const val PAIR_WINDOW_MILLIS = 120_000L
private const val PIN_LOCK_MILLIS = 60_000L
private val fingerprintPattern = Regex("[0-9a-f]{64}")
private val pinPattern = Regex("[0-9]{6}")
private val random = SecureRandom()

internal class LanInvite(val address: Inet4Address, val port: Int, val fingerprint: String, val pin: String) {
    val code: String get() = "rikka-lan:$LAN_PROTOCOL:${address.hostAddress}:$port:$fingerprint:$pin"

    companion object {
        fun parse(code: String): LanInvite? {
            val fields = code.trim().takeIf { it.length <= 110 }?.split(':') ?: return null
            if (fields.size != 6 || fields[0] != "rikka-lan" || fields[1] != LAN_PROTOCOL.toString()) return null
            val address = privateAddress(fields[2]) ?: return null
            val port = fields[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fingerprint = fields[4].lowercase(Locale.ROOT).takeIf(fingerprintPattern::matches) ?: return null
            val pin = fields[5].takeIf(pinPattern::matches) ?: return null
            return LanInvite(address, port, fingerprint, pin)
        }

        fun randomPin(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
    }
}

internal fun privateAddress(value: String): Inet4Address? {
    val octets = value.split('.')
    if (octets.size != 4) return null
    val bytes = octets.map { raw ->
        val int = raw.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == raw } ?: return null
        int.toByte()
    }.toByteArray()
    return (InetAddress.getByAddress(bytes) as? Inet4Address)?.takeIf { it.isSiteLocalAddress }
}

internal class LanPinGate(private val pin: String, private val expiresAt: Long) {
    private var failed = 0
    private var lockedUntil = 0L

    fun verify(value: String, now: Long): Int {
        if (now >= expiresAt) return 410
        if (now < lockedUntil) return 429
        if (MessageDigest.isEqual(value.toByteArray(), pin.toByteArray())) {
            failed = 0
            return 200
        }
        if (++failed == 5) {
            failed = 0
            lockedUntil = now + PIN_LOCK_MILLIS
            return 429
        }
        return 403
    }
}

@Serializable
internal class LanPairRequest(val protocol: Int, val pin: String, val deviceId: String, val name: String)

@Serializable
internal class LanPairResponse(val protocol: Int, val token: String, val deviceId: String, val name: String)

@Serializable
internal data class LanPeer(
    val name: String,
    val certSha256: String,
    val encryptedToken: String,
    val lastEndpoints: List<String> = emptyList(),
    val lastSyncAt: Long? = null,
    val protocolVersion: Int = LAN_PROTOCOL,
)

internal fun newPairToken(): String = ByteArray(32).also(random::nextBytes).let {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it)
}

internal fun validPairToken(token: String): Boolean = token.length == 43 &&
    token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

internal fun validLanName(name: String): Boolean = name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl)
