package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isContentFilterTruncation
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.ui.components.message.ChatMessageSlice
import app.androidrik.rikkahub.ui.components.message.MessageDisplayDocument
import app.androidrik.rikkahub.ui.components.message.MessagePartBlock
import app.androidrik.rikkahub.ui.components.message.messageDisplayDocument
import app.androidrik.rikkahub.ui.components.message.messageMarkdownText
import app.androidrik.rikkahub.ui.components.richtext.normalizeMarkdownForDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.bettertext.markdown.MarkdownRenderDocument
import me.rerere.bettertext.markdown.MarkdownRenderRange
import me.rerere.bettertext.markdown.MarkdownRenderUnit
import me.rerere.bettertext.markdown.MarkdownStreamParser
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

internal const val LONG_MESSAGE_MIN_CHARS = 8_000
private const val MIN_LAZY_UNITS = 4

internal fun canPrepareStaticMessage(message: UIMessage, appearance: ChatAppearance, enabled: Boolean, loading: Boolean): Boolean {
    if (!enabled || (loading && message.role == MessageRole.ASSISTANT) || appearance.layout.rendererId != "builtin.standard" || message.isContextCheckpoint) return false
    if (message.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT)) return false
    if (message.annotations.any { it is UIMessageAnnotation.SubagentNotice || it is UIMessageAnnotation.ExternalNotice }) return false
    val bubble = if (message.role == MessageRole.USER) appearance.userBubble else appearance.assistantBubble
    if (bubble.visible && bubble.opacity > 0f && bubble.material != BubbleMaterial.SOLID) return false
    return message.parts.any { it is UIMessagePart.Text && it.text.length >= LONG_MESSAGE_MIN_CHARS }
}

internal data class StaticMessageInput(val nodeId: Uuid, val message: UIMessage, val assistant: Assistant?, val folding: Boolean, val notice: String)
internal data class StaticTextGroup(val messageId: Uuid, val partIndex: Int, val epoch: Long)
internal data class StaticMessageDocument(
    val input: StaticMessageInput,
    val epoch: Long,
    val display: MessageDisplayDocument,
    val markdown: Map<Int, MarkdownRenderDocument>,
) {
    // Amortize a lazy host over nearby small syntax units. Whole lists/fences/HTML groups remain
    // atomic; a large unit stands alone. Prepared off-thread along with the document, never per frame.
    private val lazyRows = markdown.mapValues { (_, document) ->
        document.takeIf { it.renderSource.length >= LONG_MESSAGE_MIN_CHARS }?.units
            ?.filter { it.blocks.isNotEmpty() }?.takeIf { it.size >= MIN_LAZY_UNITS }?.let(::packStaticUnits)
    }
    fun lazyUnits(part: Int) = lazyRows[part]
}

internal fun packStaticUnits(units: List<MarkdownRenderUnit>): List<MarkdownRenderUnit> = buildList {
    val batch = mutableListOf<MarkdownRenderUnit>()
    fun flush() {
        if (batch.isEmpty()) return
        val first = batch.first()
        add(if (batch.size == 1) first else first.copy(
            sourceRange = MarkdownRenderRange(first.sourceRange.start, batch.last().sourceRange.end),
            syntaxRange = MarkdownRenderRange(first.syntaxRange.start, batch.last().syntaxRange.end),
            blocks = batch.flatMap { it.blocks },
        ))
        batch.clear()
    }
    for (unit in units) {
        if (batch.isNotEmpty() && (batch.size >= 8 || unit.sourceRange.end - batch.first().sourceRange.start > 1024)) flush()
        batch += unit
    }
    flush()
}

/** Called off the UI thread. No message copies, persistence changes or per-slice reparsing. */
internal suspend fun prepareStaticMessage(input: StaticMessageInput, epoch: Long): StaticMessageDocument {
    val markdown = linkedMapOf<Int, MarkdownRenderDocument>()
    input.message.parts.forEachIndexed { index, part ->
        coroutineContext.ensureActive()
        if (part is UIMessagePart.Text && part.text.length >= LONG_MESSAGE_MIN_CHARS) {
            val source = messageMarkdownText(part.text, input.notice, input.assistant, input.message.role)
            markdown[index] = MarkdownStreamParser().parseRenderDocument(normalizeMarkdownForDisplay(source))
        }
    }
    return StaticMessageDocument(input, epoch,
        messageDisplayDocument(input.message.id, input.message.parts, input.message.role, input.folding), markdown)
}

internal data class StaticDocuments(val inputs: List<StaticMessageInput>, val ready: Map<Uuid, StaticMessageDocument>, val failed: Set<Uuid>) {
    val pending get() = inputs.map { it.nodeId }.toSet() - ready.keys - failed
}

@Composable
internal fun rememberStaticDocuments(
    conversationId: Uuid,
    nodes: List<MessageNode>,
    assistant: Assistant?,
    appearance: ChatAppearance,
    enabled: Boolean,
    loading: Boolean,
    folding: Boolean,
    notice: String,
): StaticDocuments {
    // P3 is static-only: don't replace a reply the user has been reading while it streamed with a
    // short preparation placeholder when generation stops. P4 owns that in-session transition.
    val streamed = remember(conversationId) { mutableSetOf<Uuid>() }
    if (loading) nodes.lastOrNull()?.currentMessage?.takeIf { it.role == MessageRole.ASSISTANT }?.let { streamed += it.id }
    val inputs = remember(nodes, assistant, appearance, enabled, loading, folding, notice) {
        nodes.mapIndexedNotNull { index, node ->
            node.currentMessage.takeIf { it.id !in streamed && canPrepareStaticMessage(it, appearance, enabled, loading && index == nodes.lastIndex) }
                ?.let { StaticMessageInput(node.id, it, assistant, folding, notice) }
        }
    }
    val sequence = remember(conversationId) { AtomicLong() }
    val result by produceState<Pair<Map<Uuid, StaticMessageDocument>, Set<Uuid>>>(emptyMap<Uuid, StaticMessageDocument>() to emptySet(),
        conversationId, inputs) {
        val previous = value.first
        value = withContext(Dispatchers.Default) {
            val prepared = linkedMapOf<Uuid, StaticMessageDocument>()
            val failed = mutableSetOf<Uuid>()
            for (input in inputs) {
                ensureActive()
                val old = previous[input.nodeId]?.takeIf { it.input == input }
                if (old != null) prepared[input.nodeId] = old
                else try { prepared[input.nodeId] = prepareStaticMessage(input, sequence.incrementAndGet()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed += input.nodeId } // Preserve the complete legacy path on failure.
            }
            prepared to failed
        }
    }
    val valid = remember(inputs, result) {
        inputs.mapNotNull { input -> result.first[input.nodeId]?.takeIf { it.input == input }?.let { input.nodeId to it } }.toMap()
    }
    return StaticDocuments(inputs, valid, result.second.intersect(inputs.map { it.nodeId }.toSet()))
}

internal data class ChatRenderRow(
    val key: String,
    val nodeIndex: Int,
    val nodeId: Uuid,
    val messageId: Uuid,
    val slice: ChatMessageSlice?,
    val topGapDp: Float,
    val firstInMessage: Boolean,
)

internal data class ChatReadAnchor(val key: String, val nodeId: Uuid?, val messageId: Uuid?, val partIndex: Int?, val sourceOffset: Int?, val pixelOffset: Int)

/** The only mapping from logical messages to actual list indices (including checkpoint/tail rows). */
internal class ChatRenderIndex(
    val nodes: List<MessageNode>,
    val rows: List<ChatRenderRow>,
    val hasCheckpoint: Boolean,
    val tailKeys: List<String>,
) {
    val leadingCount = if (hasCheckpoint) 1 else 0
    val totalCount = leadingCount + rows.size + tailKeys.size
    val lastIndex = (totalCount - 1).coerceAtLeast(0)
    val keyToIndex = buildMap {
        if (hasCheckpoint) put("CompactionCheckpoint", 0)
        rows.forEachIndexed { index, row -> put(row.key, leadingCount + index) }
        tailKeys.forEachIndexed { index, key -> put(key, leadingCount + rows.size + index) }
    }
    val nodeRanges: Map<Uuid, IntRange> = rows.withIndex().groupBy { it.value.nodeId }.mapValues { (_, entries) ->
        leadingCount + entries.first().index..leadingCount + entries.last().index
    }
    fun target(id: Uuid): Int = nodes.firstOrNull { it.id == id || it.messages.any { message -> message.id == id } }
        ?.let { nodeRanges[it.id]?.first } ?: -1
    fun previousMessage(index: Int): Int {
        val current = nodes.indexOfLast { (nodeRanges[it.id]?.first ?: Int.MAX_VALUE) <= index }
        if (index >= leadingCount + rows.size) return nodes.lastOrNull()?.let { nodeRanges[it.id]?.first } ?: 0
        return if (current <= 0) 0 else nodeRanges.getValue(nodes[current - 1].id).first
    }
    fun nextMessage(index: Int): Int {
        val current = nodes.indexOfLast { (nodeRanges[it.id]?.first ?: Int.MAX_VALUE) <= index }
        return nodes.getOrNull(current + 1)?.let { nodeRanges[it.id]?.first } ?: lastIndex
    }
    fun anchor(key: Any?, pixelOffset: Int): ChatReadAnchor? {
        val stringKey = key?.toString() ?: return null
        val row = keyToIndex[stringKey]?.let { rows.getOrNull(it - leadingCount) }
        val unit = row?.slice as? ChatMessageSlice.Markdown
        return ChatReadAnchor(stringKey, row?.nodeId, row?.messageId, unit?.partIndex, unit?.unit?.sourceRange?.start, pixelOffset)
    }
    fun restore(anchor: ChatReadAnchor): Pair<Int, Int>? {
        keyToIndex[anchor.key]?.let { return it to anchor.pixelOffset }
        val matching = rows.withIndex().filter { it.value.nodeId == anchor.nodeId && it.value.messageId == anchor.messageId }
        if (anchor.partIndex != null && anchor.sourceOffset != null) matching.firstOrNull { (_, row) ->
            val unit = row.slice as? ChatMessageSlice.Markdown
            unit?.partIndex == anchor.partIndex && anchor.sourceOffset in unit.unit.sourceRange.start until unit.unit.sourceRange.end
        }?.let { return leadingCount + it.index to anchor.pixelOffset }
        return anchor.nodeId?.let { nodeRanges[it]?.first }?.let { it to 0 }
    }
}

internal fun buildChatRenderIndex(
    nodes: List<MessageNode>,
    documents: StaticDocuments,
    appearance: ChatAppearance,
    hasCheckpoint: Boolean,
    tailKeys: List<String>,
): ChatRenderIndex {
    val rows = mutableListOf<ChatRenderRow>()
    val pending = documents.pending
    nodes.forEachIndexed { nodeIndex, node ->
        val message = node.currentMessage
        val document = documents.ready[node.id]
        val lazy = document?.display?.body?.any { it.textDocumentId?.partIndex?.let(document::lazyUnits) != null } == true
        val boundaryGap = if (nodeIndex > 0 || hasCheckpoint) appearance.layout.listSpacingDp else 0f
        fun add(suffix: String, slice: ChatMessageSlice?, gap: Float, first: Boolean = false) {
            rows += ChatRenderRow(if (first) node.id.toString() else "${node.id}:${message.id}:$suffix", nodeIndex, node.id, message.id, slice, gap, first)
        }
        if (!lazy && node.id !in pending) {
            add("full", document?.let { ChatMessageSlice.Prepared(it.markdown) }, boundaryGap, true)
            return@forEachIndexed
        }
        add("header", ChatMessageSlice.Header, boundaryGap, true)
        val leading = message.annotations.any { it is UIMessageAnnotation.QuotedMessage || it is UIMessageAnnotation.PartialFailure } ||
            (nodeIndex == nodes.lastIndex && message.role == MessageRole.ASSISTANT && (message.finishIncomplete ||
                isOutputLimitTruncation(message.finishReason) || isContentFilterTruncation(message.finishReason)))
        val firstBodyGap = if (appearance.layout.showHeader || leading) appearance.layout.messageGapDp else 0f
        if (document == null) add("preparing", ChatMessageSlice.Preparing, firstBodyGap)
        else document.display.body.forEachIndexed { bodyIndex, section ->
            val partIndex = section.textDocumentId?.partIndex
            val units = partIndex?.let(document::lazyUnits)
            val gap = if (bodyIndex == 0) firstBodyGap else appearance.layout.messageGapDp
            if (partIndex != null && units != null) units.forEachIndexed { index, unit ->
                val group = StaticTextGroup(message.id, partIndex, document.epoch)
                add("text:$partIndex:${document.epoch}:${unit.id}", ChatMessageSlice.Markdown(partIndex, unit,
                    first = index == 0, last = index == units.lastIndex, group = group), if (index == 0) gap else 0f)
            } else add("part:${section.key}", ChatMessageSlice.Part(bodyIndex, document.markdown), gap)
        }
        if (message.translation != null && document != null) add("translation", ChatMessageSlice.Translation, appearance.layout.messageGapDp)
        add("footer", ChatMessageSlice.Footer, appearance.layout.messageGapDp)
    }
    return ChatRenderIndex(nodes, rows, hasCheckpoint, tailKeys)
}
