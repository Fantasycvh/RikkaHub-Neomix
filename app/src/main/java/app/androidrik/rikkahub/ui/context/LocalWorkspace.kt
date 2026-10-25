package app.androidrik.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前内容所属的工作区 ID，用于把 Markdown 引用的环境内绝对路径（如 /workspace/out.png）
 * 解析成宿主机文件；远程工作区经 WorkspaceBackend 解析。
 */
val LocalWorkspaceId = compositionLocalOf<String?> { null }
