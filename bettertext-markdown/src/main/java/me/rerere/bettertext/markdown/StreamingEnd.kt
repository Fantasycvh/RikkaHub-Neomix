package me.rerere.bettertext.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

// While a reply streams in, its last block is often cut off inside some syntax: `**bold`, `$\frac{`,
// `[text](https://exa`. Rendered as it is, that shows as raw text which changes once the syntax closes.
// These functions hide unfinished syntax at the end of the text, or close it when the content can already
// be shown styled. They work from the parse tree, so code and text that only looks like syntax stay as
// they are. Unclosed code fences need nothing: they already render as code.

/** Nodes whose last child holds the end of the text. */
private val CONTAINERS = setOf(
    MarkdownElementTypes.MARKDOWN_FILE, MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.LIST_ITEM, MarkdownElementTypes.BLOCK_QUOTE, GFMElementTypes.TABLE, GFMElementTypes.HEADER,
    GFMElementTypes.ROW, MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
)

/** Nodes holding inline content. */
private val INLINE_HOLDERS = setOf(MarkdownElementTypes.PARAGRAPH, MarkdownTokenTypes.ATX_CONTENT, GFMTokenTypes.CELL)

/** A tag cut off before its `>`, possibly before its name. */
private val INCOMPLETE_TAG_REGEX = Regex("""</?(?:[a-zA-Z][^<>]*)?""")
private const val MATH_CHARS = "\\^_{}="

/**
 * Hides or closes unfinished syntax at the end of [text], which was parsed as [tree]. [isDefined] tells
 * whether a link reference label, normalized with [LinkMap.normalizeLabel], has a definition.
 * Returns the new text and its tree, or the arguments when nothing is unfinished.
 */
internal fun completeStreamingEnd(text: String, tree: ASTNode, isDefined: (CharSequence) -> Boolean): Pair<String, ASTNode> {
    var current = text
    var currentTree = tree
    // Hiding a block can expose another to fix, e.g. a setext underline left by a hidden list item.
    repeat(3) {
        val next = hideUnfinishedBlock(current, currentTree)
            ?: closeUnfinishedInline(current, currentTree, isDefined)
            ?: return current to currentTree
        current = next.first
        currentTree = next.second
    }
    return current to currentTree
}

/**
 * The range from an unclosed backtick in the last block to the block's end, in [text] parsed as [tree].
 * [completeStreamingEnd] closes it as a code span, so it must be treated as code before that, e.g. by
 * [normalizeLatexDelimiters].
 */
internal fun unclosedCodeRange(text: String, tree: ASTNode): IntRange? {
    val block = lastBlock(tree)
    if (block.type !in INLINE_HOLDERS || !isOpen(text, block.endOffset)) return null
    val backtick = block.children.firstOrNull { it.type == MarkdownTokenTypes.BACKTICK } ?: return null
    if (text.substring(backtick.endOffset, block.endOffset).isBlank()) return null
    return backtick.startOffset until block.endOffset
}

/** [ranges], sorted, with [pending] from [unclosedCodeRange] added. */
internal fun withUnclosedCode(ranges: List<IntRange>, pending: IntRange?): List<IntRange> =
    if (pending == null) ranges else ranges.filter { it.first < pending.first } + listOf(pending)

/** The innermost node that ends the text: the last leaf block, or the last cell of a table. */
private fun lastBlock(tree: ASTNode): ASTNode {
    var node = tree
    while (node.type in CONTAINERS) {
        node = node.children.lastOrNull { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
            ?: return node
    }
    return node
}

/** Whether a block ending at [end] may still continue: only whitespace follows, without a blank line. */
private fun isOpen(text: String, end: Int): Boolean {
    var newlines = 0
    for (i in end until text.length) {
        if (!text[i].isWhitespace()) return false
        if (text[i] == '\n') newlines++
    }
    return newlines <= 1
}

/** Hides a last block that would render differently once complete. */
private fun hideUnfinishedBlock(text: String, tree: ASTNode): Pair<String, ASTNode>? {
    val block = lastBlock(tree)
    if (!isOpen(text, block.endOffset)) return null
    val cut = when (block.type) {
        // `Text` followed by a line that's only `-` or `=` is a setext heading, but while the line is
        // the last one it's usually the start of a list item or a divider.
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 ->
            if ('\n' in text.substring(block.endOffset)) null else text.lastIndexOf('\n', block.endOffset - 1)

        // A table header shows as a paragraph until its delimiter row arrives.
        MarkdownElementTypes.PARAGRAPH -> block.startOffset.takeIf {
            text.substring(block.startOffset, block.endOffset).lines().all { it.trimStart(' ', '\t', '>').startsWith('|') }
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val start = text.lastIndexOf('<', block.endOffset - 1)
            start.takeIf { it >= block.startOffset && INCOMPLETE_TAG_REGEX.matches(text.substring(it, block.endOffset)) }
        }

        else -> null
    }
    if (cut == null || cut < 0) return null
    val next = text.substring(0, cut)
    return next to parseTree(next)
}

/**
 * Closes emphasis, strikethrough and code spans left open in the last block, and hides math, links,
 * images and HTML tags that aren't complete yet.
 */
private fun closeUnfinishedInline(text: String, tree: ASTNode, isDefined: (CharSequence) -> Boolean): Pair<String, ASTNode>? {
    val block = lastBlock(tree)
    if (block.type !in INLINE_HOLDERS || !isOpen(text, block.endOffset)) return null
    val end = block.endOffset
    // Delimiters left in the block's top level are unmatched; matched ones are inside composite nodes.
    val tokens = block.children
    val openers = ArrayList<String>()
    var cut = end
    var insert = ""
    fun restIsBlank(from: Int) = text.substring(from, end).isBlank()
    var i = 0
    scan@ while (i < tokens.size) {
        val token = tokens[i]
        val previous = tokens.getOrNull(i - 1)
        val afterImageMark = previous?.type == MarkdownTokenTypes.EXCLAMATION_MARK && previous.endOffset == token.startOffset
        when (token.type) {
            GFMTokenTypes.DOLLAR -> {
                // A single $ followed by prose is more likely a price than a formula.
                val content = text.substring(token.endOffset, end).trim()
                val math = token.endOffset - token.startOffset > 1 ||
                    content.none { it.isWhitespace() } || content.any { it in MATH_CHARS }
                if (math) {
                    cut = token.startOffset
                    break@scan
                }
            }

            MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE -> {
                val last = runEnd(tokens, i, text)
                val runEnd = tokens[last].endOffset
                if (restIsBlank(runEnd)) {
                    cut = token.startOffset
                    break@scan
                }
                val run = text.substring(token.startOffset, runEnd)
                // Only a run followed by text can open, and a single ~ is usually "about".
                if (!text[runEnd].isWhitespace() && !(token.type == GFMTokenTypes.TILDE && run.length < 2)) openers.add(run)
                i = last
            }

            MarkdownTokenTypes.BACKTICK -> {
                if (restIsBlank(token.endOffset)) {
                    cut = token.startOffset
                } else {
                    // Everything after an unmatched backtick is code, so nothing after it needs closing.
                    insert = text.substring(token.startOffset, token.endOffset)
                }
                break@scan
            }

            // `[text](partial` shows its text until the link is complete.
            MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK -> if (opensParenthesis(tokens, i)) {
                val label = token.children.first { it.type == MarkdownElementTypes.LINK_LABEL }
                cut = token.startOffset
                insert = text.substring(label.startOffset + 1, label.endOffset - 1)
                break@scan
            }

            // An image shows nothing until it's complete. `![alt]` is read as a reference while `(url)` or
            // `[ref]` is still to come, so it's only complete when it resolves.
            MarkdownElementTypes.IMAGE -> {
                val reference = token.children.firstOrNull {
                    it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK || it.type == MarkdownElementTypes.FULL_REFERENCE_LINK
                }
                val next = tokens.getOrNull(i + 1)
                val unfinished = reference != null && (
                    opensParenthesis(tokens, i) ||
                        (next?.type == MarkdownTokenTypes.LBRACKET && next.startOffset == token.endOffset) ||
                        (reference.type == MarkdownElementTypes.SHORT_REFERENCE_LINK && restIsBlank(token.endOffset) && !isDefined(labelOf(reference, text)))
                    )
                if (unfinished) {
                    cut = token.startOffset
                    break@scan
                }
            }

            MarkdownTokenTypes.LBRACKET -> if (afterImageMark || restIsBlank(token.endOffset)) {
                cut = if (afterImageMark) previous!!.startOffset else token.startOffset
                break@scan
            }

            MarkdownTokenTypes.LT -> if (INCOMPLETE_TAG_REGEX.matches(text.substring(token.startOffset, end))) {
                cut = token.startOffset
                break@scan
            }

            else -> if (token.children.isEmpty()) {
                val delimiter = latexOpener(text, token.startOffset, token.endOffset)
                if (delimiter >= 0) {
                    cut = delimiter
                    break@scan
                }
            }
        }
        i++
    }
    if (cut == end && insert.isEmpty() && openers.isEmpty()) return null

    val head = text.substring(0, cut) + insert
    if (openers.isNotEmpty()) {
        // Closers only count when no whitespace precedes them.
        val body = head.trimEnd()
        val closed = body + openers.asReversed().joinToString("")
        val closedTree = parseTree(closed)
        // A closer the parser doesn't take as one would show as stray characters.
        val unmatched = lastBlock(closedTree).children.any { it.startOffset >= body.length && it.type in CLOSER_TYPES }
        if (!unmatched) return closed to closedTree
        if (cut == end && insert.isEmpty()) return null
    }
    return head to parseTree(head)
}

private val CLOSER_TYPES = setOf<IElementType>(MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE)

/** The normalized label of a reference link, for looking up its definition. */
private fun labelOf(reference: ASTNode, text: String): CharSequence {
    val label = reference.children.first { it.type == MarkdownElementTypes.LINK_LABEL }
    return LinkMap.normalizeLabel(text.substring(label.startOffset, label.endOffset))
}

/**
 * The first `\(` or `\[` in `[start, end)` of [text], or -1. They're converted only once closed, so any
 * left over are unfinished. A backslash escaped by another one doesn't start a delimiter.
 */
private fun latexOpener(text: String, start: Int, end: Int): Int {
    for (i in start until end) {
        if (text[i] != '\\' || i + 1 >= text.length || (text[i + 1] != '(' && text[i + 1] != '[')) continue
        var backslashes = 0
        while (i - backslashes - 1 >= 0 && text[i - backslashes - 1] == '\\') backslashes++
        if (backslashes % 2 == 0) return i
    }
    return -1
}

/** Whether token [index] is directly followed by `(`, as a link that's still being written. */
private fun opensParenthesis(tokens: List<ASTNode>, index: Int): Boolean {
    val next = tokens.getOrNull(index + 1)
    return next?.type == MarkdownTokenTypes.LPAREN && next.startOffset == tokens[index].endOffset
}

/** Index of the last token in the run of the same delimiter character starting at [start]. */
private fun runEnd(tokens: List<ASTNode>, start: Int, text: String): Int {
    val char = text[tokens[start].startOffset]
    var last = start
    while (last + 1 < tokens.size) {
        val next = tokens[last + 1]
        if (next.type != tokens[start].type || next.startOffset != tokens[last].endOffset || text[next.startOffset] != char) break
        last++
    }
    return last
}
