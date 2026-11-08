package me.rerere.bettertext.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.bettertext.text.BetterInline
import me.rerere.bettertext.text.BetterText
import me.rerere.bettertext.text.appendInline

internal class MarkdownContext(
    val theme: MarkdownTheme,
    val components: MarkdownComponents,
    /** Null opens links with the system UriHandler. */
    val linkListener: LinkInteractionListener?,
)

internal val LocalMarkdownContext = staticCompositionLocalOf<MarkdownContext> {
    error("LocalMarkdownContext is only available inside Markdown()")
}

/** The font size when none is given, the same as [BetterText]'s. */
internal val DefaultFontSize = 14.sp

private val ProvidableMarkdownTextStyle = compositionLocalOf { TextStyle(fontSize = DefaultFontSize) }

/**
 * The text style of the current block, such as a heading or a quote, for components to match,
 * like Material's `LocalTextStyle` but without depending on it. Its font size is always in sp;
 * its color is unspecified when the style given to [Markdown] has none.
 */
val LocalMarkdownTextStyle: CompositionLocal<TextStyle> = ProvidableMarkdownTextStyle

/** Merges [style] onto the current text style for [content]; an em font size is relative to the current one. */
@Composable
internal fun ProvideMarkdownTextStyle(style: TextStyle, content: @Composable () -> Unit) {
    val current = LocalMarkdownTextStyle.current
    val resolved = if (style.fontSize.isEm) style.copy(fontSize = current.fontSize * style.fontSize.value) else style
    CompositionLocalProvider(ProvidableMarkdownTextStyle provides current.merge(resolved), content)
}

/** Renders inline nodes as one [BetterText]. */
@Composable
internal fun InlineText(content: List<MarkdownInline>, modifier: Modifier = Modifier) {
    val context = LocalMarkdownContext.current
    val style = LocalMarkdownTextStyle.current
    val density = LocalDensity.current
    val contentColor = style.color.takeOrElse { Color.Black }
    val androidContext = LocalContext.current
    val rendered = remember(content, context, style, density, contentColor, androidContext) {
        InlineTextBuilder(context, MarkdownInlineScope(style, density, contentColor, androidContext)).build(content)
    }
    BetterText(
        text = rendered.text,
        modifier = modifier,
        style = style.copy(color = contentColor),
        inlineContent = rendered.inlineContent,
    )
}

private class RenderedInlineText(
    val text: AnnotatedString,
    val inlineContent: Map<String, BetterInline>,
)

private val MATH_SOURCE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)

// Opening quote to closing quote. Single quotes double as apostrophes (don't, it’s), so they don't pair.
private val QUOTE_PAIRS = mapOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')

private class InlineTextBuilder(
    private val context: MarkdownContext,
    private val scope: MarkdownInlineScope,
) {
    private val builder = AnnotatedString.Builder()
    private val inlineContent = mutableMapOf<String, BetterInline>()

    // An open quote: the closing quote it waits for and where it starts in the output.
    // Quotes may span bold text, links and other nodes.
    private var pendingQuoteClose: Char? = null
    private var pendingQuoteStart = 0

    fun build(content: List<MarkdownInline>): RenderedInlineText {
        append(content)
        return RenderedInlineText(builder.toAnnotatedString(), inlineContent)
    }

    private fun append(nodes: List<MarkdownInline>) = nodes.fastForEach { append(it) }

    private fun append(node: MarkdownInline) {
        when (node) {
            is MarkdownInline.Text -> appendText(node.text)
            is MarkdownInline.Styled -> builder.withStyle(node.style) { append(node.children) }
            is MarkdownInline.Code -> appendCode(node.code)
            is MarkdownInline.Link -> appendLink(node)
            is MarkdownInline.Math -> appendMath(node)
            // Images are split out into their own segments; only the alt text is left here.
            is MarkdownInline.Image -> builder.append(node.alt.orEmpty())
        }
    }

    private fun appendText(text: String) {
        val offset = builder.length
        appendDecorated(text)
        val quoteStyle = context.theme.quotedText ?: return
        text.forEachIndexed { index, char ->
            val close = pendingQuoteClose
            if (close == null) {
                QUOTE_PAIRS[char]?.let {
                    pendingQuoteClose = it
                    pendingQuoteStart = offset + index
                }
            } else if (char == close) {
                // Only closed quotes are styled, so a quote that is still streaming in,
                // or a stray one, doesn't color the rest of the paragraph.
                builder.addStyle(quoteStyle, pendingQuoteStart, offset + index + 1)
                pendingQuoteClose = null
            }
        }
    }

    // Decorations have no alt text, so the offsets of the text in the output don't change.
    private fun appendDecorated(text: String) {
        val decorations = context.components.textDecorations?.invoke(scope, text)
        if (decorations.isNullOrEmpty()) {
            builder.append(text)
            return
        }
        var last = 0
        for (decoration in decorations.sortedBy { it.offset }) {
            val at = decoration.offset.coerceIn(last, text.length)
            builder.append(text, last, at)
            appendDecoration(decoration.item)
            last = at
        }
        builder.append(text, last, text.length)
    }

    private fun appendLink(link: MarkdownInline.Link) {
        val inline = context.components.inlineLink?.invoke(scope, link)
        if (inline != null) {
            appendItem(inline, altText = link.children.plainText())
            return
        }
        val annotation = LinkAnnotation.Url(
            url = link.destination,
            styles = TextLinkStyles(style = context.theme.link),
            linkInteractionListener = context.linkListener,
        )
        builder.withLink(annotation) { append(link.children) }
    }

    private fun appendCode(code: String) {
        context.components.inlineCodePrefix?.invoke(scope, code)?.let(::appendDecoration)
        builder.withStyle(context.theme.inlineCode) { append(code) }
    }

    private fun appendMath(math: MarkdownInline.Math) {
        val inline = context.components.inlineMath?.invoke(scope, math.formula)
        if (inline == null) {
            builder.withStyle(MATH_SOURCE_STYLE) { append(math.formula) }
            return
        }
        appendItem(inline, altText = "$${math.formula}$")
    }

    private fun appendDecoration(item: BetterInline) {
        val id = "markdown:${inlineContent.size}"
        inlineContent[id] = item
        builder.appendInline(id, altText = "")
    }

    private fun appendItem(inline: BetterInline, altText: String) {
        val id = "markdown:${inlineContent.size}"
        inlineContent[id] = inline
        builder.appendInline(id, altText.ifEmpty { "�" })
    }
}
