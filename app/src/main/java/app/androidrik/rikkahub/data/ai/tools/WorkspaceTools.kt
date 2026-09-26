package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.DiffMetadata
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.utils.generateUnifiedDiff
import app.androidrik.workspace.WorkspaceCommandResult
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceKillResult
import app.androidrik.workspace.WorkspaceManager
import app.androidrik.workspace.WorkspaceTaskStatus
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L

/** 后台任务查询结果里单流返回的最大字符数：尾部缓冲是 8K，再截一道避免轮询把上下文吃掉。 */
private const val TASK_TAIL_RETURN_CHARS = 4_000
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
/** rootfs 写入最大字节数（对齐 WorkspaceFileSystem 的 2MB require） */
private const val MAX_ROOTFS_WRITE_BYTES = 2 * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_present_file" to false,
    "workspace_shell" to true,
    // 后台命令会持续占用设备资源且模型看不见它的输出，默认要人确认；
    // 终止同理（终止是破坏性操作，且模型可能误杀别的任务）。
    // 查询只读、无副作用，默认免审批——否则长任务轮询会变成审批轰炸。
    "workspace_run_background" to true,
    "workspace_kill_task" to true,
    "workspace_task_status" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createPresentFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createRunBackgroundTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createTaskStatusTool(workspaceId, ::needsApproval, workspaceRepository),
        createKillTaskTool(workspaceId, ::needsApproval, workspaceRepository),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

/**
 * 发送文件给用户：校验文件存在后输出 JSON，由聊天界面渲染为可见的文件卡片，
 * 用户可对其打开/导出/分享（复用 Tool part，按 toolName 分派渲染）。
 */
private fun createPresentFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_present_file",
    description = """
        Send a file to the user as a visible file card in the chat, so they can open, export or share it.
        Use this whenever you produce a file the user should receive (APK, HTML, images, documents, archives...).
        Paths must be absolute inside Rootfs; use /workspace for the workspace files area.
        Call it after the file is fully written; include a short note describing the file.
        Note: the file card is rendered by the app automatically; do not paste the file content in your reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional short note shown with the file, e.g. what it is or how to use it")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_present_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val note = params.string("note").orEmpty()
        // 校验文件存在并取大小；不存在时 rootfsFileSize 抛错，工具结果按失败处理
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("name", path.substringAfterLast('/').ifBlank { path })
                    put("sizeBytes", size)
                    if (note.isNotBlank()) put("note", note)
                }.toString()
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

/**
 * 后台命令：立即返回任务 id，输出不进本轮上下文。
 *
 * 与 `workspace_shell` 的分工写在描述里，因为模型选择哪一个是这个功能的关键：
 * 短命令要输出 ⇒ shell；长命令/要等很久 ⇒ 后台 + 轮询。
 */
private fun createRunBackgroundTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_run_background",
    description = buildString {
        append("Start a shell command in the assistant's bound workspace Rootfs **in the background** and return a task id immediately. ")
        append("The command keeps running after this answer; its output is NOT included here, so a chatty command cannot flood the context window. ")
        append("Poll workspace_task_status for the tail of stdout/stderr and stop it with workspace_kill_task. ")
        append("Use this for long builds, downloads or training scripts; use workspace_shell when you need the output in this same turn. ")
        append("The workspace files area is mounted at /workspace.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run in the background")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_run_background") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val task = workspaceRepository.startBackgroundCommand(workspaceId, command, cwd)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", task.id)
                    put("status", task.status.name)
                    put("command", task.command.abbreviateForContext(COMMAND_PREVIEW_CHARS))
                    if (task.cwd.isNotBlank()) put("cwd", task.cwd)
                    if (task.status == WorkspaceTaskStatus.FAILED) {
                        // 启动失败必须当场把原因给模型：任务从未运行，轮询也不会有新信息
                        put("error", task.stderr.ifBlank { "Task failed to start" })
                    } else {
                        put(
                            "note",
                            "Running in the background. Poll workspace_task_status(taskId=\"${task.id}\") " +
                                "for its output tail and exit code.",
                        )
                    }
                }.toString()
            )
        )
    },
)

/** 后台任务状态：带 taskId 查单个（含输出尾部），不带则列出本工作空间全部任务。 */
private fun createTaskStatusTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_task_status",
    description = "Check background tasks started with workspace_run_background. " +
        "With taskId: its status, exit code, duration and the tail of stdout/stderr. " +
        "Without taskId: a summary list of this workspace's tasks. Read-only, so it is safe to poll.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id from workspace_run_background. Omit to list all tasks.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_task_status") },
    execute = {
        val taskId = it.jsonObject.string("taskId")?.trim().orEmpty()
        val text = if (taskId.isBlank()) {
            val now = System.currentTimeMillis()
            val tasks = workspaceRepository.listBackgroundTasks(workspaceId)
            buildJsonObject {
                putJsonArray("tasks") {
                    tasks.forEach { task ->
                        addJsonObject {
                            put("taskId", task.id)
                            put("status", task.status.name)
                            task.exitCode?.let { code -> put("exitCode", code) }
                            put("command", task.command.abbreviateForContext(COMMAND_PREVIEW_CHARS))
                            put("durationMs", task.durationMillis(now))
                            put("stdoutChars", task.stdoutTotalChars)
                            put("stderrChars", task.stderrTotalChars)
                        }
                    }
                }
                put("running", tasks.count { it.isRunning })
            }.toString()
        } else {
            val task = workspaceRepository.getBackgroundTask(workspaceId, taskId)
            if (task == null) {
                buildJsonObject {
                    put("error", "Unknown taskId: $taskId")
                    put("note", "Task ids live in memory only: they disappear when the app restarts.")
                }.toString()
            } else {
                val now = System.currentTimeMillis()
                buildJsonObject {
                    put("taskId", task.id)
                    put("status", task.status.name)
                    task.exitCode?.let { code -> put("exitCode", code) }
                    put("command", task.command.abbreviateForContext(COMMAND_PREVIEW_CHARS))
                    put("durationMs", task.durationMillis(now))
                    put("stdout", task.stdout.tailForContext(TASK_TAIL_RETURN_CHARS))
                    put("stderr", task.stderr.tailForContext(TASK_TAIL_RETURN_CHARS))
                    put("stdoutChars", task.stdoutTotalChars)
                    put("stderrChars", task.stderrTotalChars)
                    if (task.outputTruncated) {
                        put("outputTruncated", true)
                        put(
                            "note",
                            "Older output was discarded; only the tail is kept. Write results to files in the " +
                                "workspace instead of relying on command output.",
                        )
                    }
                }.toString()
            }
        }
        listOf(UIMessagePart.Text(text))
    },
)

/** 终止后台任务：`RUNNING` ⇒ 强杀；已结束/未知 id 也有明确文案，避免模型反复重试。 */
private fun createKillTaskTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_kill_task",
    description = "Terminate a running background task started with workspace_run_background.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_run_background")
                })
            },
            required = listOf("taskId"),
        )
    },
    needsApproval = { needsApproval("workspace_kill_task") },
    execute = {
        val taskId = it.jsonObject.string("taskId")?.trim().orEmpty()
        if (taskId.isBlank()) error("taskId is required")
        val result = workspaceRepository.killBackgroundTask(workspaceId, taskId)
        val message = when (result) {
            WorkspaceKillResult.KILLED -> "Termination signal sent to $taskId."
            WorkspaceKillResult.ALREADY_FINISHED -> "$taskId had already finished; nothing to kill."
            WorkspaceKillResult.NOT_FOUND ->
                "Unknown taskId: $taskId (task ids live in memory only, and finished tasks expire)"
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", taskId)
                    put("result", result.name)
                    put("message", message)
                    workspaceRepository.getBackgroundTask(workspaceId, taskId)?.let { task ->
                        put("status", task.status.name)
                        task.exitCode?.let { code -> put("exitCode", code) }
                    }
                }.toString()
            )
        )
    },
)

/** 列表里的命令预览长度：只需让模型认出是哪个任务。 */
private const val COMMAND_PREVIEW_CHARS = 200

private fun String.abbreviateForContext(maxChars: Int): String {
    val oneLine = replace("\n", " ").trim()
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
}

/** 尾部缓冲内容本身就以尾部为准，这里只做「单流返回上限」再截一刀。 */
private fun String.tailForContext(maxChars: Int): String =
    if (length <= maxChars) this else "…(truncated)…\n" + takeLast(maxChars)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    // rootfs 写路径（shell cat >）曾绕过 WorkspaceFileSystem 的 2MB require，超大内容直接灌入
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_ROOTFS_WRITE_BYTES) {
        "File content exceeds ${MAX_ROOTFS_WRITE_BYTES / 1024 / 1024}MB limit"
    }
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = bytes,
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
