package app.androidrik.workspace.ssh

import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.IDN
import java.security.MessageDigest
import java.util.Base64

enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY,
}

/** 已信任的主机公钥 */
@Serializable
data class SshHostKey(
    /** 算法名, 如 ssh-ed25519 */
    val type: String,
    /** Base64 编码的公钥 blob */
    val key: String,
) {
    /** OpenSSH 风格的 SHA256 指纹 */
    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key))
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
}

/** SSH 跳板机 (ProxyJump) 的连接参数; 只作为目标机的 TCP 通道, 目标机指纹照常单独校验 */
data class SshJumpConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    /** 为 null 时首次连接跳板机会抛出 [SshUnknownHostKeyException] (isJumpHost = true) */
    val hostKey: SshHostKey? = null,
) {
    val address: String get() = "$username@$host:$port"
    override fun toString(): String = "SshJumpConfig(address=$address, authType=$authType, hostKey=${hostKey?.fingerprint})"
}

data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    /** OpenSSH / PEM 格式的私钥文本 */
    val privateKey: String? = null,
    val passphrase: String? = null,
    /** 远程工作目录, 必须是绝对路径 */
    val remoteDir: String,
    /** 为 null 时首次连接会抛出 [SshUnknownHostKeyException], 由用户确认指纹后再连 */
    val hostKey: SshHostKey? = null,
    val jump: SshJumpConfig? = null,
) {
    val address: String
        get() = "$username@$host:$port"

    // 不让凭据出现在日志和异常信息里
    override fun toString(): String =
        "SshConnectionConfig(address=$address, authType=$authType, remoteDir=$remoteDir, hostKey=${hostKey?.fingerprint}, jump=${jump?.address})"
}

/** 测试连接的结果 */
data class SshProbeResult(
    val hostKey: SshHostKey,
    val homeDir: String,
    val shell: String,
    val remoteDirExists: Boolean,
)

/** 服务器的主机密钥尚未被信任, 需要用户确认 [hostKey] 的指纹 */
class SshUnknownHostKeyException(val hostKey: SshHostKey, val isJumpHost: Boolean = false) :
    IOException("Unknown SSH host key ${hostKey.type} ${hostKey.fingerprint}")

/** 服务器的主机密钥与已信任的不一致, 可能遭遇中间人攻击, 也可能是服务器重装过 */
class SshHostKeyChangedException(val expected: SshHostKey, val actual: SshHostKey, val isJumpHost: Boolean = false) :
    IOException("SSH host key changed: expected ${expected.fingerprint}, got ${actual.type} ${actual.fingerprint}")

/** Convert IDN names for JSch/DNS; preserve ASCII hosts, IPv6 literals and invalid input as before. */
internal fun normalizeSshHost(host: String): String {
    var allAscii = true
    for (ch in host) {
        if (ch.code < 33 || ch.code > 126) {
            allAscii = false
            break
        }
    }
    if (allAscii) return host
    return try {
        IDN.toASCII(host)
    } catch (_: IllegalArgumentException) {
        host
    }
}
