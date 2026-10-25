package app.androidrik.workspace.ssh

import app.androidrik.workspace.WorkspaceStorageArea
import app.androidrik.workspace.shellQuote
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 可选的**真实服务器**集成测试：默认跳过，设置以下环境变量后运行：
 *
 * ```
 * SSH_IT_HOST=127.0.0.1 SSH_IT_PORT=2222 SSH_IT_USER=root \
 * SSH_IT_KEY=/path/to/private_key SSH_IT_DIR=/tmp/rikkahub-ssh-it \
 * ./gradlew :workspace:testDebugUnitTest --tests "*SshLiveServerTest"
 * ```
 *
 * 覆盖嵌入式 SSHD 测试难以覆盖的部分：真实 OpenSSH 的认证/主机密钥、POSIX shell 的
 * `executeCommand`、stdin、远程 cwd，以及带 PTY 的交互式 shell。
 */
class SshLiveServerTest {
    private val host = System.getenv("SSH_IT_HOST").orEmpty()
    private val port = System.getenv("SSH_IT_PORT")?.toIntOrNull() ?: 22
    private val user = System.getenv("SSH_IT_USER").orEmpty()
    private val keyPath = System.getenv("SSH_IT_KEY").orEmpty()
    private val remoteDir = System.getenv("SSH_IT_DIR")?.takeIf { it.isNotBlank() }
        ?: "/tmp/rikkahub-ssh-it"

    @Before
    fun requireLiveServer() {
        assumeTrue("SSH_IT_HOST not set", host.isNotBlank())
        assumeTrue("SSH_IT_USER not set", user.isNotBlank())
        assumeTrue("SSH_IT_KEY not set or missing", keyPath.isNotBlank() && File(keyPath).isFile)
    }

    @Test
    fun `real server supports host key verification sftp and exec`() {
        val base = configWithoutHostKey()
        SshConnectionPool().use { pool ->
            val unknown = runCatching { pool.probe(base) }.exceptionOrNull()
            assertTrue("expected unknown host key, got $unknown", unknown is SshUnknownHostKeyException)
            val hostKey = (unknown as SshUnknownHostKeyException).hostKey
            val trusted = base.copy(hostKey = hostKey)

            val wrongKey = SshHostKey(
                type = hostKey.type,
                key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            )
            val changed = runCatching { pool.probe(trusted.copy(hostKey = wrongKey)) }.exceptionOrNull()
            assertTrue("expected changed host key, got $changed", changed is SshHostKeyChangedException)

            val backend = SshWorkspaceBackend(pool, "live-${System.nanoTime()}", trusted)
            try {
                // 真实发行版可能把 bash 放在 /usr/bin（Ubuntu 的 /bin 是软链）
                assertTrue(backend.shell().endsWith("/bash"))

                val entry = backend.writeText("notes/nested/it.txt", "hello\nworld\n", overwrite = true)
                assertEquals("notes/nested/it.txt", entry.path)
                assertEquals(12L, entry.sizeBytes)
                assertEquals("hello\nworld\n", backend.readText("notes/nested/it.txt"))
                assertEquals(
                    12L,
                    backend.fileSize(WorkspaceStorageArea.FILES, "notes/nested/it.txt"),
                )
                assertTrue(
                    backend.listFiles(WorkspaceStorageArea.FILES, "notes")
                        .any { it.name == "nested" && it.isDirectory }
                )

                val absolute = "$remoteDir/abs.txt"
                backend.writeAbsoluteText(absolute, "absolute", overwrite = true)
                val exported = ByteArrayOutputStream().use { output ->
                    backend.exportAbsoluteFile(absolute, output)
                    output.toString(Charsets.UTF_8.name())
                }
                assertEquals("absolute", exported)
                assertEquals(8L, backend.absoluteFileSize(absolute))

                val failed = backend.executeCommand(
                    "printf 'out'; printf 'err' >&2; exit 7",
                    cwd = "",
                    timeoutMillis = 15_000,
                    stdin = null,
                )
                assertEquals(7, failed.exitCode)
                assertEquals("out", failed.stdout)
                assertEquals("err", failed.stderr)
                assertFalse(failed.timedOut)

                val piped = backend.executeCommand(
                    "cat",
                    cwd = "",
                    timeoutMillis = 15_000,
                    stdin = "piped".toByteArray(),
                )
                assertEquals("piped", piped.stdout)

                val pwd = backend.executeCommand(
                    "pwd",
                    cwd = "notes",
                    timeoutMillis = 15_000,
                    stdin = null,
                )
                assertEquals("$remoteDir/notes", pwd.stdout.trim())

                assertTrue(backend.listFiles(WorkspaceStorageArea.LINUX, "").isNotEmpty())
                assertTrue(backend.deleteFile(WorkspaceStorageArea.FILES, "notes", recursive = true))
                assertFalse(backend.deleteFile(WorkspaceStorageArea.FILES, "notes", recursive = true))
            } finally {
                runCatching { backend.removeRemoteDir(remoteDir) }
            }
        }
    }

    @Test
    fun `real server opens an interactive pty shell`() {
        SshConnectionPool().use { pool ->
            val hostKey = (runCatching { pool.probe(configWithoutHostKey()) }.exceptionOrNull()
                as? SshUnknownHostKeyException)?.hostKey
                ?: error("expected an unknown host key; is SSH_IT_HOST already trusted?")
            val backend = SshWorkspaceBackend(
                pool,
                "live-shell-${System.nanoTime()}",
                configWithoutHostKey().copy(hostKey = hostKey),
            )
            val shell = backend.openShell(columns = 100, rows = 30)
            try {
                shell.outputStream.write("printf 'pty-ok\\n'\n".toByteArray())
                shell.outputStream.flush()
                val output = readUntil(shell, "pty-ok", timeoutMillis = 10_000)
                assertTrue("pty output was: $output", output.contains("pty-ok"))
                assertFalse(shell.connectionLost)
            } finally {
                runCatching { shell.outputStream.write("exit\n".toByteArray()) }
                shell.close()
                runCatching { backend.removeRemoteDir(remoteDir) }
            }
        }
    }

    private fun configWithoutHostKey() = SshConnectionConfig(
        host = host,
        port = port,
        username = user,
        authType = SshAuthType.PRIVATE_KEY,
        privateKey = File(keyPath).readText(),
        remoteDir = remoteDir,
    )

    private fun SshWorkspaceBackend.removeRemoteDir(dir: String) {
        executeCommand(
            "cd / && rm -rf -- ${dir.shellQuote()}",
            cwd = "",
            timeoutMillis = 15_000,
            stdin = null,
        )
    }

    private fun readUntil(shell: SshShell, needle: String, timeoutMillis: Long): String {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val builder = StringBuilder()
        val buffer = ByteArray(4096)
        while (System.nanoTime() < deadline) {
            while (shell.inputStream.available() > 0) {
                val read = shell.inputStream.read(buffer)
                if (read < 0) return builder.toString()
                builder.append(String(buffer, 0, read, Charsets.UTF_8))
                if (builder.contains(needle)) return builder.toString()
            }
            Thread.sleep(20)
        }
        return builder.toString()
    }
}
