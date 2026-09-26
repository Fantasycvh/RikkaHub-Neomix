package app.androidrik.rikkahub.ui.components.message.tools

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import app.androidrik.ai.ui.DiffMetadata
import app.androidrik.ai.ui.metadataAs
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.highlight.CodeHighlightText
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Task01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.ui.components.richtext.DiffAddedColor
import app.androidrik.rikkahub.ui.components.richtext.DiffRemovedColor
import app.androidrik.rikkahub.ui.components.richtext.DiffView
import app.androidrik.rikkahub.ui.components.richtext.HighlightCodeBlock
import app.androidrik.rikkahub.ui.components.richtext.parseDiffStats
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.modifier.shimmer
import app.androidrik.rikkahub.utils.generateUnifiedDiff
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull
import app.androidrik.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

/**
 * 工作空间编辑文件: 摘要显示增删统计与精简 diff, 详情为完整 diff view
 */
object EditFileToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 10

    override val toolName: String = "workspace_edit_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_edit_file, path) else stringResource(R.string.tool_ui_edit_file_default)
    }

    /**
     * 执行后读取输出部件 metadata 中的全文件 diff;
     * 未执行 (如等待审批) 时基于入参的 old_text/new_text 片段生成预览 diff
     */
    private fun diffOf(context: ToolUIContext): String? {
        if (context.tool.isExecuted) {
            return context.tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff
        }
        val path = context.arguments.getStringContent("path") ?: return null
        val oldText = context.arguments.getStringContent("old_text") ?: return null
        val newText = context.arguments.getStringContent("new_text") ?: return null
        return generateUnifiedDiff(oldText, newText, path)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = diffOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = remember(context) { diffOf(context) } ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "+${stats.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffAddedColor,
            )
            Text(
                text = "-${stats.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffRemovedColor,
            )
        }
        DiffView(
            diff = diff,
            modifier = Modifier.fillMaxWidth(),
            maxLines = SUMMARY_MAX_LINES,
            showFileHeader = false,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { diffOf(context) }
        if (diff == null) {
            DefaultToolPreview(context = context)
            return
        }
        val stats = remember(diff) { parseDiffStats(diff) }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.arguments.getStringContent("path") ?: toolName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemovedColor,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 工作空间读取文件: 摘要显示内容首部预览, 详情为带语法高亮的完整内容
 */
object ReadFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_read_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_read_file, path) else stringResource(R.string.tool_ui_read_file_default)
    }

    /** 已执行时从输出 JSON 读取文件内容 */
    private fun textOf(context: ToolUIContext): String? =
        context.content.getStringContent("text")

    override fun hasSummary(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = remember(context) { textOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = remember(context) { textOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

/**
 * 工作空间写入文件: 内容取自入参 (未执行也可预览), 摘要为内容首部, 详情为完整内容
 */
object WriteFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_write_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileAdd

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_write_file, path) else stringResource(R.string.tool_ui_write_file_default)
    }

    private fun textOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("text")

    override fun hasSummary(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = remember(context) { textOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = remember(context) { textOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

/** 内联摘要: 按扩展名语法高亮展示文件内容首部若干行 */
@Composable
private fun FileContentSummary(text: String, path: String?, loading: Boolean) {
    val preview = remember(text) {
        text.lineSequence().take(FILE_SUMMARY_MAX_LINES).joinToString("\n")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        CodeHighlightText(
            code = preview,
            language = languageOf(path),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = FILE_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** BottomSheet 详情: 文件路径 + 按扩展名语法高亮的完整内容 */
@Composable
private fun FileContentPreview(path: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = path ?: stringResource(R.string.tool_ui_file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        HighlightCodeBlock(
            code = code,
            language = languageOf(path),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 工作空间执行 Shell: 摘要显示退出状态与输出首部, 详情为命令 + stdout/stderr
 */
object ShellToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = "workspace_shell"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command") ?: return stringResource(R.string.tool_ui_shell_default)
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return stringResource(R.string.tool_ui_shell, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val combined = remember(content) {
            listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
                .filterNot { it.isNullOrBlank() }
                .joinToString("\n")
                .let { limitLineLength(it) }
                .trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShellExitStatus(content, MaterialTheme.typography.labelSmall)
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = combined.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val command = context.arguments.getStringContent("command").orEmpty()
        val cwd = context.arguments.getStringContent("cwd")
        val stdout = content.getStringContent("stdout").orEmpty()
        val stderr = content.getStringContent("stderr").orEmpty()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_shell_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ShellExitStatus(content, MaterialTheme.typography.labelMedium)
            }
            HighlightCodeBlock(
                code = if (cwd.isNullOrBlank()) command else "# cwd: $cwd\n$command",
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            if (stdout.isNotEmpty()) {
                Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                HighlightCodeBlock(
                    code = limitLineLength(stdout),
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (stderr.isNotEmpty()) {
                Text(
                    text = "stderr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HighlightCodeBlock(
                    code = limitLineLength(stderr),
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun limitLineLength(text: String): String {
        // 对超长单行强制断行，避免单个无换行行撑爆 Compose 文本布局（二进制/压缩输出场景）
        return buildString {
            text.lineSequence().forEachIndexed { index, line ->
                if (index > 0) append('\n')
                if (line.length > LINE_MAX_CHARS) {
                    append(line.take(LINE_MAX_CHARS))
                    append("…") // …
                } else {
                    append(line)
                }
            }
        }
    }

    private const val LINE_MAX_CHARS = 512
}

/** Shell 退出状态文本: exit code 为 0 显示绿色, 超时或非零显示错误色 */
@Composable
private fun ShellExitStatus(content: JsonElement, style: androidx.compose.ui.text.TextStyle) {
    val exitCode = content.int("exitCode")
    val timedOut = content.boolean("timedOut") ?: false
    val ok = !timedOut && exitCode == 0
    Text(
        text = when {
            timedOut -> stringResource(R.string.tool_ui_shell_timeout)
            else -> stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?")
        },
        style = style,
        color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
    )
}

/** 从工具输出 JSON 读取布尔字段 */
private fun JsonElement?.boolean(key: String): Boolean? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.booleanOrNull

/** 从工具输出 JSON 读取整型字段 */
private fun JsonElement?.int(key: String): Int? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.intOrNull

/** 从工具输出 JSON 读取长整型字段 */
private fun JsonElement?.long(key: String): Long? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.longOrNull

private const val FILE_SUMMARY_MAX_LINES = 10

/** 由文件扩展名推断语法高亮语言 */
private fun languageOf(path: String?): String = when (
    path?.substringAfterLast('.', "")?.lowercase().orEmpty()
) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "php" -> "php"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss" -> "scss"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "md", "markdown" -> "markdown"
    "sql" -> "sql"
    "gradle" -> "groovy"
    else -> "plaintext"
}

/**
 * 工作空间发送文件（workspace_present_file）：AI 产出文件后以可见卡片交给用户。
 *
 * 折叠行标题 = 「发送文件 <name>」；展开摘要 = 文件卡片（名称/大小/note）+
 * 打开（跳工作区文件详情页）/ 导出（系统保存对话框）/ 分享（cache + FileProvider）。
 * 入参为 Rootfs 绝对路径；/workspace 前缀 → FILES 区，其余（/root 等）→ LINUX 区。
 */
object PresentFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_present_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileImport

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.arguments.getStringContent("path")
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return if (name != null) {
            stringResource(R.string.tool_ui_present_file, name)
        } else {
            stringResource(R.string.tool_ui_present_file_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val workspaceId = context.workspaceId
        val content = context.content ?: return
        val path = content.getStringContent("path") ?: return
        val name = content.getStringContent("name") ?: path.substringAfterLast('/')
        val note = content.getStringContent("note")
        val sizeBytes = content.long("sizeBytes")

        if (workspaceId == null) {
            Text(
                text = stringResource(R.string.tool_ui_present_file_no_workspace),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        PresentFileCard(
            workspaceId = workspaceId,
            path = path,
            name = name,
            note = note,
            sizeBytes = sizeBytes,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

@Composable
private fun PresentFileCard(
    workspaceId: String,
    path: String,
    name: String,
    note: String?,
    sizeBytes: Long?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val (area, relativePath) = resolvePresentPath(path)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                }
            }
        }
    }

    Surface(
        onClick = { navController.navigate(Screen.WorkspaceFileEditor(workspaceId, presentArea(path).name, presentRelativePath(path))) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.FileImport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (sizeBytes != null && sizeBytes > 0) {
                Text(
                    text = stringResource(R.string.tool_ui_present_file_size, formatBytes(sizeBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                TextButton(onClick = { exportLauncher.launch(name) }) {
                    Text(stringResource(R.string.tool_ui_present_file_export))
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val (area, relativePath) = resolvePresentPath(path)
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val cacheFile = File(dir, name)
                                cacheFile.outputStream().use { output ->
                                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    cacheFile,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.tool_ui_present_file_share))
                }
            }
        }
    }
}

/** present 文件 Rootfs 绝对路径 → (存储区, 相对路径)：/workspace 前缀 → FILES 区，其余 → LINUX 区。 */
private fun presentArea(path: String): WorkspaceStorageArea =
    if (path.startsWith("/workspace")) WorkspaceStorageArea.FILES else WorkspaceStorageArea.LINUX

private fun presentRelativePath(path: String): String =
    if (path.startsWith("/workspace")) path.removePrefix("/workspace").trimStart('/') else path.trimStart('/')

private fun resolvePresentPath(path: String): Pair<WorkspaceStorageArea, String> =
    presentArea(path) to presentRelativePath(path)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

// ---------- 后台任务三件套：workspace_run_background / _task_status / _kill_task ----------

/**
 * 后台命令**启动**卡片：标题为命令预览，摘要给出任务号与状态（或启动失败原因）。
 *
 * 刻意不展示输出：后台任务的输出根本不在启动结果里（这正是它不堵塞上下文的原因），
 * 卡片只需让用户看清「起了什么、任务号多少」；看输出用状态卡片。
 */
object BackgroundRunToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40

    override val toolName: String = "workspace_run_background"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Play

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command")
            ?: return stringResource(R.string.tool_ui_shell_bg_default)
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return stringResource(R.string.tool_ui_shell_bg, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val error = content.getStringContent("error")
        val taskId = content.getStringContent("taskId").orEmpty()
        val status = content.getStringContent("status").orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!error.isNullOrBlank()) {
                // 启动失败（rootfs 未装 / proot 缺失）当场可见，不必等用户轮询状态
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = listOf(taskId, status).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * 后台**状态**卡片：带 taskId 时显示状态/退出码与输出尾部，不带时显示任务列表。
 */
object TaskStatusToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = "workspace_task_status"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Task01

    @Composable
    override fun title(context: ToolUIContext): String {
        val taskId = context.arguments.getStringContent("taskId")?.trim()
        return if (taskId.isNullOrBlank()) {
            stringResource(R.string.tool_ui_task_status_list)
        } else {
            stringResource(R.string.tool_ui_task_status, taskId)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val error = content.getStringContent("error")
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        val status = content.getStringContent("status")
        val exitCode = content.int("exitCode")
        val lines = remember(content) {
            listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
                .filterNot { it.isNullOrBlank() }
                .joinToString("\n")
                .trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status != null) {
                TaskStatusLine(status = status, exitCode = exitCode)
            }
            if (lines.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = lines.lineSequence().toList().takeLast(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 后台**终止**卡片：标题为任务号，摘要为工具返回的结果说明。
 */
object KillTaskToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_kill_task"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Stop

    @Composable
    override fun title(context: ToolUIContext): String {
        val taskId = context.arguments.getStringContent("taskId")?.trim().orEmpty()
        return if (taskId.isBlank()) {
            stringResource(R.string.workspace_detail_tool_kill_task)
        } else {
            stringResource(R.string.tool_ui_kill_task, taskId)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val message = content.getStringContent("message") ?: return
        val status = content.getStringContent("status")
        val exitCode = content.int("exitCode")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = message, style = MaterialTheme.typography.labelSmall)
            if (status != null) {
                TaskStatusLine(status = status, exitCode = exitCode)
            }
        }
    }
}

/**
 * 后台任务状态行：状态名直接用工具返回的枚举（RUNNING/EXITED/KILLED/FAILED），
 * 与工具结果里的字面量保持一致——用户看到的和模型看到的就应该是同一个词。
 */
@Composable
private fun TaskStatusLine(status: String, exitCode: Int?) {
    val ok = status == "EXITED" && exitCode == 0
    val text = if (exitCode != null) "$status · exit $exitCode" else status
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        fontSize = 11.sp,
        color = when {
            status == "FAILED" -> MaterialTheme.colorScheme.error
            ok -> DiffAddedColor
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
