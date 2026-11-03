package app.androidrik.workspace.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import app.androidrik.workspace.StreamCollector
import app.androidrik.workspace.StreamWriter
import app.androidrik.workspace.WorkspaceCommandResult
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

/**
 * 一条 SSH 连接: 命令走 exec channel, 文件操作复用同一个 SFTP channel。
 *
 * Session 本身允许并发开多个 channel; SFTP channel 不是线程安全的, 由 [sftp] 串行化。
 * 阻塞操作响应线程中断, 调用方可以用中断取消卡在网络上的操作。
 */
internal class SshConnection private constructor(
    val config: SshConnectionConfig,
    private val session: Session,
    val hostKey: SshHostKey,
    private val channelTimeoutMillis: Int,
    private val jumpSession: Session? = null,
    private val proxy: JumpChannelProxy? = null,
) : Closeable {
    private val sftpLock = ReentrantLock()
    private var sftpChannel: ChannelSftp? = null
    private val ensuredDirs = ConcurrentHashMap.newKeySet<String>()

    val isConnected: Boolean
        get() = session.isConnected

    // lazy 在初始化抛异常时不会缓存结果, 连接恢复后下次访问会重新获取
    val homeDir: String by lazy { sftp { it.home } }

    val shell: String by lazy { detectShell() }

    fun <T> sftp(block: (ChannelSftp) -> T): T {
        // 可中断地等锁: 前一个操作卡在网络上时, 被取消的等待方能及时返回
        sftpLock.lockInterruptibly()
        try {
            val channel = sftpChannel?.takeIf { it.isConnected } ?: openSftpChannel().also { sftpChannel = it }
            try {
                return block(channel)
            } catch (e: Exception) {
                // 被打断的请求的回应可能稍后才到, 继续用这个 channel 会读到错位的回应, 关掉下次重开
                if (e.isInterruption()) {
                    channel.disconnect()
                    sftpChannel = null
                }
                throw e
            }
        } finally {
            sftpLock.unlock()
        }
    }

    private fun openSftpChannel(): ChannelSftp {
        val channel = openChannel { session.openChannel("sftp") as ChannelSftp }
        connectChannel(step = { openChannel { channel.connect(channelTimeoutMillis) } }, onAbandoned = channel::disconnect)
        return channel
    }

    /**
     * 在后台连接 channel, 被取消时放弃等待: 之后连上了就用 [onAbandoned] 关掉 channel; 失败了则断开整条连接。
     * 失败时 JSch 不会给服务器发 channel close, 远端 channel 可能已经开着, 没被取消时连接池收到异常后会断开连接,
     * 被取消后没有人处理这个异常, 只能在这里断开。
     */
    private fun connectChannel(step: () -> Unit, onAbandoned: () -> Unit) {
        awaitInterruptibly(step, onAbandoned = { onAbandoned() }, onAbandonedFailure = ::close)
    }

    /** 确保远程目录存在, 每条连接对同一目录只检查一次 */
    fun ensureDir(dir: String) {
        if (dir in ensuredDirs) return
        sftp { it.mkdirs(dir) }
        ensuredDirs += dir
    }

    fun exec(command: String, timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
        val channel = openChannel { session.openChannel("exec") as ChannelExec }
        channel.setCommand(command)
        // 输入流必须在 connect 之前取, 否则开头的输出会丢
        val stdoutStream = channel.inputStream
        val stderrStream = channel.extInputStream
        channel.connectExec()
        val stdout = StreamCollector(stdoutStream)
        val stderr = StreamCollector(stderrStream)
        // 没有 stdin 时立即发送 EOF, 避免按 isatty 判断的工具一直等待输入
        val stdinWriter = if (stdin != null) {
            StreamWriter(channel.outputStream, stdin)
        } else {
            runCatching { channel.outputStream.close() }
            null
        }
        try {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!channel.isClosed && System.nanoTime() < deadline) {
                Thread.sleep(EXEC_POLL_INTERVAL_MS)
            }
            val finished = channel.isClosed
            if (!finished) {
                channel.kill()
            }
            stdinWriter?.join(1_000)
            stdout.join(1_000)
            stderr.join(1_000)
            return WorkspaceCommandResult(
                exitCode = if (finished) channel.exitStatus else -1,
                stdout = stdout.text(),
                stderr = stderr.text(),
                timedOut = !finished,
                truncated = stdout.truncated || stderr.truncated,
            )
        } catch (e: InterruptedException) {
            // 协程取消时 runInterruptible 会中断这里的等待, 关掉 channel 避免命令继续执行
            channel.kill()
            stdinWriter?.join(1_000)
            stdout.join(1_000)
            stderr.join(1_000)
            throw e
        } finally {
            channel.disconnect()
        }
    }

    /** 在带 PTY 的 exec channel 里运行 [command], [onClose] 在 shell 关闭时调用 */
    fun openShell(command: String, columns: Int, rows: Int, onClose: () -> Unit): SshShell {
        val channel = openChannel { session.openChannel("exec") as ChannelExec }
        channel.setCommand(command)
        channel.setPty(true)
        channel.setPtyType(TERMINAL_TYPE, columns, rows, 0, 0)
        channel.setTerminalMode(TERMINAL_MODES)
        // 输入流必须在 connect 之前取, 否则开头的输出会丢
        val input = channel.inputStream
        val output = channel.outputStream
        // 连接失败时 JSch 自己会清理; 被取消时后台可能还在打开, 不能提前断开, 交给 connectExec 的放弃处理
        channel.connectExec()
        return SshShell(channel, input, output, this, onClose)
    }

    // 不等 SFTP 锁: 断开 session 会关闭所有 channel, 卡住的 SFTP 操作随之失败并释放锁
    override fun close() {
        // 先断目标 session (会经 proxy 关 channel), 再断跳板; 无 jump 时与原行为完全一致
        if (jumpSession == null) {
            session.disconnect()
        } else {
            runCatching { session.disconnect() }
            proxy?.close()
            runCatching { jumpSession?.disconnect() }
        }
    }

    private inline fun <T> openChannel(block: () -> T): T = try {
        block()
    } catch (e: JSchException) {
        throw SshChannelOpenException(e)
    }

    /**
     * 连接 exec channel。JSch 的 connect 先打开 channel, 再发送 exec 请求并等待服务器回应:
     * 只有打开阶段的失败能确定命令还没发出去, 按 [SshChannelOpenException] 允许重连重试;
     * 等待回应超时等其余失败时命令可能已经在执行, 原样抛出, 不可重复的命令不会再执行一遍。
     *
     * 后一种情况下 JSch 不会给服务器发 channel close, 远端的 channel 和进程会一直留到连接断开,
     * 而单独关闭这个 channel 的方法不对外开放, 所以断开整条连接, 连接上的其他操作也会随之失败。
     *
     * 被取消时的处理见 [connectChannel]。
     */
    private fun ChannelExec.connectExec() {
        connectChannel(
            step = {
                try {
                    connect(channelTimeoutMillis)
                } catch (e: JSchException) {
                    // JSch 没有区分这两个阶段的接口, 只能按打开阶段的异常信息判断
                    if (e.message in CHANNEL_OPEN_FAILURE_MESSAGES) throw SshChannelOpenException(e)
                    this@SshConnection.close()
                    throw e
                }
            },
            onAbandoned = { kill() },
        )
    }

    // 不少 sshd 不处理 signal 请求, 断开 channel 后远程进程会在下次读写管道时退出
    private fun ChannelExec.kill() {
        runCatching { sendSignal("KILL") }
        disconnect()
    }

    private fun detectShell(): String {
        val result = exec("command -v bash", SHELL_DETECT_TIMEOUT_MS)
        // 用户的 rc 文件可能会往 stdout 打印内容, 只认最后一个绝对路径
        val bash = result.stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("/") }
        return if (result.exitCode == 0 && bash != null) bash else "/bin/sh"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_CHANNEL_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_INTERVAL_MS = 15_000
        private const val KEEPALIVE_COUNT_MAX = 3
        private const val EXEC_POLL_INTERVAL_MS = 20L
        private const val SHELL_DETECT_TIMEOUT_MS = 15_000L
        private const val TERMINAL_TYPE = "xterm-256color"

        // RFC 4254 的 PTY 模式编码: 开启 IUTF8 (RFC 8160 opcode 42), 让行编辑按 UTF-8 字符退格; 以 TTY_OP_END 结尾
        private val TERMINAL_MODES = byteArrayOf(42, 0, 0, 0, 1, 0)

        // Channel.sendChannelOpen 失败时的异常信息, 此时 exec 请求还没发出
        private val CHANNEL_OPEN_FAILURE_MESSAGES = setOf("session is down", "channel is not opened.")

        fun open(config: SshConnectionConfig, channelTimeoutMillis: Int = DEFAULT_CHANNEL_TIMEOUT_MS): SshConnection =
            if (config.jump == null) openDirect(config, channelTimeoutMillis) else openViaJump(config, channelTimeoutMillis)

        private fun openDirect(config: SshConnectionConfig, channelTimeoutMillis: Int): SshConnection {
            val jsch = JSch()
            when (config.authType) {
                SshAuthType.PASSWORD -> {
                    if (config.password.isNullOrEmpty()) throw IOException("SSH password is required")
                }

                SshAuthType.PRIVATE_KEY -> {
                    val privateKey = config.privateKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IOException("SSH private key is required")
                    try {
                        jsch.addIdentity(
                            "rikkahub",
                            "$privateKey\n".toByteArray(),
                            null,
                            config.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
                        )
                    } catch (e: JSchException) {
                        throw IOException("Invalid SSH private key or passphrase: ${e.message}", e)
                    }
                }
            }

            val hostKeys = TrustedHostKeyRepository(config.hostKey)
            val session = jsch.getSession(config.username, normalizeSshHost(config.host), config.port).apply {
                setHostKeyRepository(hostKeys)
                setConfig("StrictHostKeyChecking", "yes")
                when (config.authType) {
                    SshAuthType.PASSWORD -> {
                        setConfig("PreferredAuthentications", "password,keyboard-interactive")
                        setPassword(config.password)
                        userInfo = PasswordUserInfo(config.password!!)
                    }

                    SshAuthType.PRIVATE_KEY -> setConfig("PreferredAuthentications", "publickey")
                }
                setServerAliveInterval(KEEPALIVE_INTERVAL_MS)
                serverAliveCountMax = KEEPALIVE_COUNT_MAX
            }
            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (e: JSchException) {
                hostKeys.rejection?.let { throw it }
                throw IOException("SSH connection to ${config.address} failed: ${e.message}", e)
            }
            val hostKey = hostKeys.presented ?: run {
                session.disconnect()
                throw IOException("SSH server did not present a host key")
            }
            return SshConnection(config, session, hostKey, channelTimeoutMillis)
        }

        /**
         * 经跳板机连目标: 跳板只用作 TCP 通道 (direct-tcpip), 不参与目标认证;
         * 目标主机指纹独立校验, 跳板和目标的信任互相独立。
         * 失败时按 目标 session -> proxy channel -> 跳板 session 的顺序清理, 不留悬挂的远端 channel。
         */
        private fun openViaJump(config: SshConnectionConfig, channelTimeoutMillis: Int): SshConnection {
            val jump = config.jump!!
            val jumpJsch = JSch()
            when (jump.authType) {
                SshAuthType.PASSWORD -> {
                    if (jump.password.isNullOrEmpty()) throw IOException("SSH jump host password is required")
                }

                SshAuthType.PRIVATE_KEY -> {
                    val privateKey = jump.privateKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IOException("SSH jump host private key is required")
                    try {
                        jumpJsch.addIdentity(
                            "rikkahub",
                            "$privateKey\n".toByteArray(),
                            null,
                            jump.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
                        )
                    } catch (e: JSchException) {
                        throw IOException("Invalid SSH jump host private key or passphrase: ${e.message}", e)
                    }
                }
            }

            val jumpHostKeys = TrustedHostKeyRepository(jump.hostKey, isJumpHost = true)
            val jumpSession = jumpJsch.getSession(jump.username, normalizeSshHost(jump.host), jump.port).apply {
                setHostKeyRepository(jumpHostKeys)
                setConfig("StrictHostKeyChecking", "yes")
                when (jump.authType) {
                    SshAuthType.PASSWORD -> {
                        setConfig("PreferredAuthentications", "password,keyboard-interactive")
                        setPassword(jump.password)
                        userInfo = PasswordUserInfo(jump.password!!)
                    }

                    SshAuthType.PRIVATE_KEY -> setConfig("PreferredAuthentications", "publickey")
                }
                setServerAliveInterval(KEEPALIVE_INTERVAL_MS)
                serverAliveCountMax = KEEPALIVE_COUNT_MAX
            }
            try {
                jumpSession.connect(CONNECT_TIMEOUT_MS)
            } catch (e: JSchException) {
                // 建连失败 (含指纹被拒) 也要释放半建好的跳板连接
                runCatching { jumpSession.disconnect() }
                jumpHostKeys.rejection?.let { throw it }
                throw IOException("SSH jump host connection to ${jump.address} failed: ${e.message}", e)
            } catch (e: Throwable) {
                // 非 JSch 异常同样要释放半建好的跳板连接
                runCatching { jumpSession.disconnect() }
                throw e
            }

            // 跳板建好之后任何一步失败都要清理跳板连接, 不留悬挂的 Session
            try {
                return openTargetViaJump(config, jumpSession, channelTimeoutMillis)
            } catch (e: Exception) {
                runCatching { jumpSession.disconnect() }
                throw e
            }
        }

        private fun openTargetViaJump(
            config: SshConnectionConfig,
            jumpSession: Session,
            channelTimeoutMillis: Int,
        ): SshConnection {
            val jsch = JSch()
            when (config.authType) {
                SshAuthType.PASSWORD -> {
                    if (config.password.isNullOrEmpty()) throw IOException("SSH password is required")
                }

                SshAuthType.PRIVATE_KEY -> {
                    val privateKey = config.privateKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IOException("SSH private key is required")
                    try {
                        jsch.addIdentity(
                            "rikkahub",
                            "$privateKey\n".toByteArray(),
                            null,
                            config.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
                        )
                    } catch (e: JSchException) {
                        throw IOException("Invalid SSH private key or passphrase: ${e.message}", e)
                    }
                }
            }

            val hostKeys = TrustedHostKeyRepository(config.hostKey)
            val session = jsch.getSession(config.username, normalizeSshHost(config.host), config.port).apply {
                setHostKeyRepository(hostKeys)
                setConfig("StrictHostKeyChecking", "yes")
                when (config.authType) {
                    SshAuthType.PASSWORD -> {
                        setConfig("PreferredAuthentications", "password,keyboard-interactive")
                        setPassword(config.password)
                        userInfo = PasswordUserInfo(config.password!!)
                    }

                    SshAuthType.PRIVATE_KEY -> setConfig("PreferredAuthentications", "publickey")
                }
                setServerAliveInterval(KEEPALIVE_INTERVAL_MS)
                serverAliveCountMax = KEEPALIVE_COUNT_MAX
            }
            val proxy = JumpChannelProxy(jumpSession, normalizeSshHost(config.host), config.port, channelTimeoutMillis)
            session.setProxy(proxy)
            try {
                // 建连整体放到看门狗线程池上并限时: direct-tcpip 之后没有本地 Socket, JSch 无法用
                // SO 超时兜住目标服务器不回 banner 的情况, 超时后从外部断开把卡住的读管道叫醒
                awaitInterruptiblyWithDeadline(
                    step = { session.connect(CONNECT_TIMEOUT_MS) },
                    deadlineMillis = (CONNECT_TIMEOUT_MS + channelTimeoutMillis).toLong(),
                    abort = {
                        runCatching { session.disconnect() }
                        proxy.close()
                    },
                )
            } catch (e: JSchException) {
                runCatching { session.disconnect() }
                proxy.close()
                hostKeys.rejection?.let { throw it }
                throw IOException("SSH connection to ${config.address} failed: ${e.message}", e)
            } catch (e: InterruptedException) {
                runCatching { session.disconnect() }
                proxy.close()
                throw e
            } catch (e: IOException) {
                // 看门狗超时
                runCatching { session.disconnect() }
                proxy.close()
                throw e
            }
            val hostKey = hostKeys.presented ?: run {
                runCatching { session.disconnect() }
                proxy.close()
                throw IOException("SSH server did not present a host key")
            }
            return SshConnection(config, session, hostKey, channelTimeoutMillis, jumpSession, proxy)
        }

        /** 用一条临时连接验证配置, 并取回远程环境的基本信息 */
        fun probe(config: SshConnectionConfig): SshProbeResult {
            // 被取消时放弃等待, 之后建好的连接直接关掉
            val connection = awaitInterruptibly({ open(config) }, onAbandoned = { it.close() })
            return connection.use {
                val remoteDir = config.remoteDir.trim()
                SshProbeResult(
                    hostKey = connection.hostKey,
                    homeDir = connection.homeDir,
                    shell = connection.shell,
                    remoteDirExists = remoteDir.startsWith("/") && connection.sftp { sftp ->
                        sftp.statOrNull(remoteDir)?.isDir == true
                    },
                )
            }
        }
    }
}

/** 打开 channel 失败, 请求还没有发到服务器, 重连后重试是安全的 */
internal class SshChannelOpenException(cause: Throwable) : IOException(cause.message, cause)

// JSch 等服务器回应时会吞掉线程中断, 这类步骤放到这里的线程上执行, 见 awaitInterruptibly
private val blockingSteps: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "ssh-blocking-step").apply { isDaemon = true }
}

/**
 * 在后台线程执行 [step], 当前线程可中断地等待结果。
 *
 * JSch 建立连接、打开 channel 和等待请求回应时会吞掉线程中断, 直接调用的话, 取消要等到服务器回应或超时,
 * 中断标记也会丢失。等待被中断时立即抛出 [InterruptedException], 之后 [step] 成功得到的结果交给 [onAbandoned] 清理,
 * 失败则调用 [onAbandonedFailure]。
 */
internal fun <T> awaitInterruptibly(
    step: () -> T,
    onAbandoned: (T) -> Unit,
    onAbandonedFailure: () -> Unit = {},
): T {
    if (Thread.interrupted()) throw InterruptedException()
    val future = CompletableFuture.supplyAsync({ step() }, blockingSteps)
    try {
        return future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        future.whenComplete { result, error -> if (error == null) onAbandoned(result) else onAbandonedFailure() }
        throw e
    }
}

/**
 * 带总时限的可中断等待: [step] 超时或等待被取消时调用 [abort] 从外部中断 [step] 卡住的网络操作,
 * [step] 之后仍然成功的话结果交给 [onAbandoned] 清理。
 *
 * direct-tcpip 隧道上没有本地 Socket, JSch 的 connectTimeout 兜不住目标服务器迟迟不回 banner 的情况,
 * 跳板机连接用它, 避免建连线程无限挂起。
 */
internal fun <T> awaitInterruptiblyWithDeadline(
    step: () -> T,
    deadlineMillis: Long,
    abort: () -> Unit,
    onAbandoned: (T) -> Unit = { abort() },
): T {
    if (Thread.interrupted()) {
        abort()
        throw InterruptedException()
    }
    val future = CompletableFuture.supplyAsync({ step() }, blockingSteps)
    try {
        return future.get(deadlineMillis, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        future.whenComplete { result, error -> if (error == null) onAbandoned(result) }
        abort()
        throw SocketTimeoutException("SSH connection timed out after $deadlineMillis ms")
    } catch (e: InterruptedException) {
        future.whenComplete { result, error -> if (error == null) onAbandoned(result) }
        abort()
        throw e
    }
}

/** 是否因线程中断而失败: JSch 会把读管道时的 InterruptedIOException 包进 SftpException 等异常里 */
internal fun Throwable.isInterruption(): Boolean = generateSequence(this) { it.cause }.any {
    it is InterruptedException || it is InterruptedIOException && it !is SocketTimeoutException
}

/** 逐级创建目录, 已存在的跳过 */
internal fun ChannelSftp.mkdirs(dir: String) {
    var current = ""
    dir.split('/').filter { it.isNotEmpty() }.forEach { segment ->
        current += "/$segment"
        val attrs = statOrNull(current)
        if (attrs == null) {
            // mkdir 不做通配符展开, 传原始路径
            mkdir(current)
        } else if (!attrs.isDir) {
            throw IOException("Not a directory: $current")
        }
    }
}

internal fun ChannelSftp.statOrNull(path: String) = try {
    stat(path.sftpQuote())
} catch (e: SftpException) {
    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
}

/** JSch 的 stat/ls/get/put/rm/rmdir 会把路径里的 * ? 当通配符展开, 反斜杠转义后按字面处理 */
internal fun String.sftpQuote(): String =
    replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")

/** 把目标 Session 的收发接进跳板机的 direct-tcpip channel, 等价 OpenSSH ProxyJump */
private class JumpChannelProxy(
    private val jumpSession: Session,
    private val targetHost: String,
    private val targetPort: Int,
    private val channelTimeoutMillis: Int,
) : Proxy {
    private val lifecycleLock = Any()
    private var closed = false
    private var channel: ChannelDirectTCPIP? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        val direct = jumpSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
        synchronized(lifecycleLock) {
            if (closed) {
                direct.disconnect()
                throw IOException("Jump channel was closed")
            }
            channel = direct
        }
        direct.setHost(targetHost)
        direct.setPort(targetPort)
        direct.setOrgIPAddress("127.0.0.1")
        direct.setOrgPort(0)
        // 流在 connect 前取: Channel.getInputStream 会把 channel 数据接到新管道
        input = direct.inputStream
        output = direct.outputStream
        try {
            direct.connect(channelTimeoutMillis)
        } catch (e: JSchException) {
            runCatching { direct.disconnect() }
            throw IOException("Cannot open jump channel to $targetHost:$targetPort: ${e.message}", e)
        }
        synchronized(lifecycleLock) {
            if (closed) {
                direct.disconnect()
                throw IOException("Jump channel was closed")
            }
        }
    }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    // direct-tcpip 没有本地 Socket; Session 对 null socket 会跳过 setSoTimeout/close
    override fun getSocket(): Socket? = null

    override fun close() {
        val current = synchronized(lifecycleLock) {
            closed = true
            channel.also { channel = null }
        }
        current?.let { runCatching { it.disconnect() } }
    }
}

/**
 * 只认用户确认过的那一把主机公钥。校验发生在认证之前,
 * 未知或不一致时连接直接失败, 密码不会发给未经确认的服务器。
 */
private class TrustedHostKeyRepository(private val trusted: SshHostKey?, private val isJumpHost: Boolean = false) : HostKeyRepository {
    @Volatile
    var presented: SshHostKey? = null

    @Volatile
    var rejection: IOException? = null

    override fun check(host: String, key: ByteArray): Int {
        val type = runCatching { HostKey(host, key).type }.getOrDefault("unknown")
        val actual = SshHostKey(type = type, key = Base64.getEncoder().encodeToString(key))
        presented = actual
        return when {
            trusted == null -> {
                rejection = SshUnknownHostKeyException(actual, isJumpHost)
                HostKeyRepository.NOT_INCLUDED
            }

            trusted.key == actual.key -> {
                rejection = null
                HostKeyRepository.OK
            }

            else -> {
                rejection = SshHostKeyChangedException(trusted, actual, isJumpHost)
                HostKeyRepository.CHANGED
            }
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit

    override fun remove(host: String, type: String) = Unit

    override fun remove(host: String, type: String, key: ByteArray) = Unit

    override fun getKnownHostsRepositoryID(): String = "rikkahub"

    override fun getHostKey(): Array<HostKey> = trustedHostKeys(host = "", type = null)

    // JSch 会把这里返回的算法排到协商列表最前, 保证服务器出示的是已信任的那一类密钥
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = trustedHostKeys(host.orEmpty(), type)

    private fun trustedHostKeys(host: String, type: String?): Array<HostKey> {
        val hostKey = trusted?.let {
            runCatching { HostKey(host, Base64.getDecoder().decode(it.key)) }.getOrNull()
        } ?: return emptyArray()
        return if (type == null || hostKey.type == type) arrayOf(hostKey) else emptyArray()
    }
}

/** 密码认证, 同时应答 keyboard-interactive 的密码提示 */
private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null

    override fun getPassword(): String = password

    override fun promptPassword(message: String?): Boolean = true

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String> = Array(prompt?.size ?: 0) { password }
}
