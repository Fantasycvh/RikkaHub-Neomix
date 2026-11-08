package me.rerere.bettertext.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

// Pipeline: Markdown --(JetBrains Markdown)--> HTML --(Jsoup)--> DOM --> MarkdownDocument
//
// The Markdown parser splits embedded HTML into scattered nodes (each inline tag is a separate token, and
// block HTML is cut at blank lines), so rendering straight from the AST can't recover how tags nest. Instead
// we always generate HTML first and hand it to Jsoup, which merges the tags generated from Markdown and the
// tags written by hand in the source into one DOM tree. That leaves a single DOM -> node conversion to maintain.

private val flavour = GFMFlavourDescriptor(useSafeLinks = true, makeHttpsAutoLinks = true)
private val markdownParser = MarkdownParser(flavour)

/**
 * Parses Markdown text into a [MarkdownDocument]. This is pure computation and thread-safe. For long text,
 * call it on a background thread.
 *
 * @param softBreakAsLineBreak whether a single newline inside a paragraph renders as a line break (the usual
 * behavior in chat). When false, it is treated as a space, as in CommonMark.
 * @param streaming whether [content] is still being generated. Unfinished syntax at its end is then hidden
 * or closed, so it doesn't show as raw text first.
 */
fun parseMarkdown(content: String, softBreakAsLineBreak: Boolean = true, streaming: Boolean = false): MarkdownDocument {
    val source = normalizeLineEndings(content)
    var text = source
    if (hasLatexDelimiters(source)) {
        val sourceTree = parseTree(source)
        val pending = if (streaming) unclosedCodeRange(source, sourceTree) else null
        text = normalizeLatexDelimiters(source, withUnclosedCode(codeRanges(sourceTree, 0), pending))
    }
    var tree = parseTree(text)
    if (streaming) {
        val definitions = HashMap<CharSequence, LinkMap.LinkInfo>().also { collectLinkDefinitions(text, tree.children, it) }
        completeStreamingEnd(text, tree, definitions::containsKey).let { (completed, completedTree) ->
            text = completed
            tree = completedTree
        }
    }
    return MarkdownDocument(
        source = content,
        blocks = convertNodes(text, tree, tree.children, LinkMap.buildLinkMap(tree, text), softBreakAsLineBreak),
    )
}

internal fun parseTree(text: String): ASTNode = markdownParser.buildMarkdownTreeFromString(text)

/**
 * Adds the link reference definitions in [nodes], parsed from [text], to [into] by normalized label.
 * Like [LinkMap.buildLinkMap], the first definition of a label wins.
 */
internal fun collectLinkDefinitions(text: String, nodes: List<ASTNode>, into: MutableMap<CharSequence, LinkMap.LinkInfo>) {
    for (node in nodes) {
        if (node.type != MarkdownElementTypes.LINK_DEFINITION) {
            collectLinkDefinitions(text, node.children, into)
            continue
        }
        val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL } ?: continue
        into.getOrPut(LinkMap.normalizeLabel(label.getTextInNode(text))) { LinkMap.LinkInfo.create(node, text) }
    }
}

/**
 * Converts the top-level [nodes] of [tree], parsed from [text], to blocks, resolving reference
 * links with [linkMap]. Converting consecutive groups of nodes separately gives the same blocks as
 * converting them together, as long as no HTML element is open across a group boundary.
 */
internal fun convertNodes(
    text: String,
    tree: ASTNode,
    nodes: List<ASTNode>,
    linkMap: LinkMap,
    softBreakAsLineBreak: Boolean,
): List<MarkdownBlock> {
    val providers = flavour.createHtmlGeneratingProviders(linkMap, null) + mapOf(
        MarkdownTokenTypes.HTML_TAG to InlineHtmlTagProvider,
        GFMElementTypes.INLINE_MATH to MathProvider(inline = true),
        GFMElementTypes.BLOCK_MATH to MathProvider(inline = false),
    )
    val root = if (nodes === tree.children) tree else CompositeASTNode(MarkdownElementTypes.MARKDOWN_FILE, nodes)
    val html = HtmlGenerator(text, root, providers, false).generateHtml(tagRenderer)
    val body = Jsoup.parse(html).body()
    return DomConverter(text, softBreakAsLineBreak).flow(body.childNodes(), markdown = false)
}

// ---- Preprocessing ----

private val LINE_ENDING_REGEX = Regex("""\r\n?""")

// The parser only treats \n as a line ending. A \r left over from CRLF stops fence lines from matching
// (for example, a closing ``` isn't recognized as closing the fence).
internal fun normalizeLineEndings(content: String): String = content.replace(LINE_ENDING_REGEX, "\n")

// Group 1 matches \\ line breaks in formulas so they are skipped. Otherwise the \[ in \\[4pt] would be
// taken as the start of a display formula. Groups 2 and 3 are \( \) and \[ \].
private val LATEX_DELIMITER_REGEX = Regex("""(\\\\)|\\\((.+?)\\\)|\\\[([\s\S]+?)\\]""")
private val LATEX_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

/** Whether [text] may contain `\(` or `\[` delimiters for [normalizeLatexDelimiters] to replace. */
internal fun hasLatexDelimiters(text: String): Boolean = "\\(" in text || "\\[" in text

/**
 * Replaces the `\( \)` and `\[ \]` math delimiters with `$ $` and `$$ $$`, which the parser understands.
 * Delimiters starting in one of the [code] ranges, sorted and found with [codeRanges] in [content]
 * parsed as it is, are left alone.
 */
internal fun normalizeLatexDelimiters(content: String, code: List<IntRange>): String {
    val out = StringBuilder(content.length)
    var copied = 0
    var from = 0
    var nextCode = 0
    while (true) {
        val match = LATEX_DELIMITER_REGEX.find(content, from) ?: break
        val start = match.range.first
        while (nextCode < code.size && code[nextCode].last < start) nextCode++
        if (nextCode < code.size && code[nextCode].first <= start) {
            from = code[nextCode].last + 1
            continue
        }
        from = match.range.last + 1
        if (match.groups[1] != null) continue
        val inline = match.groups[2]?.value?.trim()
        val replacement = when {
            // The parser doesn't allow whitespace just inside the $, but \( x \) with spaces is common,
            // so trim it.
            inline != null -> if (inline.isEmpty()) continue else "$$inline$"
            // Newlines in a display formula break parsing, so join it into one line.
            else -> "$$" + match.groupValues[3].trim().replace(LATEX_LINE_BREAK_REGEX, " ") + "$$"
        }
        out.append(content, copied, start).append(replacement)
        copied = from
    }
    return out.append(content, copied, content.length).toString()
}

private val CODE_TYPES = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN)

/** Ranges of the code blocks and code spans in [node], shifted by [base], in order. */
internal fun codeRanges(node: ASTNode, base: Int): List<IntRange> =
    ArrayList<IntRange>().also { collectCodeRanges(node, base, it) }

private fun collectCodeRanges(node: ASTNode, base: Int, into: MutableList<IntRange>) {
    if (node.type in CODE_TYPES) {
        into.add(base + node.startOffset until base + node.endOffset)
    } else {
        for (child in node.children) collectCodeRanges(child, base, into)
    }
}

// ---- HTML generation ----

// These tags get a marker when generated from Markdown. The converter uses it to tell whether text comes
// from Markdown or from hand-written HTML, which handle whitespace differently.
private const val MARKDOWN_ATTR = "data-md"
private val MARKDOWN_TEXT_TAGS = setOf("p", "li", "th", "td", "h1", "h2", "h3", "h4", "h5", "h6")

// Source information that plain HTML can't hold is carried to the DOM in custom attributes.
private val tagRenderer = HtmlGenerator.DefaultTagRenderer(
    customizer = { node, tagName, attributes ->
        val tag = tagName.toString()
        when {
            tag == "code" && node.type == MarkdownElementTypes.CODE_FENCE -> {
                val closed = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
                attributes + "data-closed=\"$closed\""
            }

            tag == "table" -> attributes + "data-src=\"${node.startOffset},${node.endOffset}\""
            tag in MARKDOWN_TEXT_TAGS -> attributes + MARKDOWN_ATTR
            else -> attributes
        }
    },
    includeSrcPositions = false,
)

private val HTML_TAG_NAME_REGEX = Regex("""^</?([a-zA-Z][a-zA-Z0-9-]*)""")

/**
 * Outputs an inline `<xxx>` as HTML only when it is a known HTML tag, and escapes it as plain text otherwise.
 * Without this, text like `List<String>` or `<answer>` would be swallowed as an unknown tag.
 */
private object InlineHtmlTagProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val tag = node.getTextInNode(text)
        val name = HTML_TAG_NAME_REGEX.find(tag)?.groupValues?.get(1)
        if (name != null && !Tag.isKnownTag(name.lowercase())) {
            visitor.consumeHtml(escapeHtml(tag))
        } else {
            visitor.consumeHtml(tag)
        }
    }
}

/**
 * Replaces the built-in GFM math output. That writes the formula source into the HTML unchanged, so Jsoup
 * takes the `<` in `0<x<1` or `\lambda(x)<k` as the start of a tag and the formula is cut off there. This
 * provider escapes the formula first.
 */
private class MathProvider(private val inline: Boolean) : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        // The first and last children are the $ or $$ delimiters.
        val formula = node.children.subList(1, node.children.size - 1).joinToString("") { it.getTextInNode(text) }
        visitor.consumeTagOpen(node, "span", "class=\"math\"", "inline=\"$inline\"")
        visitor.consumeHtml(escapeHtml(formula.trimSurroundingBackticks()))
        visitor.consumeTagClose("span")
    }

    // Supports the $`x`$ form by removing matching backticks around the formula.
    private fun String.trimSurroundingBackticks(): String {
        var n = 0
        while (length - 2 * n > 2 && this[n] == '`' && this[lastIndex - n] == '`') n++
        return substring(n, length - n).trim()
    }
}

private fun escapeHtml(text: CharSequence): String =
    text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

// ---- DOM -> nodes ----

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "aside", "header", "footer", "main", "nav", "figure", "figcaption",
    "center", "address", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "pre",
    "blockquote", "table", "hr", "details", "summary", "form", "fieldset",
)

// The content of these tags isn't readable text, or can't be drawn here, so the whole subtree is skipped.
private val SKIPPED_TAGS = setOf(
    "script", "style", "head", "title", "meta", "link", "template", "noscript", "svg", "math", "iframe",
    "object", "embed", "canvas", "video", "audio", "input", "select", "textarea", "button",
)

private val CODE_TAGS = setOf("code", "kbd", "samp", "tt")

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKETHROUGH = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)

private val TAG_STYLES = mapOf(
    "b" to BOLD,
    "strong" to BOLD,
    "i" to ITALIC,
    "em" to ITALIC,
    "cite" to ITALIC,
    "var" to ITALIC,
    "dfn" to ITALIC,
    "del" to STRIKETHROUGH,
    "s" to STRIKETHROUGH,
    "strike" to STRIKETHROUGH,
    "u" to UNDERLINE,
    "ins" to UNDERLINE,
    "sub" to SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Subscript),
    "sup" to SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Superscript),
    "small" to SpanStyle(fontSize = 0.85.em),
    "big" to SpanStyle(fontSize = 1.2.em),
    "mark" to SpanStyle(background = Color(0x66FFEB3B)),
)

private val HTML_WHITESPACE_REGEX = Regex("""\s+""")
private val SOFT_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

private class DomConverter(
    private val source: String,
    private val softBreakAsLineBreak: Boolean,
) {
    /**
     * Converts a mixed list of child nodes. Each block element becomes its own block, and each run of
     * inline content between them becomes an implicit paragraph.
     *
     * @param markdown whether bare text in these nodes comes from Markdown (rather than hand-written HTML)
     */
    fun flow(nodes: List<Node>, markdown: Boolean): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val run = mutableListOf<Node>()

        fun flush() {
            if (run.isEmpty()) return
            val content = inlines(run, markdown).trimmed()
            if (content.isNotEmpty()) blocks += MarkdownBlock.Paragraph(content)
            run.clear()
        }

        for (node in nodes) {
            if (node is Element && node.normalName() in BLOCK_TAGS) {
                flush()
                block(node, blocks)
            } else {
                run += node
            }
        }
        flush()
        return blocks
    }

    private fun block(element: Element, out: MutableList<MarkdownBlock>) {
        val markdown = element.hasAttr(MARKDOWN_ATTR)
        when (val tag = element.normalName()) {
            "p" -> {
                val content = inlines(element.childNodes(), markdown).trimmed()
                if (content.isNotEmpty()) out += MarkdownBlock.Paragraph(content, element.blockStyle())
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val content = inlines(element.childNodes(), markdown).trimmed()
                if (content.isNotEmpty()) {
                    out += MarkdownBlock.Heading(tag[1].digitToInt(), content, element.blockStyle())
                }
            }

            "ul", "ol" -> out += list(element, ordered = tag == "ol")
            "pre" -> out += codeBlock(element)
            "table" -> table(element)?.let { out += it }
            "hr" -> out += MarkdownBlock.Divider
            "details" -> out += details(element)

            "blockquote" -> {
                val children = flow(element.childNodes(), markdown = false)
                if (children.isNotEmpty()) out += MarkdownBlock.Quote(children)
            }

            else -> {
                val children = flow(element.childNodes(), markdown)
                val style = element.blockStyle()
                when {
                    children.isEmpty() -> Unit
                    style == null -> out += children
                    else -> out += MarkdownBlock.Container(children, style)
                }
            }
        }
    }

    private fun list(element: Element, ordered: Boolean): MarkdownBlock.ListBlock {
        val items = element.children().filter { it.normalName() == "li" }
        return MarkdownBlock.ListBlock(
            ordered = ordered,
            items = items.map { item ->
                // Checkboxes of nested lists come later, so selectFirst always finds this item's own one.
                val checkbox = if (item.hasClass("task-list-item")) item.selectFirst("input[type=checkbox]") else null
                ListItem(
                    children = flow(item.childNodes(), item.hasAttr(MARKDOWN_ATTR)),
                    checked = checkbox?.hasAttr("checked"),
                )
            },
            start = element.attr("start").toIntOrNull() ?: 1,
            // The content of a loose list (blank lines between items) is wrapped in <p>.
            tight = items.none { item -> item.children().any { it.normalName() == "p" } },
        )
    }

    private fun codeBlock(element: Element): MarkdownBlock.CodeBlock {
        val code = element.selectFirst("code")
        return MarkdownBlock.CodeBlock(
            code = (code ?: element).wholeText().trimEnd('\n'),
            language = code?.classNames()
                ?.firstOrNull { it.startsWith("language-") }
                ?.removePrefix("language-")
                ?.takeIf { it.isNotBlank() },
            closed = code?.attr("data-closed") != "false",
        )
    }

    private fun table(element: Element): MarkdownBlock.Table? {
        // Skip rows of nested tables.
        val rows = element.select("tr").filter { it.closest("table") === element }
        if (rows.isEmpty()) return null

        // Cells inherit the table and row styles. <table align> positions the table itself, so it isn't
        // treated as text alignment.
        val tableStyle = parseBlockStyle(element.attr("style"))
        fun cells(row: Element): List<TableCell> {
            val rowStyle = tableStyle.mergeWith(row.blockStyle())
            return row.children().filter { it.normalName() == "th" || it.normalName() == "td" }.map { cell ->
                TableCell(
                    content = inlines(cell.childNodes(), cell.hasAttr(MARKDOWN_ATTR)).trimmed(),
                    style = rowStyle.mergeWith(cell.blockStyle()),
                )
            }
        }

        val header = cells(rows.first())
        val sourceRange = element.attr("data-src").split(',').mapNotNull { it.toIntOrNull() }
        return MarkdownBlock.Table(
            header = header,
            rows = rows.drop(1).map(::cells),
            alignments = header.map { cell -> cell.style?.textAlign?.takeIf { it != TextAlign.Unspecified } },
            // Hand-written HTML can also have this attribute, so an invalid range is ignored.
            source = sourceRange.takeIf { it.size == 2 && it[0] in 0..it[1] && it[1] <= source.length }
                ?.let { (start, end) -> source.substring(start, end).trim() },
        )
    }

    private fun details(element: Element): MarkdownBlock.Details {
        val summary = element.children().firstOrNull { it.normalName() == "summary" }
        return MarkdownBlock.Details(
            summary = summary?.let { inlines(it.childNodes(), markdown = false).trimmed() }.orEmpty(),
            children = flow(element.childNodes().filter { it !== summary }, markdown = false),
            open = element.hasAttr("open"),
        )
    }

    private fun inlines(nodes: List<Node>, markdown: Boolean): List<MarkdownInline> {
        val out = mutableListOf<MarkdownInline>()
        nodes.forEach { inline(it, markdown, out) }
        return out
    }

    private fun inline(node: Node, markdown: Boolean, out: MutableList<MarkdownInline>) {
        when (node) {
            is TextNode -> out.appendText(normalizeText(node.wholeText, markdown))
            is Element -> inlineElement(node, markdown, out)
        }
    }

    private fun inlineElement(element: Element, markdown: Boolean, out: MutableList<MarkdownInline>) {
        val tag = element.normalName()
        when {
            tag in SKIPPED_TAGS -> Unit
            tag == "br" -> out.appendText("\n")
            tag in CODE_TAGS -> out += MarkdownInline.Code(element.wholeText())

            tag == "img" -> element.attr("src").takeIf { it.isNotBlank() }?.let { src ->
                out += MarkdownInline.Image(
                    url = src,
                    alt = element.attr("alt").takeIf { it.isNotBlank() },
                    title = element.attr("title").takeIf { it.isNotBlank() },
                    width = element.attr("width").toIntOrNull(),
                    height = element.attr("height").toIntOrNull(),
                )
            }

            tag == "span" && element.hasClass("math") -> out += MarkdownInline.Math(
                formula = element.wholeText().trim(),
                display = element.attr("inline") != "true",
            )

            tag == "a" && element.attr("href").isNotBlank() -> {
                val children = inlines(element.childNodes(), markdown)
                if (children.isNotEmpty()) {
                    out += MarkdownInline.Link(
                        destination = element.attr("href"),
                        children = children,
                        title = element.attr("title").takeIf { it.isNotBlank() },
                    )
                }
            }

            else -> {
                // A block element in an inline context (such as a <p> in a table cell) falls back to
                // text separated by newlines.
                val isBlock = tag in BLOCK_TAGS
                if (isBlock && out.isNotEmpty()) out.appendText("\n")
                val children = inlines(element.childNodes(), if (isBlock) element.hasAttr(MARKDOWN_ATTR) else markdown)
                val style = element.inlineStyle()
                when {
                    children.isEmpty() -> Unit
                    style == null -> children.forEach { if (it is MarkdownInline.Text) out.appendText(it.text) else out += it }
                    else -> out += MarkdownInline.Styled(style, children)
                }
            }
        }
    }

    private fun normalizeText(text: String, markdown: Boolean): String = when {
        // Indentation and newlines in hand-written HTML are only formatting. Collapse them to one space, as
        // HTML does.
        !markdown -> text.replace(HTML_WHITESPACE_REGEX, " ")
        '\n' !in text -> text
        else -> text.replace(SOFT_BREAK_REGEX, if (softBreakAsLineBreak) "\n" else " ")
    }
}

private fun Element.blockStyle(): TextStyle? = parseBlockStyle(
    css = attr("style"),
    align = attr("align").ifBlank { if (normalName() == "center") "center" else null },
)

private fun TextStyle?.mergeWith(other: TextStyle?): TextStyle? = when {
    this == null -> other
    other == null -> this
    else -> merge(other)
}

private fun Element.inlineStyle(): SpanStyle? {
    val tag = normalName()
    val tagStyle = when {
        tag == "font" -> parseFontTag(color = attr("color"), size = attr("size"), face = attr("face"))
        // GFM ~~strikethrough~~ generates this class.
        tag == "span" && hasClass("user-del") -> STRIKETHROUGH
        else -> TAG_STYLES[tag]
    }
    val cssStyle = parseSpanStyle(attr("style"))
    return when {
        tagStyle == null -> cssStyle
        cssStyle == null -> tagStyle
        else -> tagStyle.merge(cssStyle)
    }
}

private fun MutableList<MarkdownInline>.appendText(text: String) {
    val last = lastOrNull() as? MarkdownInline.Text
    // Whitespace right after a line break means nothing: a newline after <br> shouldn't add an empty line.
    val appended = if (last != null && last.text.endsWith('\n')) text.trimStart() else text
    if (appended.isEmpty()) return
    if (last != null) {
        this[lastIndex] = MarkdownInline.Text(last.text + appended)
    } else {
        this += MarkdownInline.Text(appended)
    }
}

private fun List<MarkdownInline>.trimmed(): List<MarkdownInline> {
    if (isEmpty()) return this
    val result = toMutableList()
    (result.first() as? MarkdownInline.Text)?.let { first ->
        val text = first.text.trimStart()
        if (text.isEmpty()) result.removeAt(0) else result[0] = MarkdownInline.Text(text)
    }
    (result.lastOrNull() as? MarkdownInline.Text)?.let { last ->
        val text = last.text.trimEnd()
        if (text.isEmpty()) result.removeAt(result.lastIndex) else result[result.lastIndex] = MarkdownInline.Text(text)
    }
    return result
}
