package me.rerere.bettertext.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.bettertext.text.BetterText

@Composable
internal fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    spacing: Dp = LocalMarkdownContext.current.theme.blockSpacing,
    firstBlockIndex: Int = 0,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        blocks.fastForEachIndexed { index, block ->
            MarkdownBlockNode(block = block, first = firstBlockIndex + index == 0)
        }
    }
}

// Nodes are immutable data classes: when a block equals the previous one this is skipped,
// so while streaming only the blocks that changed recompose.
@Composable
private fun MarkdownBlockNode(block: MarkdownBlock, first: Boolean) {
    val components = LocalMarkdownContext.current.components
    when (block) {
        is MarkdownBlock.Paragraph -> WithTextStyle(block.style) { InlineContent(block.content) }
        is MarkdownBlock.Heading -> HeadingBlock(block, first)
        is MarkdownBlock.CodeBlock -> components.codeBlock?.invoke(block) ?: SourceText(block.code)
        is MarkdownBlock.Quote -> QuoteBlock(block)
        is MarkdownBlock.ListBlock -> ListBlock(block)
        is MarkdownBlock.Table -> components.table?.invoke(block) ?: SourceText(block.source ?: block.plainText())
        is MarkdownBlock.Divider -> Divider()
        is MarkdownBlock.Details -> DetailsBlock(block)
        is MarkdownBlock.Container -> WithTextStyle(block.style) { MarkdownBlocks(block.children) }
    }
}

@Composable
private fun WithTextStyle(style: TextStyle?, content: @Composable () -> Unit) {
    if (style != null) ProvideMarkdownTextStyle(style, content) else content()
}

/** The current text color, or [alpha] of it. */
@Composable
private fun textColor(alpha: Float = 1f): Color {
    val color = LocalMarkdownTextStyle.current.color.takeOrElse { Color.Black }
    return color.copy(alpha = color.alpha * alpha)
}

/**
 * Renders inline Markdown content, such as a table cell, with the formulas,
 * links and styles of the surrounding [Markdown]. Only call it from a
 * component registered in [MarkdownComponents]. [style] is merged onto the
 * current text style.
 */
@Composable
fun MarkdownInlineContent(content: List<MarkdownInline>, modifier: Modifier = Modifier, style: TextStyle? = null) {
    if (style != null) {
        ProvideMarkdownTextStyle(style) { InlineContent(content, modifier, fillWidthToAlign = false) }
    } else {
        InlineContent(content, modifier, fillWidthToAlign = false)
    }
}

/**
 * Renders inline content: one BetterText, or segments in a FlowRow when the
 * content contains images or display formulas.
 */
@Composable
private fun InlineContent(
    content: List<MarkdownInline>,
    modifier: Modifier = Modifier,
    fillWidthToAlign: Boolean = true,
) {
    val segments = remember(content) { content.toSegments() }
    val textAlign = LocalMarkdownTextStyle.current.textAlign
    // Size to the content so a chat bubble doesn't grow to full width; only non-start alignment needs it.
    val alignedModifier = if (fillWidthToAlign && textAlign.needsFullWidth()) modifier.fillMaxWidth() else modifier

    when (val single = segments.singleOrNull()) {
        is InlineSegment.Text -> InlineText(single.content, alignedModifier)
        is InlineSegment.Math -> Box(modifier) { MathBlock(single.formula) }
        else -> FlowRow(
            modifier = alignedModifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp, textAlign.toHorizontalAlignment()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            segments.fastForEach { segment ->
                when (segment) {
                    is InlineSegment.Text -> InlineText(segment.content)
                    is InlineSegment.Image -> ImageSegment(segment.image, segment.link)
                    is InlineSegment.Math -> Box(Modifier.fillMaxWidth()) { MathBlock(segment.formula) }
                }
            }
        }
    }
}

private fun TextAlign.needsFullWidth() =
    this == TextAlign.Center || this == TextAlign.End || this == TextAlign.Right || this == TextAlign.Justify

private fun TextAlign.toHorizontalAlignment() = when (this) {
    TextAlign.Center -> Alignment.CenterHorizontally
    TextAlign.End, TextAlign.Right -> Alignment.End
    else -> Alignment.Start
}

@Composable
private fun MathBlock(formula: String) {
    val component = LocalMarkdownContext.current.components.mathBlock
    if (component != null) component(formula) else SourceText(formula)
}

/** The registered image component, or the alt text linking to the image. */
@Composable
private fun ImageSegment(image: MarkdownInline.Image, link: String?) {
    val component = LocalMarkdownContext.current.components.image
    if (component != null) {
        component(image, link)
        return
    }
    val context = LocalMarkdownContext.current
    val text = remember(image, link, context) {
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(link ?: image.url, linkInteractionListener = context.linkListener)) {
                append(image.alt ?: image.url)
            }
        }
    }
    BetterText(text, style = LocalMarkdownTextStyle.current.merge(context.theme.link))
}

/** Source text in monospace, for code and tables without a registered component. */
@Composable
private fun SourceText(source: String) {
    val style = LocalMarkdownTextStyle.current
    BetterText(
        AnnotatedString(source),
        style = style.merge(TextStyle(fontFamily = FontFamily.Monospace, lineHeight = TextUnit.Unspecified)),
    )
}

private fun MarkdownBlock.Table.plainText(): String =
    (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(" | ") { it.content.plainText() } }

@Composable
private fun Divider() {
    val color = LocalMarkdownContext.current.theme.dividerColor.takeOrElse { textColor(alpha = 0.2f) }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading, first: Boolean) {
    val theme = LocalMarkdownContext.current.theme
    // The heading size is relative to the body, and the block's own style to the heading.
    ProvideMarkdownTextStyle(theme.headings.getOrNull(block.level - 1) ?: TextStyle.Default) {
        WithTextStyle(block.style) {
            InlineContent(
                content = block.content,
                // Extra space above a heading keeps it closer to the content it introduces.
                modifier = Modifier
                    .padding(top = if (first) 0.dp else theme.blockSpacing / 2)
                    .semantics { heading() },
            )
        }
    }
}

@Composable
private fun QuoteBlock(block: MarkdownBlock.Quote) {
    val barColor = LocalMarkdownContext.current.theme.quoteColor.takeOrElse { textColor(alpha = 0.3f) }
    ProvideMarkdownTextStyle(TextStyle(color = textColor(alpha = 0.75f))) {
        MarkdownBlocks(
            blocks = block.children,
            modifier = Modifier
                .drawBehind {
                    val barWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
                .padding(start = 12.dp),
        )
    }
}

private val LocalListDepth = compositionLocalOf { 0 }
private val BULLETS = listOf("•", "◦", "▪")

@Composable
private fun ListBlock(block: MarkdownBlock.ListBlock) {
    val theme = LocalMarkdownContext.current.theme
    val depth = LocalListDepth.current
    val spacing = if (block.tight) theme.blockSpacing / 3 else theme.blockSpacing
    val markerStyle = LocalMarkdownTextStyle.current.merge(TextStyle(color = theme.markerColor.takeOrElse { textColor() }))
    CompositionLocalProvider(LocalListDepth provides depth + 1) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            block.items.fastForEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        item.checked != null -> TaskCheckbox(item.checked)
                        block.ordered -> BetterText("${block.start + index}.", style = markerStyle)
                        else -> BetterText(BULLETS[depth % BULLETS.size], style = markerStyle)
                    }
                    MarkdownBlocks(
                        blocks = item.children,
                        modifier = Modifier.weight(1f, fill = false),
                        spacing = spacing,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCheckbox(checked: Boolean) {
    val color = LocalMarkdownContext.current.theme.markerColor.takeOrElse { textColor() }
    val style = LocalMarkdownTextStyle.current
    val (boxSize, lineHeight) = with(LocalDensity.current) {
        val fontSize = style.fontSize.toDp()
        fontSize to if (style.lineHeight.isSpecified && style.lineHeight.isSp) style.lineHeight.toDp() else fontSize * 1.4f
    }
    // The outer box is one line tall, centering the checkbox on the first line of text.
    Box(
        modifier = Modifier.height(lineHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Spacer(
                    Modifier
                        .size(boxSize * 0.8f)
                        .drawBehind {
                            val path = Path().apply {
                                moveTo(size.width * 0.2f, size.height * 0.52f)
                                lineTo(size.width * 0.42f, size.height * 0.74f)
                                lineTo(size.width * 0.8f, size.height * 0.3f)
                            }
                            drawPath(
                                path,
                                color,
                                style = Stroke(width = size.width * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun DetailsBlock(block: MarkdownBlock.Details) {
    val theme = LocalMarkdownContext.current.theme
    val component = LocalMarkdownContext.current.components.details
    val summary: @Composable () -> Unit = {
        ProvideMarkdownTextStyle(TextStyle(fontWeight = FontWeight.Medium)) { InlineText(block.summary) }
    }
    val content: @Composable () -> Unit = { MarkdownBlocks(block.children) }
    if (component != null) {
        component(block, summary, content)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(theme.blockSpacing / 2)) {
            summary()
            content()
        }
    }
}
