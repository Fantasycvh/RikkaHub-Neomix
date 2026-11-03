package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.SshJumpDraft
import app.androidrik.rikkahub.data.model.SshWorkspaceDraft
import app.androidrik.rikkahub.data.model.SshWorkspaceSettings
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceSshEditorVM
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.SecretCipher
import app.androidrik.workspace.RootfsInstaller
import app.androidrik.workspace.WorkspaceManager
import app.androidrik.workspace.ssh.SshConnectionPool
import app.androidrik.workspace.ssh.SshHostKey
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshJumpSettingsInstrumentedTest {
    @Test
    fun androidConnectsThroughJumpWithIndependentTrustAndSftpExecPty() {
        val args = InstrumentationRegistry.getArguments()
        val jumpPort = args.getString("jumpPort")?.toIntOrNull()
        val targetPort = args.getString("targetPort")?.toIntOrNull()
        org.junit.Assume.assumeTrue("Local fixture not requested", jumpPort != null && targetPort != null)
        val jump = app.androidrik.workspace.ssh.SshJumpConfig(
            host = "127.0.0.1", port = jumpPort!!, username = "jumpuser",
            authType = app.androidrik.workspace.ssh.SshAuthType.PASSWORD, password = "jump-pw",
        )
        val target = app.androidrik.workspace.ssh.SshConnectionConfig(
            host = "127.0.0.1", port = targetPort!!, username = "rikkatest",
            authType = app.androidrik.workspace.ssh.SshAuthType.PASSWORD, password = "rikkatest-pw",
            remoteDir = "/remote", jump = jump,
        )
        SshConnectionPool().use { pool ->
            val jumpUnknown = assertThrows(app.androidrik.workspace.ssh.SshUnknownHostKeyException::class.java) { pool.probe(target) }
            assertTrue(jumpUnknown.isJumpHost)
            val trustedJump = target.copy(jump = jump.copy(hostKey = jumpUnknown.hostKey))
            val targetUnknown = assertThrows(app.androidrik.workspace.ssh.SshUnknownHostKeyException::class.java) { pool.probe(trustedJump) }
            assertFalse(targetUnknown.isJumpHost)
            val trusted = trustedJump.copy(hostKey = targetUnknown.hostKey)
            assertTrue(pool.probe(trusted).remoteDirExists)
            val backend = app.androidrik.workspace.ssh.SshWorkspaceBackend(pool, "device-jump-fixture", trusted)
            backend.writeText("android-jump.txt", "from Android through jump", overwrite = true)
            assertEquals("from Android through jump", backend.readText("android-jump.txt"))
            assertTrue(backend.executeCommand("fixture-command", "", 5000, null).stdout.contains("/bin/sh"))
            backend.openShell(80, 24).use { shell ->
                assertTrue(shell.inputStream.bufferedReader().readText().contains("/bin/sh"))
            }
        }
    }

    @Test
    fun encryptedJumpCredentialsPersistAndAreNotReusedForAnotherEndpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "jump-settings-${Uuid.random()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val pool = SshConnectionPool()
        val scope = AppScope().apply { cancel() }
        try {
            val manager = WorkspaceManager(directory)
            val repo = WorkspaceRepository(db.workspaceDao(), manager, RootfsInstaller(manager),
                SettingsStore(context, scope, File(directory, "test.preferences_pb")), pool)
            val draft = SshWorkspaceDraft(name = "jump", host = "target", username = "target-user",
                password = "target-secret", remoteDir = "/work",
                jump = SshJumpDraft(host = "bastion", username = "jump-user", password = "jump-secret"))
            val saved = repo.saveSsh(null, draft)
            assertFalse(saved.sshConfig!!.contains("jump-secret"))
            assertFalse(saved.sshConfig!!.contains("target-secret"))
            val loaded = repo.getById(saved.id)!!.sshSettings()!!
            assertEquals("jump-secret", SecretCipher.decrypt(loaded.jump!!.encryptedPassword!!))
            val unchanged = repo.saveSsh(saved.id, draft.copy(password = "", jump = draft.jump!!.copy(password = "")))
            assertEquals(loaded.jump!!.encryptedPassword, unchanged.sshSettings()!!.jump!!.encryptedPassword)
            val changed = repo.saveSsh(saved.id, draft.copy(jump = draft.jump!!.copy(host = "other", password = "")))
            assertNull(changed.sshSettings()!!.jump!!.encryptedPassword)
            assertNull(repo.saveSsh(saved.id, draft.copy(jump = null)).sshSettings()!!.jump)
            val legacy = JsonInstant.decodeFromString<SshWorkspaceSettings>("""{"host":"old","username":"u","remoteDir":"/work"}""")
            assertNull(legacy.jump)

            val vm = WorkspaceSshEditorVM(null, repo)
            val key = SshHostKey("ssh-ed25519", java.util.Base64.getEncoder().encodeToString(ByteArray(32)))
            vm.update { it.copy(jumpEnabled = true, jumpHost = "a", jumpUsername = "u") }
            vm.update { it.copy(jumpHostKey = key, hostKey = key, hasSavedJumpPassword = true) }
            vm.update { it.copy(jumpHost = "b") }
            assertNull(vm.state.value.jumpHostKey)
            assertFalse(vm.state.value.hasSavedJumpPassword)
            assertEquals(key, vm.state.value.hostKey)
            vm.update { it.copy(jumpEnabled = false) }
            assertEquals("", vm.state.value.jumpHost)
            assertNull(vm.state.value.jumpHostKey)
        } finally {
            pool.close()
            db.close()
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }
}
