package app.androidrik.rikkahub.data.ai.transformers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.SshWorkspaceDraft
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.ssh.SshAuthType
import app.androidrik.workspace.ssh.SshUnknownHostKeyException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import kotlin.uuid.Uuid

/**
 * 真机验证工作区提醒注入：真实 SSH 工作区 + 真实 Repository，不经过 UI 预览。
 *
 * 预览（`请求内容`）此前只投影 `buildSystemBlocks`，会丢掉 transformer 追加到 system 的
 * 工作区提醒；这里直接断言 transformer 输出，避免被预览缺陷误判成“没有注入”。
 * 未提供 sshUser/sshPassword 时跳过。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceReminderTransformerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val user = args.getString("sshUser").orEmpty()
    private val password = args.getString("sshPassword").orEmpty()
    private val host = args.getString("sshHost") ?: "127.0.0.1"
    private val port = args.getString("sshPort")?.toIntOrNull() ?: 22

    private fun requireCredentials() {
        assumeTrue("sshUser not provided", user.isNotBlank())
        assumeTrue("sshPassword not provided", password.isNotBlank())
    }

    private fun draft(name: String, targetPort: Int) = SshWorkspaceDraft(
        name = name,
        host = host,
        port = targetPort,
        username = user,
        authType = SshAuthType.PASSWORD,
        password = password,
        remoteDir = "/home/$user/rikkahub-prompt-it",
    )

    private suspend fun transformToText(
        repository: WorkspaceRepository,
        workspaceId: String,
    ): String {
        val transformer = WorkspaceReminderTransformer(repository)
        val ctx = TransformerContext(
            context = context,
            model = Model(),
            assistant = Assistant(workspaceId = Uuid.parse(workspaceId)),
            settings = Settings(),
        )
        val output = transformer.transform(ctx, listOf(UIMessage.system("BASE")))
        return output.first().parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    @Test
    fun sshWorkspaceInjectsRemotePromptWithoutAgentsMd() {
        requireCredentials()
        runBlocking {
            val repository = KoinJavaComponent.getKoin().get<WorkspaceRepository>()
            val draft = draft("prompt-it-${System.currentTimeMillis()}", port)
            val hostKey = try {
                repository.probeSsh(null, draft).hostKey
            } catch (unknown: SshUnknownHostKeyException) {
                unknown.hostKey
            }
            val workspace = repository.saveSsh(null, draft.copy(hostKey = hostKey))
            try {
                val text = transformToText(repository, workspace.id)
                assertTrue(text.startsWith("BASE"))
                assertTrue("missing workspace tag", text.contains("<workspace>"))
                assertTrue(text.contains("persistent remote workspace"))
                assertTrue(text.contains(workspace.name))
                assertTrue(text.contains("/home/$user/rikkahub-prompt-it"))
                assertTrue(text.contains("workspace_read_file"))
                assertTrue(text.contains("workspace_shell"))
                assertTrue(text.contains("no file write or edit tools"))
                assertFalse("remote workspace must not read AGENTS.md", text.contains("AGENTS.md"))
            } finally {
                repository.delete(workspace.id)
            }
        }
    }

    @Test(timeout = 90_000)
    fun unreachableWorkspaceInjectsUnreachablePrompt() {
        requireCredentials()
        runBlocking {
            val repository = KoinJavaComponent.getKoin().get<WorkspaceRepository>()
            // saveSsh 不探测连接；端口 1 必然连不上，用于触发不可达分支
            val workspace = repository.saveSsh(
                null,
                draft("unreachable-it-${System.currentTimeMillis()}", 1),
            )
            try {
                val text = transformToText(repository, workspace.id)
                assertTrue(text.startsWith("BASE"))
                assertTrue(text.contains("<workspace>"))
                assertTrue(text.contains("currently unreachable"))
                assertFalse(text.contains("persistent remote workspace"))
            } finally {
                repository.delete(workspace.id)
            }
        }
    }
}
