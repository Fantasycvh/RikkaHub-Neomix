package me.rerere.bettertext.markdown

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.rerere.bettertext.text.BetterInline

/**
 * Visual parameters of [Markdown]. The defaults are neutral; apps map their
 * design system onto them. Unspecified colors derive from the text color.
 */
@Immutable
data class MarkdownTheme(
    /** Text styles of h1 to h6, merged onto the body style; em font sizes are relative to the body. */
    val headings: List<TextStyle> = floatArrayOf(1.5f, 1.375f, 1.25f, 1.125f, 1f, 0.875f).map { scale ->
        TextStyle(fontWeight = FontWeight.Bold, fontSize = scale.em, lineHeight = 1.25.em)
    },
    val link: SpanStyle = SpanStyle(textDecoration = TextDecoration.Underline),
    val inlineCode: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x24808080)),
    /** Space between blocks. */
    val blockSpacing: Dp = 12.dp,
    /** Color of list markers, numbers and task checkboxes; unspecified uses the text color. */
    val markerColor: Color = Color.Unspecified,
    /** Color of the bar beside block quotes; unspecified uses the text color at 30%. */
    val quoteColor: Color = Color.Unspecified,
    /** Color of dividers; unspecified uses the text color at 20%. */
    val dividerColor: Color = Color.Unspecified,
    /** Style of text in quotes ("…", “…”, 「…」, 『…』); null leaves it unstyled. */
    val quotedText: SpanStyle? = null,
)

/** What's available when building custom inline content. */
@Immutable
class MarkdownInlineScope(
    /** The text style of the paragraph. */
    val style: TextStyle,
    val density: Density,
    val contentColor: Color,
    /** The Android context, e.g. to load fonts. */
    val context: Context,
)

/** An item drawn before [offset] of a text, e.g. a swatch before a color code; it copies as nothing. */
class InlineDecoration(val offset: Int, val item: BetterInline)

/**
 * Components registered by the app. The module draws text, lists and quotes
 * itself; everything else is up to the app, with a plain fallback when a slot
 * is null: code, formulas and tables show their source, images their alt
 * text, and details blocks their whole content. The latex module draws
 * formulas, see [inlineMath] and [mathBlock]. Components can match the
 * surrounding text through [LocalMarkdownTextStyle].
 *
 * Keep instances stable (a top-level value or remembered), or every
 * recomposition rebuilds all the text.
 */
@Immutable
class MarkdownComponents(
    val codeBlock: (@Composable (block: MarkdownBlock.CodeBlock) -> Unit)? = null,
    val table: (@Composable (table: MarkdownBlock.Table) -> Unit)? = null,
    /** [link] is set when the image is wrapped in a link. */
    val image: (@Composable (image: MarkdownInline.Image, link: String?) -> Unit)? = null,
    /** Display formulas; null shows their source. */
    val mathBlock: (@Composable (formula: String) -> Unit)? = null,
    /**
     * `<details>` blocks. [summary] and [content] render the parts, so the
     * component only decides how to lay them out and when to show the content.
     */
    val details: (
        @Composable (block: MarkdownBlock.Details, summary: @Composable () -> Unit, content: @Composable () -> Unit) -> Unit
    )? = null,
    /**
     * Inline formulas, as items drawn in the text, such as `LatexInline.cached(formula, context)`
     * from the latex module; null, or returning null, shows their source in monospace.
     */
    val inlineMath: (MarkdownInlineScope.(formula: String) -> BetterInline?)? = null,
    /** Renders some links differently, e.g. as citation tags; null renders a normal link. */
    val inlineLink: (MarkdownInlineScope.(link: MarkdownInline.Link) -> BetterInline?)? = null,
    /**
     * An item drawn before some inline code, e.g. a swatch before a color code. The code stays
     * text with [MarkdownTheme.inlineCode], and the item copies as nothing.
     */
    val inlineCodePrefix: (MarkdownInlineScope.(code: String) -> BetterInline?)? = null,
    /**
     * Decorations in plain text, e.g. a swatch before a color written as `#8B0000` outside code.
     * Called for each run of plain text; offsets are in that run.
     */
    val textDecorations: (MarkdownInlineScope.(text: String) -> List<InlineDecoration>)? = null,
)

object MarkdownDefaults {
    val theme = MarkdownTheme()
    val components = MarkdownComponents()
}
