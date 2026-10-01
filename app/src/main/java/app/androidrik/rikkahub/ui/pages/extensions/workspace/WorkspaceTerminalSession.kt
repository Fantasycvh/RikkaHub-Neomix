package app.androidrik.rikkahub.ui.pages.extensions.workspace

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.net.toUri
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.workspace.RootfsPatchOptions
import app.androidrik.workspace.RootfsPatcher
import java.io.File

internal fun createWorkspaceTerminalSession(
    context: Context,
    root: String,
    client: TerminalSessionClient,
    shellCompatibilityMode: Boolean = false,
): TerminalSession {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val filesDir = File(workspaceDir, "files")
    val linuxDir = File(workspaceDir, "linux")
    val tempDir = File(workspaceDir, "tmp")
    val skillsDir = File(appContext.filesDir, FileFolders.SKILLS).apply { mkdirs() }
    val builtinSkillsDir = File(appContext.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() }
    val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot_exec.so")
    val loader = File(nativeLibraryDir, "libproot_loader.so")

    // Shell 兼容模式：初始 cwd 移到 rootfs 内部 /root——PROOT_NO_SECCOMP 纯 ptrace 模式下
    // getcwd 在绑定挂载点（/workspace）反向解析会返回 ENOSYS，bash 启动即报
    // "shell-init: getcwd ... Function not implemented"；/root 为 rootfs 内部正向映射，无此问题
    val initialCwd = if (shellCompatibilityMode) "/root" else WORKSPACE_DIR

    val args = mutableListOf(
        "--root-id",
        "--link2symlink",
        "--kill-on-exit",
        "-r",
        linuxDir.absolutePath,
        "-w",
        initialCwd,
        "-b",
        "${filesDir.absolutePath}:$WORKSPACE_DIR",
        "-b",
        "${skillsDir.absolutePath}:$SKILLS_DIR",
        "-b",
        "${builtinSkillsDir.absolutePath}:$BUILTIN_SKILLS_DIR",
    )
    listOf("/dev", "/proc", "/sys").forEach { path ->
        if (File(path).exists()) {
            args += "-b"
            args += path
        }
    }
    args += listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=/bin/bash",
        "/bin/bash",
    )

    val env = mutableListOf(
        "PROOT_LOADER=${loader.absolutePath}",
        "PROOT_TMP_DIR=${tempDir.absolutePath}",
        "TMPDIR=${tempDir.absolutePath}",
    )
    // Shell 兼容模式：禁用 PRoot seccomp 加速（对齐上游 03246406）
    if (shellCompatibilityMode) {
        env += "PROOT_NO_SECCOMP=1"
    }

    return TerminalSession(
        proot.absolutePath,
        filesDir.absolutePath,
        args.toTypedArray(),
        env.toTypedArray(),
        2_000,
        client,
    ).apply {
        mSessionName = root
    }
}

internal fun prepareWorkspaceTerminalSession(context: Context, root: String) {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val linuxDir = File(workspaceDir, "linux")
    File(workspaceDir, "files").mkdirs()
    File(workspaceDir, "tmp").mkdirs()
    File(appContext.filesDir, FileFolders.SKILLS).mkdirs()
    RootfsPatcher().patch(
        linuxDir,
        RootfsPatchOptions(nameservers = appContext.activeDnsServers())
    )
}

internal fun workspaceRootfsReady(context: Context, root: String): Boolean {
    val linuxDir = File(File(File(context.applicationContext.filesDir, "workspaces"), root), "linux")
    return linuxDir.isDirectory && File(linuxDir, "bin/sh").isFile
}

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onTitleUpdated: (String?) -> Unit,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleUpdated(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    /**
     * 检测点击位置是否落在一个 URL 上, 是则用浏览器打开并返回 true.
     * TerminalView 0.118.0 没有内置链接点击, 这里基于 getColumnAndRow() + 屏幕缓冲文本自行实现,
     * 并通过 getLineWrap() 还原被软换行拆开的长 URL.
     */
    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val rows = emulator.mRows
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = rows - 1
        if (column < 0 || column >= columns) return false
        if (row < minAccessibleRow || row > maxAccessibleRow) return false

        // 向上/向下扩展到完整逻辑行(被软换行拆开的行 mLineWrap 为 true).
        // 限制最多扩展 URL_MAX_WRAP_ROWS 行: 真实 URL 跨不了这么多行, 同时避免连续无换行的
        // 长输出导致单次点击遍历整个 transcript.
        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (r in startRow..endRow) {
            if (r == row) {
                // 用 [0, column] 这段文本的长度精确换算点击字符在本行内的下标, 避免宽字符错位
                tapIndex = line.length + (screen.getSelectedText(0, r, column, r).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, r, columns - 1, r))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return dispatchTerminalUrl(context, url)
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}

private const val WORKSPACE_DIR = "/workspace"
private const val SKILLS_DIR = "/skills"
private const val BUILTIN_SKILLS_DIR = "/builtin_skills"

// 一个 URL 最多还原跨越的软换行行数(向上/向下各算), 足够覆盖任意真实 URL
private const val URL_MAX_WRAP_ROWS = 50

private val URL_REGEX =
    Regex(
        """(?i)(https?|ftp|ssh|git)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+|mailto:[\w\-._%+]+@[\w\-._]+\.[\w\-]+|file:///[\w\-._~/]+|git@[\w\-._]+:[\w\-._~/]+""",
    )

// 终端里 URL 后面常跟标点(行尾句号、被括号包裹等), 打开前去掉这些结尾字符
private val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

// 终端里点击 URL 的协议分发: 返回 true 表示已处理该次点击
private fun dispatchTerminalUrl(context: Context, url: String): Boolean {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return false
    return when {
        // 工作区本地文件: 复制完整路径, 跳转文件浏览器留给工作区联动阶段
        uri.scheme == "file" -> {
            copyText(context, url)
            Toast.makeText(context, "已复制路径: $url", Toast.LENGTH_SHORT).show()
            true
        }
        // git/ssh 仓库地址: 弹菜单(复制 / 打开浏览器), 直接 ACTION_VIEW 在多数设备没有处理器
        uri.scheme == "git" || uri.scheme == "ssh" || url.startsWith("git@") -> {
            showGitUrlMenu(context, url)
            true
        }
        uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "ftp" || uri.scheme == "mailto" -> {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrElse {
                Log.w("WorkspaceTerminal", "Failed to open url: $url", it)
                false
            }
        }
        else -> false
    }
}

// git@github.com:org/repo 这类 scp-like 地址没有 scheme, 转成 https 网页地址供浏览器打开
private fun gitUrlToHttp(url: String): String? {
    val trimmed = url.trimEnd(*URL_TRAILING_TRIM)
    val hostAndPath = when {
        trimmed.startsWith("git@") -> trimmed.removePrefix("git@").replaceFirst(':', '/')
        trimmed.startsWith("git://") -> trimmed.removePrefix("git://")
        trimmed.startsWith("ssh://") -> trimmed.removePrefix("ssh://")
        else -> return null
    }
    // 去掉 ssh://user@host:port 形式的 user@ 和端口段
    val authority = hostAndPath.substringBefore('/')
    val path = hostAndPath.substringAfter('/', missingDelimiterValue = "")
    val host = authority.substringAfter('@', authority).substringBefore(':')
    return "https://$host/$path"
}

private fun showGitUrlMenu(context: Context, url: String) {
    val dialogContext = context
    AlertDialog.Builder(dialogContext)
        .setTitle(url)
        .setItems(
            arrayOf("在浏览器打开", "复制到剪贴板", "取消"),
        ) { _, which ->
            when (which) {
                0 -> gitUrlToHttp(url)?.let { httpUrl ->
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, httpUrl.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        dialogContext.startActivity(intent)
                    }.onFailure {
                        Log.w("WorkspaceTerminal", "Failed to open git url: $httpUrl", it)
                    }
                }
                1 -> copyText(dialogContext, url)
            }
        }
        .show()
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
}

private fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val network = connectivityManager.activeNetwork ?: return emptyList()
    return connectivityManager.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}
