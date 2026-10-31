package app.androidrik.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.PackageMirrorCatalog
import app.androidrik.workspace.PackageMirrorSetup
import app.androidrik.workspace.WorkspaceLayout
import app.androidrik.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 本地工作区同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令。
 * 远程工作区不读取 AGENTS.md, shell 与发行版读到一次后缓存 (见 [RemoteEnvironmentCache]),
 * 连不上时只注入一段说明, 不阻塞对话。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    private val remoteEnvironments = RemoteEnvironmentCache()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatToolFactory.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages
        val layout = workspaceRepository.layout(workspaceId) ?: return messages

        val prompt = if (workspace.isSsh()) {
            val environment = remoteEnvironments.get(workspaceId, workspace.sshConfig) {
                // shell 探测需要连接, 失败时不再读发行版, 以免再等一次超时
                RemoteEnvironment(
                    shell = workspaceRepository.rootfsShell(workspaceId),
                    distro = readDistroName(workspaceId),
                )
            }.getOrElse { e ->
                Log.w("WorkspaceReminder", "Workspace unreachable: $workspaceId", e)
                return messages.withPrompt(buildUnreachablePrompt(workspace, layout, e))
            }
            buildSshWorkspacePrompt(workspace, layout, environment.shell, environment.distro, ctx.workspaceCwd)
        } else {
            buildWorkspacePrompt(
                workspace = workspace,
                shell = workspaceRepository.rootfsShell(workspaceId),
                distro = readDistroName(workspaceId),
                cwd = ctx.workspaceCwd,
            ) + buildAgentsPrompt(workspaceId, ctx.workspaceCwd) +
                buildPackageMirrorPrompt(workspace.packageMirrorSetup())
        }
        return messages.withPrompt(prompt)
    }

    // 追加到第一条 system 消息; 若不存在则插入一条
    private fun List<UIMessage>.withPrompt(prompt: String): List<UIMessage> {
        val systemIndex = indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + this
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            readRootfsText(workspaceId, path, MAX_AGENTS_BYTES)?.let { path to it }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    /**
     * 从 Rootfs 的 `os-release` 读发行版名（对齐上游 `298e2771`）。
     *
     * 为什么值得告诉模型：提示词本身**不含** bash/apt 字样，于是模型只能**猜**——它会按最常见
     * 的 Ubuntu + apt 写命令。遇到非 Debian 系的 Rootfs（用户自制的精简/Alpine 等）时，
     * `apt-get` 一律失败。告知实际发行版能把这轮试错省掉。
     */
    private suspend fun readDistroName(workspaceId: String): String? =
        OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
            readRootfsText(workspaceId, path, MAX_OS_RELEASE_BYTES)?.let(::parseOsReleaseName)
        }

    /** 读 Rootfs 内文本文件；不存在、过大或不可读一律返回 null（提示词其余部分不受影响）。 */
    private suspend fun readRootfsText(workspaceId: String, path: String, maxBytes: Long): String? =
        try {
            val size = workspaceRepository.rootfsFileSize(workspaceId, path)
            require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportRootfsFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            content.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("WorkspaceReminder", "Skipping workspace file: $path", e)
            null
        }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
        const val MAX_OS_RELEASE_BYTES = 16L * 1024
        val OS_RELEASE_PATHS = listOf("/etc/os-release", "/usr/lib/os-release")
    }
}

/** 远程工作区提示词里描述的执行环境 */
internal data class RemoteEnvironment(val shell: String, val distro: String?)

/**
 * 按工作区缓存远程执行环境。
 *
 * 读一次要好几次网络往返, 而每一步请求都要构建提示词; 远程环境很少变化, 读到后就一直沿用, 直到连接配置改变。
 * 连不上的结果只保留 [failureTtlMillis]: 期间的后续步骤不必每步都等连接超时, 过期后再重新尝试。
 */
internal class RemoteEnvironmentCache(
    private val failureTtlMillis: Long = DEFAULT_FAILURE_TTL_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(val config: String?, val result: Result<RemoteEnvironment>, val loadedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(
        workspaceId: String,
        config: String?,
        load: suspend () -> RemoteEnvironment,
    ): Result<RemoteEnvironment> {
        entries[workspaceId]
            ?.takeIf { it.config == config && (it.result.isSuccess || now() - it.loadedAt < failureTtlMillis) }
            ?.let { return it.result }
        val result = try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        entries[workspaceId] = Entry(config, result, now())
        return result
    }

    private companion object {
        const val DEFAULT_FAILURE_TTL_MS = 60_000L
    }
}

/** 发行版名长度上限：内容取自 Rootfs 内的文件，拼进系统提示前必须设界。 */
private const val MAX_DISTRO_NAME_LENGTH = 80

/**
 * 从 `os-release` 内容解析发行版名：优先 `PRETTY_NAME`，其次 `NAME` + `VERSION_ID`。
 *
 * 内容来自 Rootfs 内的文件（**外部可写**）⇒ 拼进系统提示前去掉控制字符并截断长度，
 * 避免被塞进换行/转义把提示词结构搅乱。
 */
internal fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            // 键**也** trim：真实 os-release 写作 PRETTY_NAME="..."（无空格），但用户可手改
            // Rootfs 里的这个文件，`PRETTY_NAME = "..."` 这种写法不该解析不出（上游未 trim 键）
            // 值两侧的引号：`removeSurrounding` 处理成对引号；末尾 `trim` 兜住「值被换行截断、
            // 引号不成对」的情形（否则会把一个孤引号拼进系统提示）
            line.substringBefore('=').trim() to line.substringAfter('=')
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim('"', '\'')
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).ifBlank { null }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    shell: String,
    distro: String?,
    cwd: String? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    // 告知实际的 shell 与发行版：模型默认会按 Ubuntu + bash + apt 猜，遇到别的发行版就一路试错
    appendLine("- Shell: `$shell`. Commands run as root inside the Rootfs.")
    if (distro != null) {
        appendLine("- Distribution: $distro. Use that distribution's package manager and tools; do not assume another one.")
    }
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Built-in skills shipped with the app are mounted at `/builtin_skills/<skill-name>/` with the same layout. Treat `/builtin_skills` as READ-ONLY: you may read files and run scripts there, but never modify, overwrite, or delete anything. A skill in `/skills` with the same name overrides the built-in one.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

private fun buildSshWorkspacePrompt(
    workspace: WorkspaceEntity,
    layout: WorkspaceLayout,
    shell: String,
    distro: String?,
    cwd: String?,
): String = buildString {
    val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" } ?: "the remote host"
    appendLine("<workspace>")
    appendLine("You have access to a persistent remote workspace named \"${workspace.name}\" on a real machine, reached over SSH as `$address`.")
    appendLine("- The workspace directory is `${layout.workspaceDir}`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute paths on the remote machine (for example `${layout.workspaceDir}/notes.md`).")
    appendLine("- This is not a sandbox: commands run with the SSH user's real permissions and can affect other data and services on the machine. Avoid destructive or system-wide changes unless the user asks for them.")
    if (distro != null) {
        appendLine("- The remote operating system is $distro.")
    }
    appendShellLine(shell)
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_shell`: run shell commands on the remote machine (defaults to the workspace directory).")
    appendLine("- There are no file write or edit tools in this workspace. Create and modify files with `workspace_shell`, for example a quoted heredoc (`cat > file <<'EOF'`) for new content, or `sed`, `patch` or a short script for targeted edits. Prefer targeted edits over rewriting whole files.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

private fun buildUnreachablePrompt(workspace: WorkspaceEntity, layout: WorkspaceLayout, error: Throwable): String =
    buildString {
        val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" }
        appendLine("<workspace>")
        appendLine(
            "You are bound to a workspace named \"${workspace.name}\"" +
                (address?.let { " on `$it`" } ?: "") +
                " with directory `${layout.workspaceDir}`, but it is currently unreachable: ${error.message}"
        )
        appendLine("- Workspace tools will fail until the connection is restored. If the user asks you to work in the workspace, tell them it is unreachable.")
        append("</workspace>")
    }

private fun StringBuilder.appendShellLine(shell: String) {
    if (shell.substringAfterLast('/') == "bash") {
        appendLine("- `workspace_shell` runs commands with `$shell`.")
    } else {
        appendLine("- `workspace_shell` runs commands with `$shell`, a POSIX shell. Bash is not installed, so avoid bash-only syntax such as arrays, here-strings (`<<<`) and brace expansion.")
    }
}

/**
 * 国内镜像源已配置时追加的提示词（仅本地 rootfs 工作区；SSH 工作区 packageMirrors 恒为空配置）。
 *
 * 光告诉模型「源换好了」不够：没被自动覆盖到的下载（GitHub Release 预编译二进制、
 * Maven 依赖等）依旧会卡住，模型容易因此报「环境不可用」。这里把「慢/连不上就先换国内源
 * 重试」写成可执行的指令，并给出各管理器的具体写法与备选站。
 */
internal fun buildPackageMirrorPrompt(setup: PackageMirrorSetup): String {
    if (!setup.active) return ""
    return buildString {
        appendLine()
        appendLine("<package_mirrors>")
        appendLine("This workspace's package managers already point at fast domestic (China) mirrors — keep them; never switch a working install back to official endpoints.")
        if (setup.aptRepoUrl.isNotBlank()) {
            appendLine("- apt: `${setup.aptRepoUrl}` (already rewritten in the sources list; run `apt-get update` before the first install of a session)")
        }
        if (setup.npmRegistry.isNotBlank()) {
            appendLine("- npm: `${setup.npmRegistry}` (already in `/root/.npmrc`, mirrors prebuilt binaries too)")
        }
        if (setup.pipIndexUrl.isNotBlank()) {
            appendLine("- pip: `${setup.pipIndexUrl}` (already in `/etc/pip.conf`)")
        }
        if (setup.goProxy.isNotBlank()) {
            appendLine("- Go: `GOPROXY=${setup.goProxy},direct` (already exported)")
        }
        appendLine("A slow, hanging or unreachable download is a mirror problem, NOT a broken environment — retry once against a domestic mirror (see redirects below) with a raised timeout; if it still fails, report the exact error to the user instead of retrying further.")
        appendLine("One-off redirects: GitHub release/archive hangs → prefix `https://gh-proxy.com/`; npm `--registry=https://registry.npmmirror.com`; pip `-i ${setup.pipIndexUrl.ifBlank { "https://pypi.tuna.tsinghua.edu.cn/simple" }}`; Maven/Gradle deps → add `maven.aliyun.com/repository/public`; Gradle wrapper → rewrite the host to `mirrors.cloud.tencent.com`; apt → swap the `URIs:` line in `/etc/apt/sources.list.d/ubuntu.sources` then `apt-get update` (other mirrors: ${otherAptMirrors(setup)}).")
        appendLine("The base image ships without `ca-certificates`: on TLS errors run `apt-get install -y ca-certificates` first, then retry.")
        append("</package_mirrors>")
    }
}

/** 备选 apt 站：从候选清单里取，排除当前已生效的主机。 */
private fun otherAptMirrors(setup: PackageMirrorSetup): String {
    val currentHost = runCatching { URI(setup.aptRepoUrl).host }.getOrNull()
    return PackageMirrorCatalog.APT_SITES
        .asSequence()
        .map { it.host.removePrefix("http://").removePrefix("https://") }
        .distinct()
        .filter { it != currentHost }
        .joinToString(", ") { "http://$it" }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
