package app.androidrik.rikkahub.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.model.SshWorkspaceDraft
import app.androidrik.workspace.ssh.SshAuthType
import app.androidrik.workspace.ssh.SshUnknownHostKeyException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent

/**
 * 真机端到端：Keystore 加密落库 + Repository 解密连接 + SFTP/exec。
 *
 * 需要一台可访问的 SSH 服务器，通过 instrumentation 参数传入：
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=...SshRepositoryInstrumentedTest \
 *   -Pandroid.testInstrumentationRunnerArguments.sshUser=sshit \
 *   -Pandroid.testInstrumentationRunnerArguments.sshPassword=... \
 *   -Pandroid.testInstrumentationRunnerArguments.sshHost=127.0.0.1 \
 *   -Pandroid.testInstrumentationRunnerArguments.sshPort=2222
 * ```
 * 未提供 sshUser 时跳过。
 */
@RunWith(AndroidJUnit4::class)
class SshRepositoryInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val user = args.getString("sshUser").orEmpty()
    private val password = args.getString("sshPassword").orEmpty()
    private val host = args.getString("sshHost") ?: "127.0.0.1"
    private val port = args.getString("sshPort")?.toIntOrNull() ?: 22
    private val remoteDir = args.getString("sshRemoteDir") ?: "/home/$user/rikkahub-it"

    @Test
    fun savesEncryptedCredentialsAndConnectsOverSsh() {
        runBlocking {
            assumeTrue("sshUser not provided", user.isNotBlank())
            assumeTrue("sshPassword not provided", password.isNotBlank())

            val repository = KoinJavaComponent.getKoin().get<WorkspaceRepository>()
            val name = "ssh-it-${System.currentTimeMillis()}"
            val draft = SshWorkspaceDraft(
                name = name,
                host = host,
                port = port,
                username = user,
                authType = SshAuthType.PASSWORD,
                password = password,
                remoteDir = remoteDir,
            )

            // 首次连接没有信任的主机密钥，先取指纹再重试（与编辑页首次确认流程一致）
            val hostKey = try {
                repository.probeSsh(null, draft).hostKey
            } catch (unknown: SshUnknownHostKeyException) {
                unknown.hostKey
            }
            val probe = repository.probeSsh(null, draft.copy(hostKey = hostKey))
            assertEquals("/home/$user", probe.homeDir)
            assertTrue(probe.shell.endsWith("/bash") || probe.shell.endsWith("/sh"))

            val workspace = repository.saveSsh(null, draft.copy(hostKey = hostKey))
            try {
                val stored = repository.getById(workspace.id) ?: error("workspace disappeared")
                val config = stored.sshConfig ?: error("ssh config missing")
                assertTrue("credential should be encrypted", config.contains("\"encryptedPassword\":\"v1:"))
                assertFalse("plaintext password leaked into DB", config.contains(password))
                assertFalse("password field should not exist", config.contains("\"password\""))

                // 走到这里说明 Keystore 解密成功，并且能连上真实服务器
                val shell = repository.rootfsShell(workspace.id)
                assertTrue(shell.endsWith("/bash") || shell.endsWith("/sh"))

                val result = repository.executeCommand(
                    id = workspace.id,
                    command = "printf 'ok'; id -un",
                    cwd = "",
                    timeoutMillis = 15_000,
                )
                assertEquals(0, result.exitCode)
                assertEquals("ok$user", result.stdout.trim())

                repository.writeText(workspace.id, "it.txt", "hello-ssh", overwrite = true)
                assertEquals("hello-ssh", repository.readText(workspace.id, "it.txt"))

                repository.executeCommand(
                    id = workspace.id,
                    command = "cd / && rm -rf -- " + remoteDir.shellQuote(),
                    cwd = "",
                    timeoutMillis = 15_000,
                )
            } finally {
                repository.delete(workspace.id)
            }
        }
    }
}

private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
