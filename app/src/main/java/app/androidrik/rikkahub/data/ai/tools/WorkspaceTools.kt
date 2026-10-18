package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.intOrNull
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
import app.androidrik.document.DocumentFormat
import app.androidrik.document.DocumentReader
import app.androidrik.document.DocumentSlice
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.utils.generateUnifiedDiff
import app.androidrik.workspace.WorkspaceCommandResult
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceKillResult
import app.androidrik.workspace.WorkspaceManager
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L

/**
 * shell 工具的**外层**执行上限 = 命令上限（[SHELL_TIMEOUT_MAX_SECONDS]）+ 本次缓冲。
 *
 * 工具自带命令超时与进程树杀法（见 ProcessSignals），外层这层只兜「runner 自身卡死」
 * 这类不会正常返回的形态；缓冲是给进程树回收与输出读取留的余量——**必须大于命令上限**，
 * 否则正常的长命令会被外层误杀。
 */
private const val SHELL_TIMEOUT_BUFFER_MS = 15_000L

/**
 * 沙箱可用工具说明（写进 shell 类**工具描述**，与后台命令共用同一份措辞）。
 *
 * 模型默认按「完整 Linux 主机」假设解释器齐全，于是先写 `python3 -c ...` 再撞
 * `command not found`（日志实证）。Rootfs 是用户自行安装的精简镜像，预装什么因镜像
 * 而异 ⇒ 只能要求「先探测」，不能写死「没有 python3」（有的镜像确实有）。
 */
private const val SANDBOX_INTERPRETER_NOTE =
    "The Rootfs is a slim, user-installed Linux image: common Unix tools such as bash, perl, awk and sed " +
        "are usually present, but interpreters like python3 or node are NOT guaranteed. " +
        "Probe before relying on one (e.g. `command -v python3`); if it is missing, do the job with shell tools " +
        "or install it with the distribution's package manager instead of assuming it exists."

/** shell 的「找不到命令」退出码（bash/dash 对未知命令统一返回 127）。 */
private const val COMMAND_NOT_FOUND_EXIT_CODE = 127

/**
 * 退出码 127 时给模型的可执行提示：只回传 `command not found` 只能让模型自己猜下一步，
 * 这里直接指明「先探测 + 改用已有工具 / 先安装」。真实清单只能探测，故不写死。
 */
private const val COMMAND_NOT_FOUND_NOTE =
    "Exit code 127 means a command was not found. Probe the exact name with `command -v <name>` and use what " +
        "actually exists in this Rootfs (bash, perl, awk, sed and core Unix utilities are usually present; " +
        "python3, node and similar interpreters may not be); otherwise install it with the distribution's " +
        "package manager."

/** 后台任务查询结果里单流返回的最大字符数：尾部缓冲是 8K，再截一道避免轮询把上下文吃掉。 */
private const val TASK_TAIL_RETURN_CHARS = 4_000
/**
 * 单次读取上限的**默认值**：工作区可覆盖（`WorkspaceEntity.maxReadFileBytes`，工作区设置页可调）。
 * 保持 8MB 与迁移前一致 ⇒ 老工作区行为零变化。
 */
private const val DEFAULT_MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/** 窗口读取的硬上限（行）：挡住 `limit=10_000_000` 这类请求（字节上限另算） */
internal const val MAX_READ_LINES = 20_000

/** 文档单次读取的单元上限（页/张/章/块）：一页几千字，50 页就可能顶到字符上限 */
internal const val MAX_DOC_UNITS_PER_READ = 50
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
    /**
     * 本轮生成令牌：写进后台任务的归属，供「本轮被打断」时精确收掉这一轮起的任务
     * （见 [WorkspaceRepository.killBackgroundTasksByOwner]）。null = 不归属（子代理/预览等）。
     */
    turnOwner: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId)
    val approvalOverrides = workspace?.toolApprovalOverrides().orEmpty()
    // 单次读取上限按工作区取（非正值/读不到时回落到默认 8MB）
    val maxReadBytes = workspace?.maxReadFileBytes?.takeIf { it > 0 } ?: DEFAULT_MAX_READ_FILE_BYTES
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository, maxReadBytes),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createPresentFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createRunBackgroundTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd, turnOwner),
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
    maxReadBytes: Long,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files, image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico)
        and documents (pdf, docx, pptx, epub).
        For text files offset (1-based first line) and limit (max lines) read a window; the window is streamed,
        so files larger than the single-read limit can still be read this way.
        For documents offset/limit mean units: pdf pages, pptx slides, epub chapters, docx blocks of 100
        paragraphs (docx has no real pagination); the extracted text comes back with firstUnit/lastUnit/totalUnits
        so you can page through it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "1-based first unit to return: a text line for text files, a page/slide/chapter/block " +
                            "for documents (pdf/docx/pptx/epub). Read a window from a large file without " +
                            "loading all of it."
                    )
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum number of units to return (text files: lines, max $MAX_READ_LINES; " +
                            "documents: max $MAX_DOC_UNITS_PER_READ). Without offset it reads from the first unit."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        val window = normalizeReadWindow(
            offset = it.jsonObject["offset"]?.jsonPrimitive?.intOrNull,
            limit = it.jsonObject["limit"]?.jsonPrimitive?.intOrNull,
        )
        when {
            // 图片没有行/单元概念：明确拒绝，别让模型以为能"翻页"
            path.isImagePath() && window != null ->
                error("offset/limit do not apply to images: $path")

            path.isImagePath() -> workspaceRepository.readImageInRootfs(workspaceId, path, maxReadBytes)

            // 文档：offset/limit 是"单元"（页/张/章/块），默认读前 DEFAULT_DOC_UNITS_PER_READ 个单元
            DocumentFormat.of(path) != null -> {
                val format = DocumentFormat.of(path)!!
                val startUnit = window?.startLine?.toInt() ?: 1
                val unitCount = (window?.limit ?: DEFAULT_DOC_UNITS_PER_READ)
                    .coerceAtMost(MAX_DOC_UNITS_PER_READ)
                val slice = readDocument(workspaceRepository, workspaceId, path, startUnit, unitCount)
                val result = buildDocumentResult(
                    text = slice.text,
                    firstUnit = slice.firstUnit,
                    lastUnit = slice.lastUnit,
                    totalUnits = slice.totalUnits,
                )
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("format", format.name.lowercase())
                            put("unit", format.unitLabel)
                            put("text", result.text)
                            put("firstUnit", result.firstUnit)
                            put("lastUnit", result.lastUnit)
                            if (result.totalUnits >= 0) put("totalUnits", result.totalUnits)
                            put("truncated", result.truncated)
                            result.notice?.let { put("notice", it) }
                        }.toString()
                    )
                )
            }

            window != null -> {
                val read = workspaceRepository.readRootfsLineWindow(
                    workspaceId, path, window.startLine, window.limit,
                )
                val result = buildWindowResult(
                    window, read.lines, read.moreLines,
                    // 窗口自身的字节上限：Int 足够（>2GB 的窗口没有意义）
                    maxBytes = maxReadBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
                requireNotBinary(path, result.text)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", result.text)
                            put("startLine", result.startLine)
                            put("endLine", result.endLine)
                            put("truncated", result.truncated)
                            runCatching { workspaceRepository.rootfsFileMtime(workspaceId, path) }.getOrNull()
                                ?.let { put("mtime", it) }
                        }.toString()
                    )
                )
            }

            else -> {
                val text = workspaceRepository.readTextInRootfs(workspaceId, path, maxReadBytes)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", text)
                            // mtime 供后续 write_file 的 expectedMtimeMs 做冲突检测（读写闭环）
                            runCatching { workspaceRepository.rootfsFileMtime(workspaceId, path) }.getOrNull()
                                ?.let { put("mtime", it) }
                        }.toString()
                    )
                )
            }
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
        Concurrency guard: if the file changed on disk since the mtime you pass in `expectedMtimeMs` (e.g. another session wrote it),
        the write is rejected with WRITE_CONFLICT unless you set force=true; pass force=true to overwrite deliberately.
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
                put("expectedMtimeMs", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional: mtime (ms) you believe the file currently has (from a previous read/write result). Write is rejected if it changed meanwhile.")
                })
                put("force", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Deliberately overwrite despite a detected conflict. Defaults to false.")
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
        val expectedMtime = params["expectedMtimeMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val force = params["force"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        // 写冲突检测（Yuihub `f91d3b16` 同源）：调用方给了 expectedMtimeMs 且磁盘文件比它新
        // ⇒ 有其它会话/编辑器写过；未显式 force 时拒绝并给出双方时间戳，避免并发双写
        // 静默 last-writer-wins（子代理并行/用户同时开编辑器场景的兜底）
        if (expectedMtime != null && !force) {
            val currentMtime = runCatching {
                workspaceRepository.rootfsFileMtime(workspaceId, path)
            }.getOrNull()
            if (currentMtime != null && currentMtime > expectedMtime) {
                error(
                    "WRITE_CONFLICT: $path was modified by another session (disk mtime=$currentMtime, " +
                        "your expectedMtimeMs=$expectedMtime). Re-read the file and merge, or pass force=true to overwrite deliberately."
                )
            }
        }
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
        append("Requires Rootfs to be installed and ready. ")
        append(SANDBOX_INTERPRETER_NOTE)
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
    executionTimeoutMillis = SHELL_TIMEOUT_MAX_SECONDS * 1_000L + SHELL_TIMEOUT_BUFFER_MS,
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
                    // 失败也要给「下一步怎么做」：127 是模型最常撞的形态（写了不存在的解释器）
                    if (result.exitCode == COMMAND_NOT_FOUND_EXIT_CODE) put("note", COMMAND_NOT_FOUND_NOTE)
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
    turnOwner: String? = null,
) = Tool(
    name = "workspace_run_background",
    description = buildString {
        append("Start a shell command in the assistant's bound workspace Rootfs **in the background** and return a task id immediately. ")
        append("The command keeps running after this answer; its output is NOT included here, so a chatty command cannot flood the context window. ")
        append("Poll workspace_task_status for the tail of stdout/stderr and stop it with workspace_kill_task. ")
        append("Use this for long builds, downloads or training scripts; use workspace_shell when you need the output in this same turn. ")
        append("Interactive commands are NOT supported (stdin is closed, so anything prompting for input fails immediately) - run those with workspace_shell or the terminal. ")
        append("A task is stopped automatically when it exceeds its time limit, and the full output is written to ")
        append("/workspace/.tasks/<taskId>.stdout.log and .stderr.log inside the workspace, so use workspace_read_file if the reported tail is not enough. ")
        append("The workspace files area is mounted at /workspace. ")
        append(SANDBOX_INTERPRETER_NOTE)
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
        val task = workspaceRepository.startBackgroundCommand(workspaceId, command, cwd, owner = turnOwner)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", task.id)
                    put("status", task.status.name)
                    put("command", task.command.abbreviateForContext(COMMAND_PREVIEW_CHARS))
                    if (task.cwd.isNotBlank()) put("cwd", task.cwd)
                    putBackgroundTaskMeta(task, System.currentTimeMillis())
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
                    putBackgroundTaskMeta(task, now)
                    if (task.status == WorkspaceTaskStatus.TIMED_OUT) {
                        put(
                            "note",
                            "The task exceeded its time limit and was terminated. Split the work into " +
                                "shorter steps, or write incremental results to files as it goes.",
                        )
                    }
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

/**
 * 后台任务在工具结果里的公共字段：**时限**（还剩多久被自动终止）与**完整日志位置**。
 *
 * 两者都是给模型做决策用的：剩余时间决定「继续等」还是「先干别的」，日志路径决定
 * 「尾部不够用时去哪里读全量」——没有这两个字段时，模型只能盲猜或放弃。
 */
private fun JsonObjectBuilder.putBackgroundTaskMeta(task: WorkspaceTaskSnapshot, nowMillis: Long) {
    if (task.lifetimeLimitMillis > 0L) {
        put("lifetimeLimitMs", task.lifetimeLimitMillis)
        task.remainingMillis(nowMillis)?.let { put("expiresInMs", it) }
    }
    task.stdoutLogPath?.let { put("stdoutLogPath", it) }
    task.stderrLogPath?.let { put("stderrLogPath", it) }
}

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
    maxBytes: Long = DEFAULT_MAX_READ_FILE_BYTES,
): String {
    val text = readRootfsBuffer(workspaceId, path, maxBytes).toString(Charsets.UTF_8.name())
    requireNotBinary(path, text)
    return text
}

/**
 * U+FFFD 是 UTF-8 **有损解码**的产物：非文本字节被静默替换为 U+FFFD 后，
 * 「解码 → replaceText → 写回」会把损坏内容当原文写回、把整个文件写坏
 * （Yuihub `8a3a37ac` ISSUE-08 同源问题）。真实文本里出现 U+FFFD 的概率极低，
 * 检测到即按二进制拒绝，引导模型走 shell 处理。read_file 与 edit_file 共用本守卫。
 */
private fun requireNotBinary(path: String, text: String) {
    require('�' !in text) {
        "File looks like a binary file (invalid UTF-8): $path. Use shell commands instead."
    }
}

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
    maxBytes: Long,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= maxBytes) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${maxBytes / 1024 / 1024}MB). " +
            "Read a window instead with offset/limit, or use shell commands like head, tail, or grep."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

/**
 * 文档抽文本。
 *
 * 同步的原生/XML 解析放 IO 上（PDF 是 MuPDF 原生调用，几百页是秒级），失败**抛异常**并带上原因
 * ——模型据此知道"这份文件读不了"，而不是收到一段空文本（旧 `parse()` 把错误当字符串返回，
 * 这条路径特意不复用它，见 `DocumentReader` 的说明）。
 */
private suspend fun readDocument(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    path: String,
    startUnit: Int,
    unitCount: Int,
): DocumentSlice {
    val file = workspaceRepository.rootfsReadableFile(workspaceId, path)
    return withContext(Dispatchers.IO) {
        runCatching { DocumentReader.read(file, path, startUnit, unitCount) }
            .getOrElse { error ->
                error(
                    "Failed to extract text from $path (${DocumentFormat.of(path)?.name?.lowercase()}): " +
                        "${error.message ?: error::class.simpleName}"
                )
            }
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
    maxBytes: Long,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path, maxBytes).toByteArray()

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
