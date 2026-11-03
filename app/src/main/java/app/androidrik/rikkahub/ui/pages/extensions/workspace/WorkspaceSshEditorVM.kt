package app.androidrik.rikkahub.ui.pages.extensions.workspace

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.SshJumpDraft
import app.androidrik.rikkahub.data.model.SshWorkspaceDraft
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.ssh.SshAuthType
import app.androidrik.workspace.ssh.SshHostKey
import app.androidrik.workspace.ssh.SshHostKeyChangedException
import app.androidrik.workspace.ssh.SshProbeResult
import app.androidrik.workspace.ssh.SshUnknownHostKeyException

class WorkspaceSshEditorVM(
    private val id: String?,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceSshEditorState(loading = id != null))
    val state = _state.asStateFlow()

    val isNew: Boolean = id == null

    init {
        if (id != null) {
            viewModelScope.launch {
                val workspace = repository.getById(id)
                val settings = workspace?.sshSettings()
                _state.update { state ->
                    if (workspace == null || settings == null) {
                        state.copy(
                            loading = false,
                            error = SshEditorError.Resource(R.string.workspace_ssh_editor_not_found)
                        )
                    } else {
                        state.copy(
                            loading = false,
                            name = workspace.name,
                            host = settings.host,
                            port = settings.port.toString(),
                            username = settings.username,
                            authType = settings.authType,
                            remoteDir = settings.remoteDir,
                            hostKey = settings.hostKey,
                            hasSavedPassword = settings.encryptedPassword != null,
                            hasSavedPrivateKey = settings.encryptedPrivateKey != null,
                            jumpEnabled = settings.jump != null,
                            jumpHost = settings.jump?.host.orEmpty(),
                            jumpPort = settings.jump?.port?.toString() ?: "22",
                            jumpUsername = settings.jump?.username.orEmpty(),
                            jumpAuthType = settings.jump?.authType ?: SshAuthType.PASSWORD,
                            jumpHostKey = settings.jump?.hostKey,
                            hasSavedJumpPassword = settings.jump?.encryptedPassword != null,
                            hasSavedJumpPrivateKey = settings.jump?.encryptedPrivateKey != null,
                        )
                    }
                }
            }
        }
    }

    fun update(transform: (WorkspaceSshEditorState) -> WorkspaceSshEditorState) {
        _state.update { old ->
            // 测试和保存针对的是开始时的表单快照, 期间不接受修改
            if (old.busy) return@update old
            val new = transform(old)
            // 信任的主机密钥属于原来的地址, 地址变了必须重新确认
            val targetChanged = new.host.trim() != old.host.trim() || new.port != old.port
            // 跳板机地址变了重新确认指纹; 端点或认证方式变化后不再沿用已存凭据
            val jumpAddressChanged = new.jumpHost.trim() != old.jumpHost.trim() || new.jumpPort != old.jumpPort
            val jumpEndpointChanged = jumpAddressChanged ||
                new.jumpUsername.trim() != old.jumpUsername.trim() || new.jumpAuthType != old.jumpAuthType
            val cleared = when {
                !new.jumpEnabled -> new.clearJump()
                jumpEndpointChanged -> new.copy(
                    jumpHostKey = if (jumpAddressChanged) null else new.jumpHostKey,
                    // 端点或认证方式变了, 已保存的旧凭据不再可用 (Repository 同步拒绝沿用)
                    hasSavedJumpPassword = false,
                    hasSavedJumpPrivateKey = false,
                    // 测试结果是旧配置得到的, 换跳板后不再代表当前表单
                    probeResult = null,
                )
                else -> new
            }
            if (targetChanged) {
                cleared.copy(hostKey = null, probeResult = null)
            } else {
                cleared
            }
        }
    }

    fun testConnection() = runProbe(saveAfter = false)

    /** 保存前先测试连接, 顺便确认主机密钥; 连接失败时询问是否仍然保存 */
    fun save() = runProbe(saveAfter = true)

    /** 保存测试失败的那份配置 */
    fun saveAnyway() {
        val draft = state.value.saveAnywayDraft ?: return
        _state.update { it.copy(error = null, saveAnywayDraft = null) }
        persist(draft)
    }

    fun trustHostKey() {
        val pending = state.value.pendingHostKey ?: return
        // 指纹属于测试时的那份配置, 表单变了就不能套用到新地址上
        if (state.value.toDraftOrNull()?.withoutHostKeys() != pending.draft.withoutHostKeys()) {
            _state.update { it.copy(pendingHostKey = null) }
            return
        }
        _state.update { it.copy(hostKey = pending.actual, pendingHostKey = null) }
        runProbe(saveAfter = pending.saveAfter)
    }

    fun dismissHostKey() {
        _state.update { it.copy(pendingHostKey = null) }
    }

    fun trustJumpHostKey() {
        val pending = state.value.pendingJumpHostKey ?: return
        // 跳板机指纹同样属于测试时的配置, 表单变了就不能套用到新地址上
        if (state.value.toDraftOrNull()?.withoutHostKeys() != pending.draft.withoutHostKeys()) {
            _state.update { it.copy(pendingJumpHostKey = null) }
            return
        }
        _state.update { it.copy(jumpHostKey = pending.actual, pendingJumpHostKey = null) }
        runProbe(saveAfter = pending.saveAfter)
    }

    fun dismissJumpHostKey() {
        _state.update { it.copy(pendingJumpHostKey = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, saveAnywayDraft = null) }
    }

    private fun runProbe(saveAfter: Boolean) {
        val current = state.value
        if (current.busy) return
        val draft = current.toDraftOrNull() ?: run {
            _state.update { it.copy(error = SshEditorError.Resource(R.string.workspace_ssh_editor_missing_fields)) }
            return
        }
        _state.update { it.copy(testing = true, error = null, saveAnywayDraft = null) }
        viewModelScope.launch {
            try {
                val result = repository.probeSsh(id, draft)
                // 没填工作目录时默认用远程用户的主目录
                val tested = draft.copy(remoteDir = draft.remoteDir.ifBlank { result.homeDir })
                _state.update { it.copy(testing = false, probeResult = result, remoteDir = tested.remoteDir) }
                if (saveAfter) persist(tested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshUnknownHostKeyException) {
                if (e.isJumpHost) {
                    _state.update {
                        it.copy(
                            testing = false,
                            pendingJumpHostKey = PendingJumpHostKey(draft, e.hostKey, null, saveAfter)
                        )
                    }
                } else {
                    _state.update {
                        it.copy(testing = false, pendingHostKey = PendingHostKey(draft, e.hostKey, null, saveAfter))
                    }
                }
            } catch (e: SshHostKeyChangedException) {
                if (e.isJumpHost) {
                    _state.update {
                        it.copy(
                            testing = false,
                            pendingJumpHostKey = PendingJumpHostKey(draft, e.actual, e.expected, saveAfter),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            testing = false,
                            pendingHostKey = PendingHostKey(draft, e.actual, e.expected, saveAfter)
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        testing = false,
                        probeResult = null,
                        error = SshEditorError.Message(e.message ?: e.javaClass.simpleName),
                        saveAnywayDraft = draft.takeIf { saveAfter },
                    )
                }
            }
        }
    }

    private fun persist(draft: SshWorkspaceDraft) {
        if (draft.remoteDir.isBlank()) {
            _state.update { it.copy(error = SshEditorError.Resource(R.string.workspace_ssh_editor_missing_remote_dir)) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repository.saveSsh(id, draft)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        saving = false,
                        error =
                        e.message?.let(SshEditorError::Message)
                            ?: SshEditorError.Resource(R.string.workspace_ssh_editor_save_failed)
                    )
                }
            }
        }
    }

    private fun WorkspaceSshEditorState.toDraftOrNull(): SshWorkspaceDraft? {
        val port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || username.isBlank()) return null
        val jumpDraft = if (jumpEnabled) {
            val jumpPort = jumpPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (jumpHost.isBlank() || jumpUsername.isBlank()) return null
            SshJumpDraft(
                host = jumpHost,
                port = jumpPort,
                username = jumpUsername,
                authType = jumpAuthType,
                password = jumpPassword,
                privateKey = jumpPrivateKey,
                passphrase = jumpPassphrase,
                hostKey = jumpHostKey,
            )
        } else {
            null
        }
        return SshWorkspaceDraft(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            remoteDir = remoteDir.trim(),
            hostKey = hostKey,
            jump = jumpDraft,
        )
    }
}

/** 指纹不参与表单比较: 确认时只比对除两把主机密钥外的配置 */
private fun SshWorkspaceDraft.withoutHostKeys(): SshWorkspaceDraft =
    copy(hostKey = null, jump = jump?.copy(hostKey = null))

data class WorkspaceSshEditorState(
    val loading: Boolean = false,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val remoteDir: String = "",
    val hostKey: SshHostKey? = null,
    // 编辑已有工作区时, 已保存的凭据可以留空沿用
    val hasSavedPassword: Boolean = false,
    val hasSavedPrivateKey: Boolean = false,
    // 跳板机 (ProxyJump): 关闭时保存的 jump=null
    val jumpEnabled: Boolean = false,
    val jumpHost: String = "",
    val jumpPort: String = "22",
    val jumpUsername: String = "",
    val jumpAuthType: SshAuthType = SshAuthType.PASSWORD,
    val jumpPassword: String = "",
    val jumpPrivateKey: String = "",
    val jumpPassphrase: String = "",
    val jumpHostKey: SshHostKey? = null,
    val hasSavedJumpPassword: Boolean = false,
    val hasSavedJumpPrivateKey: Boolean = false,
    val testing: Boolean = false,
    val saving: Boolean = false,
    val probeResult: SshProbeResult? = null,
    val pendingHostKey: PendingHostKey? = null,
    val pendingJumpHostKey: PendingJumpHostKey? = null,
    val error: SshEditorError? = null,
    /** 保存前测试失败的配置, 用户可以选择仍然保存它 */
    val saveAnywayDraft: SshWorkspaceDraft? = null,
    val saved: Boolean = false,
) {
    // 不让明文凭据 (含跳板机) 出现在日志和异常信息里
    override fun toString(): String =
        "WorkspaceSshEditorState(loading=$loading, name=$name, host=$host, port=$port, username=$username, " +
            "authType=$authType, remoteDir=$remoteDir, hostKey=${hostKey?.fingerprint}, " +
            "hasSavedPassword=$hasSavedPassword, hasSavedPrivateKey=$hasSavedPrivateKey, " +
            "jumpEnabled=$jumpEnabled, jumpHost=$jumpHost, jumpPort=$jumpPort, jumpUsername=$jumpUsername, " +
            "jumpAuthType=$jumpAuthType, jumpHostKey=${jumpHostKey?.fingerprint}, " +
            "hasSavedJumpPassword=$hasSavedJumpPassword, hasSavedJumpPrivateKey=$hasSavedJumpPrivateKey, " +
            "testing=$testing, saving=$saving, saved=$saved)"

    val busy: Boolean
        get() = loading || testing || saving

    val canSaveAnyway: Boolean
        get() = saveAnywayDraft != null

    /** 关闭跳板机开关时清掉全部跳板机字段 */
    fun clearJump(): WorkspaceSshEditorState = copy(
        jumpEnabled = false,
        jumpHost = "",
        jumpPort = "22",
        jumpUsername = "",
        jumpAuthType = SshAuthType.PASSWORD,
        jumpPassword = "",
        jumpPrivateKey = "",
        jumpPassphrase = "",
        jumpHostKey = null,
        hasSavedJumpPassword = false,
        hasSavedJumpPrivateKey = false,
        pendingJumpHostKey = null,
    )
}

/** 表单自己的提示用字符串资源, 连接和保存失败时直接显示异常信息 */
sealed interface SshEditorError {
    data class Resource(@StringRes val id: Int) : SshEditorError

    data class Message(val text: String) : SshEditorError
}

/** 等待用户确认的主机密钥, 属于 [draft] 这份配置; [expected] 不为空表示和之前信任的不一致 */
data class PendingHostKey(
    val draft: SshWorkspaceDraft,
    val actual: SshHostKey,
    val expected: SshHostKey?,
    val saveAfter: Boolean,
)

/** 等待用户确认的跳板机主机密钥, 语义同 [PendingHostKey], 只是指纹属于跳板机 */
data class PendingJumpHostKey(
    val draft: SshWorkspaceDraft,
    val actual: SshHostKey,
    val expected: SshHostKey?,
    val saveAfter: Boolean,
)
