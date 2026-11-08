package app.androidrik.rikkahub.ui.components.richtext

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.ui.components.table.DataTable
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalWorkspaceId
import app.androidrik.rikkahub.ui.modifier.onClick
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.ui.theme.LocalChatTextGradient
import app.androidrik.rikkahub.ui.theme.toBrush
import app.androidrik.rikkahub.data.model.AppearanceGradient
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.bettertext.latex.LatexBlock
import me.rerere.bettertext.latex.LatexInline
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownComponents
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.MarkdownInlineContent
import me.rerere.bettertext.markdown.MarkdownTheme
import me.rerere.bettertext.markdown.TableCell
import me.rerere.bettertext.markdown.plainText
import me.rerere.bettertext.markdown.rememberMarkdownRenderDocument
import me.rerere.bettertext.text.BetterInline
import me.rerere.bettertext.text.ComposableInline
import me.rerere.bettertext.text.InlineAlign
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import org.koin.compose.koinInject
import kotlin.math.max
import kotlin.time.Clock
import me.rerere.bettertext.markdown.Markdown as BetterMarkdown

/**
 * 用 Material 3 主题渲染 Markdown：解析、排版、选择交给 BetterText。
 *
 * 与旧渲染链的区别：行内公式是 `LatexInline`（KaTeX 行内折行、基线对齐、行高随内容增高），
 * 自定义行内组件是 `ComposableInline`/`DrawnInline`，不再使用 Compose `InlineTextContent` 的固定占位符。
 *
 * @param streaming 内容还在生成时为 true，末尾没写完的语法（如 `**加粗`、`$\frac{`）会被补全或隐藏
 */
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
    streaming: Boolean = false,
    textGradient: AppearanceGradient? = LocalChatTextGradient.current,
    // Scope parser state by message + original text-part identity, independent of its content.
    documentKey: Any? = null,
) {
    val displaySetting = LocalSettings.current.displaySetting
    val enableLatexRendering = displaySetting.enableLatexRendering
    val enableDiagramRendering = displaySetting.enableDiagramRendering
    val fontSize = LocalTextStyle.current.merge(style).fontSize
    val density = LocalDensity.current
    val context = LocalContext.current
    val currentOnClickCitation by rememberUpdatedState(onClickCitation)

    val colorScheme = MaterialTheme.colorScheme
    val defaultTheme = remember(fontSize, colorScheme) { markdownTheme(fontSize, colorScheme) }
    val theme = remember(defaultTheme, fontSize, density) {
        defaultTheme.copy(
            inlineCode = defaultTheme.inlineCode.copy(
                fontFamily = JetbrainsMono,
                fontFeatureSettings = CODE_FONT_FEATURES,
            ),
            // 段落间距跟随字号，调整字体大小时保持比例
            blockSpacing = if (fontSize.isSp) with(density) { fontSize.toDp() } else defaultTheme.blockSpacing,
        )
    }

    val components = remember(enableLatexRendering, enableDiagramRendering) {
        MarkdownComponents(
            codeBlock = { block ->
                HighlightCodeBlock(
                    code = block.code,
                    language = block.language ?: "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                    completeCodeBlock = block.closed,
                )
            },
            image = { image, _ ->
                ZoomableAsyncImage(
                    model = rememberBetterWorkspaceImageUrl(image.url),
                    contentDescription = image.alt,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            },
            // 禁用 LaTeX 渲染时不注册公式组件，公式以等宽字体显示源码
            mathBlock = if (enableLatexRendering) {
                { formula -> FormulaBlock(latex = formula, modifier = Modifier.fillMaxWidth()) }
            } else {
                null
            },
            table = { table -> TableCard(table) },
            details = MarkdownDefaults.components.details,
            inlineMath = if (enableLatexRendering) {
                { formula -> LatexInline.cached(formula, context) }
            } else {
                null
            },
            inlineLink = { link -> citationWidget(link) { currentOnClickCitation(it) } },
        )
    }

    val merged = LocalTextStyle.current.merge(style)
    val solid = merged.copy(color = merged.color.takeOrElse { LocalContentColor.current })
    val brush = remember(textGradient) { textGradient?.toBrush() }
    val resolved = if (brush != null) solid.copy(brush = brush) else solid
    // 渲染前两步归一化（顺序敏感）：
    // 1. LatexEnvironments.normalize 给裸的 \begin{tikzpicture}/\begin{align} 等补 $$ 定界符
    //    （与导出路径 MarkdownHtml.preProcess 同步；BetterText 迁移时聊天路径漏了这步，
    //    裸环境直接以普通段落显示源码）；
    // 2. normalizeDisplayMath 折叠 $$…$$ 内空行——BetterText 的 GFM block math 遇空行会
    //    结束公式块，模型常在块内用空行分隔 \node/\draw。必须后跑，否则刚补上的 $$ 块
    //    内部仍是带空行的原样。
    val normalizedContent = remember(content) {
        normalizeDisplayMath(LatexEnvironments.normalize(content))
    }
    // 插槽里的组件（代码块、表格工具栏等）用 Material 的 Text，让它们也拿到正文样式
    ProvideTextStyle(resolved) {
        key(documentKey) {
            val document = rememberMarkdownRenderDocument(normalizedContent, streaming = streaming)
            BetterMarkdown(
                document = document.document,
                modifier = modifier.padding(horizontal = 4.dp),
                style = resolved,
                theme = theme,
                components = components,
            )
        }
    }
}

/** 块级公式：TikZ 图块继续走原来的 TikzJax，普通公式用 BetterText 的 KaTeX 排版（超宽横向滚动）。 */
@Composable
private fun FormulaBlock(latex: String, modifier: Modifier = Modifier) {
    val displaySetting = LocalSettings.current.displaySetting
    val formula = stripFormulaWrappers(latex)
    if (displaySetting.enableDiagramRendering && isDiagramFormula(formula)) {
        TikzJax(code = formula, modifier = modifier)
        return
    }
    val style = me.rerere.bettertext.markdown.LocalMarkdownTextStyle.current
    LatexBlock(
        latex = formula,
        modifier = modifier.padding(8.dp),
        fontSize = style.fontSize,
        color = style.color.takeOrElse { LocalContentColor.current },
    )
}

/** 判定公式是否是需要 TikZ 渲染的图表代码（tikzcd/tikzpicture/pgfplots/xymatrix/CD） */
private val DIAGRAM_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),  // tikzpicture / tikzcd / tikztiming...
    Regex("""\\begin\{axis\}"""),     // pgfplots
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

private fun isDiagramFormula(latex: String): Boolean =
    DIAGRAM_PATTERNS.any { it.containsMatchIn(latex) }

/** 去掉 Markdown 块公式的 $$ / \[...\] 外层包裹（TikZ 渲染需要裸环境） */
private fun stripFormulaWrappers(latex: String): String {
    val trimmed = latex.trim()
    return when {
        trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 ->
            trimmed.substring(2, trimmed.length - 2).trim()
        trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 ->
            trimmed.substring(2, trimmed.length - 2).trim()
        else -> trimmed
    }
}

/**
 * 绑定了工作区时, 把 Rootfs 绝对路径 (如 /workspace/out.png) 解析成宿主机上的文件路径;
 * 解析期间返回 null 显示占位图, 找不到文件时原样返回交给 Coil。
 *
 * Markdown 解析器会对 src 做百分号编码 (空格、中文等), 需取解码后的 path 并丢掉 query/fragment
 */
@Composable
private fun rememberBetterWorkspaceImageUrl(url: String): String? {
    val workspaceId = LocalWorkspaceId.current
    if (workspaceId == null || !url.startsWith("/") || url.startsWith("//")) return url
    val path = remember(url) { url.toUri().path } ?: return url
    val workspaceRepository: WorkspaceRepository = koinInject()
    val resolved by produceState<String?>(initialValue = null, workspaceId, path) {
        value = workspaceRepository.resolveAbsoluteFile(workspaceId, path)?.absolutePath ?: url
    }
    return resolved
}

// 搜索引用在正文里写作 [citation,域名](引用id)，渲染成可点击的角标
private fun citationWidget(link: MarkdownInline.Link, onClick: (String) -> Unit): BetterInline? {
    val text = link.children.plainText()
    if (!text.startsWith("citation,")) return null
    val domain = text.substringAfter("citation,")
    val id = link.destination
    if (id.length != 6) {
        // id 不合法的引用没法跳转，不显示
        return ComposableInline {}
    }
    return ComposableInline(align = InlineAlign.Center) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onClick(id.trim()) }
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = domain,
                style = TextStyle(
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

/** Material 3 的 Markdown 主题：标题字号按正文字号等比放大 */
private fun markdownTheme(
    fontSize: androidx.compose.ui.unit.TextUnit,
    colorScheme: androidx.compose.material3.ColorScheme,
): MarkdownTheme {
    val headingFontSize = if (fontSize.isSp) fontSize else 16.sp
    return MarkdownTheme(
        headings = HeadingScales.map { scale ->
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = headingFontSize * scale,
                lineHeight = headingFontSize * (scale * 1.25f),
            )
        },
        link = SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
        inlineCode = SpanStyle(
            color = colorScheme.primary,
            fontFamily = JetbrainsMono,
            fontSize = 0.9.em,
        ),
        blockSpacing = 12.dp,
        markerColor = colorScheme.primary,
        quoteColor = colorScheme.tertiary,
        dividerColor = colorScheme.outlineVariant,
    )
}

private object MarkdownDefaults {
    val components = MarkdownComponents(
        details = { block, summary, content -> Details(block, summary, content) },
    )
}

private val HeadingScales = floatArrayOf(1.5f, 1.375f, 1.25f, 1.125f, 1f, 0.875f)

/** 根据 open 属性初始化折叠状态，正文更新时保留用户的展开选择。 */
@Composable
private fun Details(
    block: MarkdownBlock.Details,
    summary: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 流式输出中 open 属性可能后到；只在它变化时重新初始化状态。
    var expanded by rememberSaveable(block.open) { mutableStateOf(block.open) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f)
    Column(modifier) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
            Column(Modifier.weight(1f, fill = false)) {
                summary()
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 22.dp, top = 6.dp)) {
                content()
            }
        }
    }
}

/** 表格卡片（工具栏 + 表格），工具栏提供复制 Markdown / 导出 CSV。 */
@Composable
private fun TableCard(table: MarkdownBlock.Table) {
    val columnCount = max(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    val headers: List<@Composable () -> Unit> = table.header.mapIndexed { index, cell ->
        @Composable {
            TableCellContent(
                cell = cell,
                columnAlign = table.alignments.getOrNull(index),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    val rows: List<List<@Composable () -> Unit>> = table.rows.map { row ->
        List(columnCount) { index ->
            @Composable {
                val cell = row.getOrNull(index)
                if (cell != null) {
                    TableCellContent(
                        cell = cell,
                        columnAlign = table.alignments.getOrNull(index),
                        fontWeight = null,
                    )
                }
            }
        }
    }

    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val tableCsv = remember(table) {
        buildTableCsv(
            headerCells = table.header.map { it.content.plainText() },
            rows = table.rows.map { row -> row.map { it.content.plainText() } },
        )
    }
    // HTML 写出的表格没有 Markdown 源码，复制时退回到 CSV
    val tableText = table.source ?: tableCsv

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(tableCsv.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.markdown_table_title),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            scope.launch {
                                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableText)))
                            }
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )

                Icon(
                    imageVector = HugeIcons.Download04,
                    contentDescription = "Download",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            createDocumentLauncher.launch(
                                "table_${
                                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                }.csv"
                            )
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
        DataTable(
            headers = headers,
            rows = rows,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

@Composable
private fun TableCellContent(
    cell: TableCell,
    columnAlign: TextAlign?,
    fontWeight: FontWeight?,
) {
    val columnStyle = TextStyle(
        fontWeight = fontWeight,
        textAlign = columnAlign ?: TextAlign.Unspecified,
    )
    // 单元格自己的样式优先于列的对齐方式和表头的字重
    MarkdownInlineContent(cell.content, style = columnStyle.merge(cell.style))
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(headerCells: List<String>, rows: List<List<String>>): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(headerCells.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it) })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BetterMarkdownPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides app.androidrik.rikkahub.data.datastore.Settings()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MarkdownBlock(
                    content = """
                        矩阵 ${'$'}\begin{pmatrix} a & b \\ c & d \end{pmatrix}${'$'} 的行列式是 ${'$'}ad - bc${'$'}。
                        连分数 ${'$'}\cfrac{1}{1 + \cfrac{1}{1 + x}}${'$'} 和 ${'$'}\sqrt{\begin{matrix} 1 \\ 2 \\ 3 \end{matrix}}${'$'} 会把所在的行撑高。
                    """.trimIndent()
                )
            }
        }
    }
}
