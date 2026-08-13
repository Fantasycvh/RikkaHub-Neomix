package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.context.Navigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyUIMessage
import app.androidrik.ai.util.encodeBase64
import app.androidrik.common.android.appTempFolder
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.message.MessagePartBlock
import app.androidrik.rikkahub.ui.components.message.ThinkingStep
import app.androidrik.rikkahub.ui.components.message.groupMessageParts
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.richtext.generateMarkdownHtml
import app.androidrik.rikkahub.tikz.TikzRenderer
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.BitmapComposer
import app.androidrik.rikkahub.ui.components.ui.ChainOfThought
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import com.dokar.sonner.rememberToasterState
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import app.androidrik.rikkahub.utils.exportImage
import app.androidrik.rikkahub.utils.getActivity
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.escapeHtml
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull
import app.androidrik.rikkahub.utils.toLocalString
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }
    var includeAllVersions by remember { mutableStateOf(false) }
    // 导出的消息：默认当前选中版本（多选流程传筛选后消息）；开启后含分支/编辑历史的全部版本
    val exportMessages = remember(includeAllVersions, conversation, selectedMessages) {
        if (includeAllVersions) conversation.messageNodes.flatMap { it.messages }
        else selectedMessages
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))

                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.chat_page_export_include_all_versions))
                    },
                    trailingContent = {
                        Switch(
                            checked = includeAllVersions,
                            onCheckedChange = { includeAllVersions = it }
                        )
                    }
                )

                HorizontalDivider()

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        exportToMarkdown(context, conversation, exportMessages)
                        toaster.show(
                            markdownSuccessMessage,
                            type = ToastType.Success
                        )
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        }
                    )
                }

                val htmlSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "HTML")
                OutlinedCard(
                    onClick = {
                        exportToHtml(context, conversation, exportMessages)
                        toaster.show(
                            htmlSuccessMessage,
                            type = ToastType.Success
                        )
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_html))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_html_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.Earth, contentDescription = null)
                        }
                    )
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                            trailingContent = {
                                Switch(
                                    checked = imageExportOptions.expandReasoning,
                                    onCheckedChange = {
                                        imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                    }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                conversation = conversation,
                                                messages = exportMessages,
                                                settings = settings,
                                                options = imageExportOptions
                                            )
                                        }.onSuccess {
                                            toaster.show(
                                                imageSuccessMessage,
                                                type = ToastType.Success
                                            )
                                            onDismissRequest()
                                        }.onFailure {
                                            // 取消必须向上传播，否则手动关闭 sheet 会被误报为导出失败
                                            if (it is CancellationException) throw it
                                            it.printStackTrace()
                                            toaster.show(
                                                message = "Failed to export image: ${it.message}",
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
) {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"

    val sb = buildAnnotatedString {
        append("# ${conversation.title}\n\n")
        append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

        messages.forEach { message ->
            val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
            append("$role:\n\n")
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        append(part.text)
                        appendLine()
                    }

                    is UIMessagePart.Image -> {
                        append("![Image](${part.encodeBase64().getOrNull()?.base64})")
                        appendLine()
                    }

                    is UIMessagePart.Reasoning -> {
                        part.reasoning.lines()
                            .filter { it.isNotBlank() }
                            .map { "> $it" }
                            .forEach {
                                append(it)
                            }
                        appendLine()
                        appendLine()
                    }

                    is UIMessagePart.Tool -> {
                        append("**Tool**: `${part.toolName}`")
                        appendLine()
                        if (part.toolCallId.isNotBlank()) {
                            append("- Call ID: `${part.toolCallId}`")
                            appendLine()
                        }

                        append("Input:")
                        appendLine()
                        append("```json")
                        appendLine()
                        append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                        appendLine()
                        append("```")
                        appendLine()

                        if (part.output.isNotEmpty()) {
                            append("Output:")
                            appendLine()
                            part.output.forEach { outputPart ->
                                when (outputPart) {
                                    is UIMessagePart.Text -> {
                                        append("```text")
                                        appendLine()
                                        append(outputPart.text)
                                        appendLine()
                                        append("```")
                                        appendLine()
                                    }

                                    is UIMessagePart.Reasoning -> {
                                        outputPart.reasoning.lines()
                                            .filter { it.isNotBlank() }
                                            .forEach {
                                                append("> $it")
                                                appendLine()
                                            }
                                    }

                                    is UIMessagePart.Image -> {
                                        append("![Tool Image](${outputPart.encodeBase64().getOrNull()?.base64})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Document -> {
                                        append("[Document: ${outputPart.fileName}](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Video -> {
                                        append("[Video](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Audio -> {
                                        append("[Audio](${outputPart.url})")
                                        appendLine()
                                    }

                                    else -> {}
                                }
                            }
                        }
                        appendLine()
                    }

                    else -> {}
                }
            }
            appendLine()
            append("---")
            appendLine()
        }
    }

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray())
        }

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "text/markdown")

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** 匹配 ```tikz / ```tikzcd 等围栏代码块 */
private val TIKZ_FENCE_REGEX = Regex("""```(?:tikz|tikzpicture|tikzcd|tikztiming)[^\n]*\n([\s\S]*?)```""")

/** $$...$$ 块内嵌的 TikZ 图表特征（与 MathBlock.isDiagramLatex 一致） */
private val DIAGRAM_LATEX_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),
    Regex("""\\begin\{axis\}"""),
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

private val DOLLAR_TIKZ_REGEX = Regex("""\$\$([\s\S]*?)\$\$""")

/**
 * 将 markdown 中的 TikZ（代码围栏 + $$ 内嵌图表）渲染为内嵌 <svg>（矢量），
 * 其余 markdown 交给 generateMarkdownHtml。用占位符绕过 flexmark 对原始 HTML 的转义。
 */
private fun renderMarkdownWithTikz(content: String): String {
    val svgs = mutableListOf<String>()

    fun renderToPlaceholder(code: String): String? {
        val svg = TikzRenderer.render(code.trim()).getOrNull() ?: return null
        val placeholder = "%%TIKZ_PH_${svgs.size}%%"
        svgs.add(svg)
        return placeholder
    }

    // 1. 处理 $$...$$ 块内嵌的 TikZ 图表（\begin{tikzpicture}/\begin{tikzcd} 等）
    val afterDollar = DOLLAR_TIKZ_REGEX.replace(content) { m ->
        val latex = m.groupValues[1]
        if (DIAGRAM_LATEX_PATTERNS.any { it.containsMatchIn(latex) }) {
            renderToPlaceholder(latex) ?: m.value
        } else {
            m.value // 普通数学公式，保留
        }
    }

    // 2. 处理 ```tikz 围栏
    val afterFence = TIKZ_FENCE_REGEX.replace(afterDollar) { m ->
        renderToPlaceholder(m.groupValues[1]) ?: m.value
    }

    val html = generateMarkdownHtml(afterFence)
    var result = html
    svgs.forEachIndexed { i, svg ->
        result = result.replace("%%TIKZ_PH_${i}%%", svg)
    }
    return result
}

private fun exportToHtml(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
) {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.html"

    val sb = StringBuilder()
    sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<style>\n")
    sb.append(
        """
        body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background: #f5f6f8; color: #1a1a1a; line-height: 1.6; }
        h1 { font-size: 1.5em; margin-bottom: 4px; }
        .meta { color: #888; font-size: 0.85em; margin-bottom: 24px; }
        .message { border-radius: 10px; padding: 12px 16px; margin: 12px 0; }
        .message.user { background: #e8f0fe; border-left: 4px solid #4285f4; }
        .message.assistant { background: #ffffff; border-left: 4px solid #b0b0b0; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
        .role { font-weight: 700; margin-bottom: 6px; font-size: 0.9em; }
        .message.user .role { color: #1a5dc7; }
        .message.assistant .role { color: #555; }
        .tool { background: #fafafa; border: 1px solid #e5e5e5; border-radius: 6px; padding: 8px 12px; margin: 8px 0; }
        .tool-name { font-weight: 600; font-family: ui-monospace, monospace; font-size: 0.9em; }
        details { margin: 8px 0; background: #fff8e1; border-radius: 6px; padding: 8px 12px; }
        summary { cursor: pointer; font-weight: 600; color: #b26a00; }
        img { max-width: 100%; border-radius: 6px; }
        pre { background: #f0f0f0; padding: 10px; border-radius: 6px; overflow-x: auto; }
        code { background: #f0f0f0; padding: 2px 4px; border-radius: 4px; font-family: ui-monospace, monospace; font-size: 0.9em; }
        pre code { background: transparent; padding: 0; }
        """.trimIndent()
    )
    sb.append("\n</style>\n</head>\n<body>\n")

    sb.append("<h1>").append(conversation.title.escapeHtml()).append("</h1>\n")
    sb.append("<p class=\"meta\">Exported on ").append(LocalDateTime.now().toLocalString().escapeHtml()).append("</p>\n")

    messages.forEach { message ->
        val isUser = message.role == MessageRole.USER
        val roleClass = if (isUser) "user" else "assistant"
        val roleLabel = if (isUser) "User" else "Assistant"
        sb.append("<div class=\"message ").append(roleClass).append("\">\n")
        sb.append("<div class=\"role\">").append(roleLabel).append("</div>\n")
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    sb.append(renderMarkdownWithTikz(part.text))
                    sb.append('\n')
                }

                is UIMessagePart.Image -> {
                    part.encodeBase64().getOrNull()?.let { enc ->
                        sb.append("<img src=\"").append(enc.base64).append("\" alt=\"image\" />\n")
                    }
                }

                is UIMessagePart.Reasoning -> {
                    sb.append("<details>\n<summary>").append("思考过程".escapeHtml()).append("</summary>\n")
                    sb.append(generateMarkdownHtml(part.reasoning))
                    sb.append("\n</details>\n")
                }

                is UIMessagePart.Tool -> {
                    sb.append("<div class=\"tool\">\n")
                    sb.append("<div class=\"tool-name\">").append(part.toolName.escapeHtml()).append("</div>\n")
                    if (part.toolCallId.isNotBlank()) {
                        sb.append("<div style=\"color:#888;font-size:0.8em;\">Call ID: ").append(part.toolCallId.escapeHtml()).append("</div>\n")
                    }
                    sb.append("<div><strong>Input</strong></div>\n")
                    sb.append("<pre>").append(JsonInstantPretty.encodeToString(part.inputAsJson()).escapeHtml()).append("</pre>\n")
                    if (part.output.isNotEmpty()) {
                        sb.append("<div><strong>Output</strong></div>\n")
                        part.output.forEach { outputPart ->
                            when (outputPart) {
                                is UIMessagePart.Text -> {
                                    sb.append("<pre>").append(outputPart.text.escapeHtml()).append("</pre>\n")
                                }

                                is UIMessagePart.Reasoning -> {
                                    sb.append(generateMarkdownHtml(outputPart.reasoning)).append('\n')
                                }

                                is UIMessagePart.Image -> {
                                    outputPart.encodeBase64().getOrNull()?.let { enc ->
                                        sb.append("<img src=\"").append(enc.base64).append("\" alt=\"tool image\" />\n")
                                    }
                                }

                                is UIMessagePart.Document -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Document: ").append(outputPart.fileName.escapeHtml()).append("</a>\n")
                                }

                                is UIMessagePart.Video -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Video</a>\n")
                                }

                                is UIMessagePart.Audio -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Audio</a>\n")
                                }

                                else -> {}
                            }
                        }
                    }
                    sb.append("</div>\n")
                }

                else -> {}
            }
        }
        sb.append("</div>\n")
    }
    sb.append("</body>\n</html>")

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray(Charsets.UTF_8))
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "text/html")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions = ImageExportOptions()
) {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.png"
    val composer = BitmapComposer(scope)
    val activity = context.getActivity()
    if (activity == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to get activity", Toast.LENGTH_SHORT).show()
        }
        return
    }

    val bitmap = composer.composableToBitmap(
        activity = activity,
        width = 540.dp,
        screenDensity = density,
        content = {
            CompositionLocalProvider(LocalSettings provides settings) {
                ExportedChatImage(
                    conversation = conversation,
                    messages = messages,
                    options = options
                )
            }
        }
    )

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
        }

        // Save to gallery
        context.exportImage(activity, bitmap, filename)

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "image/png")
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to export image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } finally {
        bitmap.recycle()
    }
}

data class ImageExportOptions(val expandReasoning: Boolean = false)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ImageExportOptions = ImageExportOptions()
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toasterState
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = LocalDateTime.now().toLocalString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_foreground)
                        Image(
                            painter = painter,
                            contentDescription = "Logo",
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    messages.forEach { message ->
                        ExportedChatMessage(
                            message = message,
                            options = options,
                            prevMessage = messages.getOrNull(messages.indexOf(message) - 1)
                        )
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    prevMessage: UIMessage? = null,
    options: ImageExportOptions = ImageExportOptions()
) {
    if (message.parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) }
    // Always show model icon for assistant messages in exported images
    val showModelIcon = message.role == MessageRole.ASSISTANT && prevMessage?.role == MessageRole.USER
    val iconLabel = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    val groupedParts = remember(message.parts) { message.parts.groupMessageParts() }
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            ChainOfThought(
                                steps = block.steps,
                                collapsedVisibleCount = block.steps.size
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        ExportedReasoningStep(
                                            reasoning = step.reasoning,
                                            expanded = options.expandReasoning
                                        )
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        ExportedToolStep(
                                            tool = step.tool
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ContentBlock -> {
                        when (val part = block.part) {
                            is UIMessagePart.Text -> {
                                if (part.text.isNotBlank()) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        if (message.role == MessageRole.USER) {
                                            Card(
                                                shape = MaterialTheme.shapes.medium,
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        } else {
                                            if (settings.displaySetting.showAssistantBubble) {
                                                Card(
                                                    shape = MaterialTheme.shapes.medium,
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                ) {
                                                    MarkdownBlock(
                                                        content = part.text,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                MarkdownBlock(
                                                    content = part.text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is UIMessagePart.Image -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(part.url)
                                        .allowHardware(false)
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = "Image",
                                    modifier = Modifier
                                        .sizeIn(maxHeight = 300.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }

                            else -> {
                                // Other parts are not rendered in image export for now
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelIcon) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            AutoAIIcon(
                name = iconLabel,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
            )

            Text(
                text = iconLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    messageContent()
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            null
        },
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(
    tool: UIMessagePart.Tool
) {
    val memoryAction = runCatching {
        tool.inputAsJson().jsonObject["action"]?.jsonPrimitiveOrNull?.contentOrNull
    }.getOrNull()
    val title = when (tool.toolName) {
        "memory_tool" -> when (memoryAction) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "memory_tool" -> when (memoryAction) {
                        "create", "edit" -> HugeIcons.Book04
                        "delete" -> HugeIcons.Book02
                        else -> HugeIcons.Wrench01
                    }

                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    else -> HugeIcons.Wrench01
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false,
        content = null,
    )
}

private fun shareFile(context: Context, uri: Uri, mimeType: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via)
        )
    )
}
