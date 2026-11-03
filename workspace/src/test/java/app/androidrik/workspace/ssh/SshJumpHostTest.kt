package app.androidrik.workspace.ssh

import app.androidrik.workspace.WorkspaceStorageArea
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * 跳板机 (ProxyJump) 连接: 双嵌入式 SSH 服务器 (跳板 + 目标), 验证
 * 指纹独立确认、隧道收发、凭据不泄漏与卡死时的看门狗返回。
 */
class SshJumpHostTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var jumpServer: SshServer
    private lateinit var targetServer: SshServer
    private lateinit var remoteDir: java.io.File
    private lateinit var home: java.io.File
    private val pool = SshConnectionPool()
    private var jumpPublicKey: String? = null
    private var targetPublicKey: String? = null

    @Before
    fun setUp() {
        remoteDir = tempFolder.newFolder("remote").canonicalFile
        home = tempFolder.newFolder("home").canonicalFile
        jumpServer = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tempFolder.root, "jump_host_key").toPath())
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == JUMP_USER && password == JUMP_PASSWORD
            }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                user == JUMP_USER && org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key).split(" ")[1] == jumpPublicKey
            }
            // 跳板只负责 direct-tcpip 转发
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }
        targetServer = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tempFolder.root, "target_host_key").toPath())
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == USER && password == PASSWORD
            }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                user == USER && org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key).split(" ")[1] == targetPublicKey
            }
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(tempFolder.root.toPath())
            subsystemFactories = listOf(SftpSubsystemFactory())
            commandFactory = org.apache.sshd.server.command.CommandFactory { channel, command ->
                object : org.apache.sshd.server.command.Command {
                    private lateinit var output: java.io.OutputStream
                    private lateinit var exit: org.apache.sshd.server.ExitCallback
                    override fun setInputStream(input: java.io.InputStream) {}
                    override fun setOutputStream(stream: java.io.OutputStream) { output = stream }
                    override fun setErrorStream(error: java.io.OutputStream) {}
                    override fun setExitCallback(callback: org.apache.sshd.server.ExitCallback) { exit = callback }
                    override fun start(session: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) {
                        output.write("/bin/sh\n".toByteArray())
                        output.flush()
                        exit.onExit(0)
                    }
                    override fun destroy(session: org.apache.sshd.server.channel.ChannelSession) {}
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        pool.close()
        targetServer.stop(true)
        jumpServer.stop(true)
    }

    private fun jumpConfig(hostKey: SshHostKey? = null) = SshJumpConfig(
        host = "127.0.0.1",
        port = jumpServer.port,
        username = JUMP_USER,
        authType = SshAuthType.PASSWORD,
        password = JUMP_PASSWORD,
        hostKey = hostKey,
    )

    private fun targetConfig(jump: SshJumpConfig?, hostKey: SshHostKey? = null) = SshConnectionConfig(
        host = "127.0.0.1",
        port = targetServer.port,
        username = USER,
        authType = SshAuthType.PASSWORD,
        password = PASSWORD,
        remoteDir = "/remote",
        hostKey = hostKey,
        jump = jump,
    )

    private fun trustedJump(): SshJumpConfig {
        val hostKey = assertThrows(SshUnknownHostKeyException::class.java) {
            pool.probe(targetConfig(jump = jumpConfig()))
        }.hostKey
        return jumpConfig(hostKey)
    }

    private fun trustedTarget(jump: SshJumpConfig): SshConnectionConfig {
        val hostKey = assertThrows(SshUnknownHostKeyException::class.java) {
            pool.probe(targetConfig(jump = jump))
        }.hostKey
        return targetConfig(jump = jump, hostKey = hostKey)
    }

    @Test
    fun unknownJumpHostKeyIsFlagged() {
        val error = assertThrows(SshUnknownHostKeyException::class.java) {
            pool.probe(targetConfig(jump = jumpConfig()))
        }
        assertTrue(error.isJumpHost)
        assertTrue(error.hostKey.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun probeAndFileOperationsGoThroughJump() {
        val jump = trustedJump()
        val config = trustedTarget(jump)
        val result = pool.probe(config)
        assertTrue(result.remoteDirExists)
        assertTrue(result.shell.startsWith("/"))

        val backend = SshWorkspaceBackend(pool, WORKSPACE_ID, config)
        backend.writeText("jump.txt", "hello via jump", overwrite = true)
        assertEquals("hello via jump", backend.readText("jump.txt"))
        assertTrue(backend.listFiles(WorkspaceStorageArea.FILES, ".").any { it.name == "jump.txt" })
    }

    @Test
    fun targetHostKeyIsStillEnforcedThroughJump() {
        val jump = trustedJump()
        val forged = SshHostKey(type = "ssh-ed25519", key = Base64.getEncoder().encodeToString(ByteArray(51) { 2 }))
        val error = assertThrows(SshHostKeyChangedException::class.java) {
            pool.probe(targetConfig(jump = jump, hostKey = forged))
        }
        assertFalse(error.isJumpHost)
        assertEquals(forged, error.expected)
    }

    @Test
    fun changedJumpHostKeyIsFlagged() {
        val jump = trustedJump()
        val forged = SshHostKey(type = "ssh-ed25519", key = Base64.getEncoder().encodeToString(ByteArray(51) { 3 }))
        val error = assertThrows(SshHostKeyChangedException::class.java) {
            pool.probe(targetConfig(jump = jump.copy(hostKey = forged)))
        }
        assertTrue(error.isJumpHost)
    }

    @Test
    fun wrongJumpPasswordFailsWithoutLeakingCredentials() {
        val jump = trustedJump()
        val error = assertThrows(IOException::class.java) {
            pool.probe(targetConfig(jump = jump.copy(password = "wrong")))
        }
        assertFalse(error.message!!.contains("wrong"))
        assertFalse(error.message!!.contains(PASSWORD))
    }

    @Test
    fun deadTargetPortBehindJumpFailsFast() {
        val jump = trustedJump()
        // 端口占住但不回 banner: direct-tcpip 能开, 目标握手卡死, 看门狗必须按时返回
        ServerSocket(0).use { sink ->
            thread(isDaemon = true) { runCatching { sink.accept() } }
            val start = System.currentTimeMillis()
            val error = assertThrows(Exception::class.java) {
                SshConnection.open(targetConfig(jump = jump).copy(port = sink.localPort))
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue("open returned too late (${elapsed}ms): $error", elapsed < 45_000)
        }
    }

    @Test
    fun poolReuseDistinguishesJumpConfigs() {
        val direct = targetConfig(jump = null)
        val viaJump = targetConfig(jump = jumpConfig())
        assertEquals(direct, direct.copy())
        assertFalse("同目标不同 jump 必须视为不同配置", direct == viaJump)
        assertTrue(viaJump == viaJump.copy(jump = jumpConfig()))
    }

    @Test
    fun independentPrivateKeysAuthenticateBothHops() {
        fun key(isJump: Boolean): String {
            val pair = com.jcraft.jsch.KeyPair.genKeyPair(com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048)
            if (isJump) jumpPublicKey = Base64.getEncoder().encodeToString(pair.publicKeyBlob)
            else targetPublicKey = Base64.getEncoder().encodeToString(pair.publicKeyBlob)
            return try {
                java.io.ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString("UTF-8")
            } finally { pair.dispose() }
        }
        val jump = trustedJump().copy(authType = SshAuthType.PRIVATE_KEY, password = null, privateKey = key(true))
        val config = trustedTarget(jump).copy(authType = SshAuthType.PRIVATE_KEY, password = null, privateKey = key(false))
        SshConnection.open(config).use { connection -> assertTrue(connection.isConnected) }
        assertThrows(IOException::class.java) {
            SshConnection.open(config.copy(privateKey = jump.privateKey))
        }
    }

    @Test
    fun execAndPtyChannelsUseTheTunnelAndPoolCloseDisconnectsBothSessions() {
        val config = trustedTarget(trustedJump())
        val backend = SshWorkspaceBackend(pool, WORKSPACE_ID, config)
        val result = pool.use(WORKSPACE_ID, config, retry = false) { it.exec("probe-command", 5000) }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("/bin/sh"))
        backend.openShell(80, 24).use { shell ->
            assertTrue(shell.inputStream.bufferedReader().readText().contains("/bin/sh"))
        }
        assertTrue(pool.isConnected(WORKSPACE_ID))
        pool.close()
        assertFalse(pool.isConnected(WORKSPACE_ID))
        repeat(50) {
            if (jumpServer.activeSessions.isEmpty() && targetServer.activeSessions.isEmpty()) return
            Thread.sleep(20)
        }
        assertTrue(jumpServer.activeSessions.isEmpty())
        assertTrue(targetServer.activeSessions.isEmpty())
    }

    /** Opt-in fixture for Android-through-adb-reverse integration; never contacts an external server. */
    @Test
    fun deviceFixtureWhenRequested() {
        val path = System.getenv("NEOMIX_SSH_FIXTURE_FILE")
        org.junit.Assume.assumeTrue("No device fixture requested", !path.isNullOrBlank())
        val marker = File(path!!)
        marker.writeText("${jumpServer.port},${targetServer.port}")
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3)
        while (marker.exists() && System.nanoTime() < until) Thread.sleep(100)
    }

    private companion object {
        const val USER = "rikkatest"
        const val PASSWORD = "rikkatest-pw"
        const val JUMP_USER = "jumpuser"
        const val JUMP_PASSWORD = "jump-pw"
        const val WORKSPACE_ID = "jump-test-workspace"
    }
}
