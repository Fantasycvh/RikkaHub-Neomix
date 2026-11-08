package me.rerere.bettertext.markdown

import androidx.compose.runtime.Immutable

/** Half-open UTF-16 offsets into [MarkdownRenderDocument.renderSource], never into a raw message. */
@Immutable
data class MarkdownRenderRange(val start: Int, val end: Int) {
    init { require(start >= 0 && end >= start) }
}

/**
 * A parser-safe run of top-level nodes. HTML containers may produce several [blocks]; splitting
 * them by characters or parsing their source slices independently would lose syntax/link context.
 * Definitions and whitespace may produce no visible blocks but still own a source range.
 *
 * [id] is allocated by one parser session, never derived from content. It survives append-only tail
 * reparsing at the same syntax start, including an open fence growing or a paragraph becoming a
 * heading. Scope it by the message/text-part identity when using it as a UI key.
 */
@Immutable
data class MarkdownRenderUnit(
    val id: Long,
    val sourceRange: MarkdownRenderRange,
    val syntaxRange: MarkdownRenderRange,
    val firstBlockIndex: Int,
    val blocks: List<MarkdownBlock>,
)

/**
 * Display-only projection; [document] preserves the original parser API and complete input source.
 * [renderSource] is after CRLF/math delimiter normalization and optional streaming completion.
 * Its ranges must not be used to edit [MarkdownDocument.source] or the original message.
 *
 * Units partition all of [renderSource], including whitespace/definitions. Their flattened nodes
 * equal [document.blocks]. [generation] changes on non-append edits; [revision] on every new input.
 * [reparsedFrom] is the conservative start of invalidated parser text, not a raw-source offset.
 */
@Immutable
data class MarkdownRenderDocument(
    val document: MarkdownDocument,
    val renderSource: String,
    val units: List<MarkdownRenderUnit>,
    val generation: Long,
    val revision: Long,
    val reparsedFrom: Int,
)
