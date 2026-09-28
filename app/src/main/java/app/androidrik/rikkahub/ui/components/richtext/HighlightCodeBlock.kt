package app.androidrik.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.androidrik.highlight.HighlightTextColorPalette
import app.androidrik.highlight.buildHighlightText
import app.androidrik.highlight.CodeHighlightText
import app.androidrik.highlight.CodeHighlighter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.webview.WebView
import app.androidrik.rikkahub.ui.components.webview.WebViewContentCache
import app.androidrik.rikkahub.ui.components.webview.rememberWebViewState
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.modifier.onClick
import app.androidrik.rikkahub.ui.theme.AtomOneDarkPalette
import app.androidrik.rikkahub.ui.theme.AtomOneLightPalette
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.toDp
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10
private val PREVIEWABLE_LANGUAGES = setOf("html", "svg")
/** 触发 TikZ 图表渲染的代码围栏语言（normalizedLanguage 已小写化） */
private val TIKZ_FENCE_LANGS = setOf("tikz", "tikzpicture", "tikzcd", "tikztiming")

/** 内容是否含 TikZ/数学图表（无语言标注的 fenced block 兜底——AI 常生成 ``` 空标注 + $$...tlz...$$） */
private val TIKZ_CONTENT_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),  // tikzpicture / tikzcd / tikztiming...
    Regex("""\\begin\{axis\}"""),     // pgfplots
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

private fun isTikzContent(code: String): Boolean =
    TIKZ_CONTENT_PATTERNS.any { it.containsMatchIn(code) }

/** 剥掉外层 $$ / \[...\] 定界符（AI 常把 tikz 包在 $$...$$ 里放代码块） */
private fun stripTikzWrappers(code: String): String {
    val t = code.trim()
    return when {
        t.startsWith("$$") && t.endsWith("$$") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        else -> t
    }
}

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val normalizedLanguage = remember(language) { language.lowercase() }
    val canInlinePreview = completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES
    var previewMode by remember(canInlinePreview, code, normalizedLanguage) {
        mutableStateOf(canInlinePreview)
    }

    var isExpanded by remember(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(!settings.displaySetting.codeBlockAutoCollapse)
    }
    val autoWrap = settings.displaySetting.codeBlockAutoWrap
    val showLineNumbers = settings.displaySetting.showLineNumbers

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            // 截断模式("wt")覆盖已有文件：重复导出到同一 URI 时残留旧内容 → 清空重写（上游 #1841）
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it, "wt")?.use { outputStream ->
                        outputStream.write(code.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            HighlightCodeActions(
                language = language,
                scope = scope,
                clipboardManager = clipboardManager,
                code = code,
                createDocumentLauncher = createDocumentLauncher,
                navController = navController,
                completeCodeBlock = completeCodeBlock,
                previewMode = previewMode,
                canInlinePreview = canInlinePreview,
                onTogglePreviewMode = {
                    previewMode = !previewMode
                },
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                canInlinePreview && previewMode -> {
                    CodeBlockPreview(
                        code = code,
                        language = normalizedLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
                completeCodeBlock && normalizedLanguage == "mermaid" -> {
                    Mermaid(
                        code = code,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // TikZ 内容无需等待代码块闭合：AI 流式输出中 completeCodeBlock 可能为 false，
                // 但内容已含 \begin{tikzpicture} 即可渲染；闭合后再次触发会重新渲染完整版。
                // 语言标注（tikz/latex 等）或内容检测命中其一即可。
                (isTikzContent(code) || (completeCodeBlock && normalizedLanguage in TIKZ_FENCE_LANGS)) -> {
                    android.util.Log.i("TikzJax", "HighlightCodeBlock->TikzJax lang=$normalizedLanguage complete=$completeCodeBlock isTikz=${isTikzContent(code)} codeHead=${code.take(60)}")
                    TikzJax(
                        // AI 常在 ``` 块内包 $$...$$ 包裹 tikz，剥掉外层定界符再传给引擎
                        code = stripTikzWrappers(code),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    android.util.Log.i("TikzJax", "HighlightCodeBlock->ELSE lang=$normalizedLanguage complete=$completeCodeBlock isTikz=${code.let { isTikzContent(it) }} codeHead=${code.take(60)}")
                    val textStyle = LocalTextStyle.current.merge(style)
                    val codeLines = remember(code) { code.lines() }
                    val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
                    val displayCode = if (isExpanded) code else collapsedCode
                    val displayLines = remember(displayCode) { displayCode.lines() }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = displayCode,
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                                autoWrap = autoWrap,
                                showLineNumbers = showLineNumbers,
                                scrollState = scrollState,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    // 代码折叠按钮
                    if (settings.displaySetting.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES) {
                        Box(
                            modifier = Modifier
                                .onClick {
                                    isExpanded = !isExpanded
                                }
                                .fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(textStyle.fontSize.toDp())
                                )
                                Text(
                                    text = if (isExpanded) {
                                        stringResource(id = R.string.code_block_collapse)
                                    } else {
                                        stringResource(id = R.string.code_block_expand)
                                    },
                                    fontSize = textStyle.fontSize,
                                    lineHeight = textStyle.lineHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockWithLineNumbersWrapped(
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
) {
    val lineNumberWidth = remember(displayLines.size) {
        displayLines.size.toString().length
    }
    SelectionContainer {
        Column {
            displayLines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 行号不可选中：否则复制代码会带上行号（上游 324b337b）
                    DisableSelection {
                        Text(
                            text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                            fontSize = textStyle.fontSize,
                            lineHeight = textStyle.lineHeight,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            softWrap = false,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    CodeHighlightText(
                        code = line,
                        language = language,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        colors = colorPalette,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) {
                Modifier
            } else {
                Modifier.horizontalScroll(scrollState)
            }
        )
    ) {
        // 行号列
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) {
                displayLines.size.toString().length
            }
            Column(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                displayLines.forEachIndexed { index, _ ->
                    // 行号不可选中（同站点 1）
                    DisableSelection {
                        Text(
                            text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                            fontSize = textStyle.fontSize,
                            lineHeight = textStyle.lineHeight,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            softWrap = false,
                        )
                    }
                }
            }
        }

        // 代码列
        SelectionContainer {
            CodeHighlightText(
                code = displayCode,
                language = language,
                modifier = Modifier.animateContentSize(),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean = true,
    previewMode: Boolean = false,
    canInlinePreview: Boolean = false,
    onTogglePreviewMode: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = stringResource(id = R.string.chat_page_save),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        val extension = when (language.lowercase()) {
                            "kotlin" -> "kt"
                            "java" -> "java"
                            "python" -> "py"
                            "javascript" -> "js"
                            "typescript" -> "ts"
                            "cpp", "c++" -> "cpp"
                            "c" -> "c"
                            "html" -> "html"
                            "css" -> "css"
                            "xml" -> "xml"
                            "json" -> "json"
                            "yaml", "yml" -> "yml"
                            "markdown", "md" -> "md"
                            "sql" -> "sql"
                            "sh", "bash" -> "sh"
                            "svg" -> "svg"
                            else -> "txt"
                        }
                        createDocumentLauncher.launch(
                            "code_${
                                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            }.$extension"
                        )
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(id = R.string.code_block_copy),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                        }
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            val normalizedLanguage = language.lowercase()
            if (canInlinePreview) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Code else HugeIcons.View,
                    contentDescription = if (previewMode) "Code" else stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            onTogglePreviewMode()
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }

            if (completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES) {
                Icon(
                    imageVector = HugeIcons.Eye,
                    contentDescription = stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            val content = buildCodePreviewHtml(code = code, language = normalizedLanguage)
                            val contentId = WebViewContentCache.store(context.cacheDir, content)
                            navController.navigate(Screen.WebView(contentId = contentId))
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = buildCodePreviewHtml(code = code, language = language),
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    WebView(
        state = state,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
    )
}

private fun buildCodePreviewHtml(code: String, language: String): String {
    return if (language == "svg") {
        """<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
    } else {
        code
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: CodeHighlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                val tokens = highlighter.highlight(text.text, language)
                buildAnnotatedString {
                    tokens.forEach { token ->
                        buildHighlightText(token, colorPalette)
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }
}
