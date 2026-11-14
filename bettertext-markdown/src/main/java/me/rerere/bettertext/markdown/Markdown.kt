package me.rerere.bettertext.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

/**
 * Renders Markdown: GFM (tables, task lists, strikethrough), LaTeX formulas and embedded HTML.
 *
 * Text is drawn with `BetterText`, so wrap the message in a `BetterSelectionContainer`
 * to make it selectable. Code blocks, formulas, tables, images and details blocks are
 * registered by the app through [components]; visual parameters go in [theme].
 * [style] is the body text style, including its color. [content] may change often while
 * a reply streams in: it is parsed in the background, reusing the unchanged
 * beginning, and blocks that didn't change don't recompose.
 *
 * @param softBreakAsLineBreak render a single newline in a paragraph as a line break.
 * @param streaming true while [content] is still being generated: unfinished syntax at its end,
 * such as `**bold` or `$\frac{`, is closed or hidden instead of showing as raw text first.
 * @param onLinkClick called when a link is clicked; null opens it with the system UriHandler.
 */
@Composable
fun Markdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    theme: MarkdownTheme = MarkdownDefaults.theme,
    components: MarkdownComponents = MarkdownDefaults.components,
    softBreakAsLineBreak: Boolean = true,
    streaming: Boolean = false,
    blockFadeIn: Boolean = false,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    Markdown(
        document = rememberMarkdownDocument(content, softBreakAsLineBreak, streaming),
        modifier = modifier,
        style = style,
        theme = theme,
        components = components,
        blockFadeIn = blockFadeIn,
        onLinkClick = onLinkClick,
    )
}

/** Renders an already parsed [document], e.g. one parsed ahead of time with [parseMarkdown]. */
@Composable
fun Markdown(
    document: MarkdownDocument,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    theme: MarkdownTheme = MarkdownDefaults.theme,
    components: MarkdownComponents = MarkdownDefaults.components,
    blockFadeIn: Boolean = false,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    ProvideMarkdownContext(style, theme, components, onLinkClick) {
        MarkdownBlocks(blocks = document.blocks, modifier = modifier, blockFadeIn = blockFadeIn)
    }
}

/**
 * Renders one already parsed unit, with the same slots, styles, links and heading position as the
 * complete document. The host owns spacing between units. Never parse the unit's source again.
 */
@Composable
fun Markdown(
    unit: MarkdownRenderUnit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    theme: MarkdownTheme = MarkdownDefaults.theme,
    components: MarkdownComponents = MarkdownDefaults.components,
    blockFadeIn: Boolean = false,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    ProvideMarkdownContext(style, theme, components, onLinkClick) {
        MarkdownBlocks(
            blocks = unit.blocks,
            modifier = modifier,
            firstBlockIndex = unit.firstBlockIndex,
            blockFadeIn = blockFadeIn,
        )
    }
}

@Composable
private fun ProvideMarkdownContext(
    style: TextStyle,
    theme: MarkdownTheme,
    components: MarkdownComponents,
    onLinkClick: ((String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val handlesLinks = onLinkClick != null
    val linkListener = remember(handlesLinks) {
        if (handlesLinks) {
            LinkInteractionListener { link ->
                if (link is LinkAnnotation.Url) currentOnLinkClick?.invoke(link.url)
            }
        } else {
            null
        }
    }
    val context = remember(theme, components, linkListener) {
        MarkdownContext(theme, components, linkListener)
    }
    CompositionLocalProvider(LocalMarkdownContext provides context) {
        ProvideMarkdownTextStyle(style) {
            content()
        }
    }
}

/**
 * Parses [content] and follows its changes.
 *
 * The first parse is synchronous so the first frame has content; later ones run
 * in the background with a [MarkdownStreamParser]. When updates come faster than
 * parsing, only the latest content is parsed.
 */
@Composable
fun rememberMarkdownDocument(
    content: String,
    softBreakAsLineBreak: Boolean = true,
    streaming: Boolean = false,
): MarkdownDocument = rememberMarkdownRenderDocument(content, softBreakAsLineBreak, streaming).document

/** Like [rememberMarkdownDocument], exposing the parser's display projection without another parse. */
@Composable
fun rememberMarkdownRenderDocument(
    content: String,
    softBreakAsLineBreak: Boolean = true,
    streaming: Boolean = false,
): MarkdownRenderDocument {
    val parser = remember(softBreakAsLineBreak) { MarkdownStreamParser(softBreakAsLineBreak) }
    val state = remember(parser) { mutableStateOf(parser.parseRenderDocument(content, streaming)) }
    val currentInput by rememberUpdatedState(content to streaming)
    LaunchedEffect(parser) {
        // The parser returns the same document when the input didn't change, so the first,
        // already parsed input costs nothing.
        snapshotFlow { currentInput }
            .conflate()
            .mapNotNull { (content, streaming) -> runCatching { parser.parseRenderDocument(content, streaming) }.getOrNull() }
            .flowOn(Dispatchers.Default)
            .collect { state.value = it }
    }
    return state.value
}
