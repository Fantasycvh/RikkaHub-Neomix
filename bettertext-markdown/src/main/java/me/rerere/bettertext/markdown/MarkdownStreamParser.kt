package me.rerere.bettertext.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.LinkMap

/**
 * Parses Markdown that is streamed in, reusing work from the previous call.
 *
 * The document is split into chunks of top-level blocks. When the new content
 * shares a prefix with the previous one, as it does while a reply streams in,
 * chunks inside that prefix are kept and only the rest is parsed again. The
 * last two kept chunks are parsed again too, since text appended after a block
 * can change how it's read, e.g. a list that gains another item.
 *
 * Reference definitions can affect earlier chunks. Their appearance in the tail, or an edit to a
 * document containing definitions, conservatively invalidates the complete document.
 *
 * Not thread-safe: call [parse] from one thread at a time.
 */
class MarkdownStreamParser(private val softBreakAsLineBreak: Boolean = true) {
    /** A run of top-level blocks covering `[start, end)` of the normalized text, with the link definitions in it. */
    private class Chunk(
        val start: Int,
        val end: Int,
        val blocks: List<MarkdownBlock>,
        val definitions: Map<CharSequence, LinkMap.LinkInfo>,
    )

    /** A run of top-level blocks covering `[0, end)` of the source before delimiter conversion, with its code. */
    private class CodeChunk(val end: Int, val code: List<IntRange>)

    /** The text the chunks were parsed from, with unfinished syntax completed when streaming. */
    private var text = ""
    private var chunks: List<Chunk> = emptyList()
    private var lastInput = ""
    private var lastStreaming = false
    private var document: MarkdownDocument? = null
    private var source = ""
    private var codeChunks: List<CodeChunk> = emptyList()
    private var projection: MarkdownRenderDocument? = null
    private var nextUnitId = 0L

    /** Parses once, exposing parser-safe ranges and identities alongside the original document. */
    fun parseRenderDocument(content: String, streaming: Boolean = false): MarkdownRenderDocument {
        parse(content, streaming)
        return checkNotNull(projection)
    }

    /** Parses [content]; [streaming] is as in [parseMarkdown]. */
    fun parse(content: String, streaming: Boolean = false): MarkdownDocument {
        val appendOnly = document?.source?.let(content::startsWith) ?: true
        val lines = normalizeLineEndings(content)
        val normalized = if (hasLatexDelimiters(lines)) normalizeLatexDelimiters(lines, sourceCodeRanges(lines, streaming)) else lines
        document?.let {
            if (normalized == lastInput && streaming == lastStreaming) {
                if (it.source == content) return it
                return MarkdownDocument(content, it.blocks).also { doc ->
                    document = doc
                    project(doc, appendOnly, reparsedFrom = 0)
                }
            }
        }
        val common = commonPrefixLength(text, normalized)
        var kept = if (!appendOnly && chunks.any { it.definitions.isNotEmpty() }) emptyList()
            else chunks.takeWhile { it.end <= common }.dropLast(REPARSED_CHUNKS)
        var tailStart = kept.lastOrNull()?.end ?: 0
        var tail = normalized.substring(tailStart)

        var tree = parseTree(tail)
        if (kept.isNotEmpty()) {
            val tailDefinitions = HashMap<CharSequence, LinkMap.LinkInfo>()
            collectLinkDefinitions(tail, tree.children, tailDefinitions)
            if (tailDefinitions.isNotEmpty()) {
                kept = emptyList()
                tailStart = 0
                tail = normalized
                tree = parseTree(tail)
            }
        }
        // Unfinished syntax can only be in the last block, which is always in the tail.
        if (streaming) {
            val defined = HashSet<CharSequence>()
            for (chunk in kept) defined += chunk.definitions.keys
            defined += HashMap<CharSequence, LinkMap.LinkInfo>().also { collectLinkDefinitions(tail, tree.children, it) }.keys
            completeStreamingEnd(tail, tree, defined::contains).let { (completed, completedTree) ->
                tail = completed
                tree = completedTree
            }
        }
        val groups = group(tree, tail)
        val groupDefinitions = groups.map { nodes ->
            HashMap<CharSequence, LinkMap.LinkInfo>().also { collectLinkDefinitions(tail, nodes, it) }
        }
        // Links in the tail can refer to definitions in kept chunks too.
        val definitions = HashMap<CharSequence, LinkMap.LinkInfo>()
        for (map in kept.map { it.definitions } + groupDefinitions) {
            for ((label, info) in map) definitions.getOrPut(label) { info }
        }
        val linkMap = LinkMap(definitions)
        val parsed = groups.mapIndexed { i, nodes ->
            Chunk(
                start = tailStart + nodes.first().startOffset,
                end = tailStart + nodes.last().endOffset,
                blocks = convertNodes(tail, tree, nodes, linkMap, softBreakAsLineBreak),
                definitions = groupDefinitions[i].ifEmpty { emptyMap() },
            )
        }
        text = normalized.substring(0, tailStart) + tail
        chunks = kept + parsed
        lastInput = normalized
        lastStreaming = streaming
        return MarkdownDocument(source = content, blocks = chunks.flatMap { it.blocks }).also {
            document = it
            project(it, appendOnly, tailStart)
        }
    }

    private fun project(document: MarkdownDocument, appendOnly: Boolean, reparsedFrom: Int) {
        val previous = projection
        val oldUnits = if (appendOnly) previous?.units.orEmpty() else emptyList()
        var oldIndex = 0
        fun previousAt(start: Int): MarkdownRenderUnit? {
            while (oldIndex < oldUnits.size && oldUnits[oldIndex].syntaxRange.start < start) oldIndex++
            return oldUnits.getOrNull(oldIndex)?.takeIf { it.syntaxRange.start == start }
        }
        var blockIndex = 0
        val units = chunks.mapIndexed { index, chunk ->
            val old = previousAt(chunk.start)
            val start = if (index == 0) 0 else chunk.start
            val end = chunks.getOrNull(index + 1)?.start ?: text.length
            // Stable prefixes reuse their descriptors without allocating ranges/candidate units or
            // a document-sized lookup map on every streaming update.
            val unit = if (old != null && old.sourceRange.start == start && old.sourceRange.end == end &&
                old.syntaxRange.end == chunk.end && old.firstBlockIndex == blockIndex && old.blocks === chunk.blocks
            ) old else MarkdownRenderUnit(
                id = old?.id ?: nextUnitId++, sourceRange = MarkdownRenderRange(start, end),
                syntaxRange = MarkdownRenderRange(chunk.start, chunk.end), firstBlockIndex = blockIndex,
                blocks = chunk.blocks,
            )
            blockIndex += chunk.blocks.size
            unit
        }.ifEmpty {
            if (text.isEmpty()) emptyList() else listOf(MarkdownRenderUnit(
                id = previousAt(0)?.id ?: nextUnitId++,
                sourceRange = MarkdownRenderRange(0, text.length),
                syntaxRange = MarkdownRenderRange(0, text.length),
                firstBlockIndex = 0,
                blocks = emptyList(),
            ))
        }
        projection = MarkdownRenderDocument(
            document = document, renderSource = text, units = units,
            generation = (previous?.generation ?: 0) + if (appendOnly) 0 else 1,
            revision = (previous?.revision ?: -1) + 1,
            reparsedFrom = if (appendOnly) reparsedFrom else 0,
        )
    }

    /**
     * Code ranges in [newSource] parsed as it is, for [normalizeLatexDelimiters], including an unclosed
     * code span at the end when [streaming]. Parsed incrementally like the blocks in [parse].
     */
    private fun sourceCodeRanges(newSource: String, streaming: Boolean): List<IntRange> {
        val common = commonPrefixLength(source, newSource)
        val kept = codeChunks.takeWhile { it.end <= common }.dropLast(REPARSED_CHUNKS)
        val tailStart = kept.lastOrNull()?.end ?: 0
        val tail = newSource.substring(tailStart)
        val tree = parseTree(tail)
        val parsed = group(tree, tail).map { nodes ->
            CodeChunk(
                end = tailStart + nodes.last().endOffset,
                code = nodes.flatMap { codeRanges(it, tailStart) },
            )
        }
        source = newSource
        codeChunks = kept + parsed
        val pending = if (streaming) unclosedCodeRange(tail, tree)?.let { it.first + tailStart until it.last + 1 + tailStart } else null
        return withUnclosedCode(codeChunks.flatMap { it.code }, pending)
    }

    private companion object {
        const val REPARSED_CHUNKS = 2

        // Elements that never have a closing tag.
        val VOID_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
        )
        val TAG_REGEX = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9-]*)[^>]*?(/?)>""")

        fun commonPrefixLength(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        /**
         * Splits the top-level blocks of [tree] into groups that convert to the same
         * blocks on their own. A group doesn't end while an HTML element is open, and
         * HTML blocks stay with their neighbors: loose inline HTML on either side of a
         * boundary would otherwise not be merged into one paragraph.
         */
        fun group(tree: ASTNode, text: String): List<List<ASTNode>> {
            val groups = ArrayList<List<ASTNode>>()
            var current = ArrayList<ASTNode>()
            var depth = 0
            for (node in tree.children) {
                if (node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE) continue
                val html = node.type == MarkdownElementTypes.HTML_BLOCK
                val previousHtml = current.lastOrNull()?.type == MarkdownElementTypes.HTML_BLOCK
                if (current.isNotEmpty() && depth == 0 && !html && !previousHtml) {
                    groups.add(current)
                    current = ArrayList()
                }
                current.add(node)
                depth = maxOf(0, depth + tagBalance(node, text))
            }
            if (current.isNotEmpty()) groups.add(current)
            return groups
        }

        /** Opened minus closed HTML elements in [node], counting raw HTML only, not code. */
        fun tagBalance(node: ASTNode, text: String): Int = when (node.type) {
            MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG -> TAG_REGEX.findAll(node.getTextInNode(text)).sumOf {
                val name = it.groupValues[2].lowercase()
                when {
                    name in VOID_TAGS || it.groupValues[3] == "/" -> 0
                    it.groupValues[1] == "/" -> -1
                    else -> 1
                }.toInt()
            }

            MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> 0
            else -> node.children.sumOf { tagBalance(it, text) }
        }
    }
}
