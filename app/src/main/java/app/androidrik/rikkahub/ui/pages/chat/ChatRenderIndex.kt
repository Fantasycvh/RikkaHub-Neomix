package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.conflate
import me.rerere.bettertext.markdown.MarkdownRenderDocument
import me.rerere.bettertext.markdown.MarkdownRenderRange
import me.rerere.bettertext.markdown.MarkdownRenderUnit
import me.rerere.bettertext.markdown.MarkdownStreamParser
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

internal const val LONG_MESSAGE_MIN_CHARS = 8_000
private const val MIN_LAZY_UNITS = 4

@Suppress("UNUSED_PARAMETER") // Layout/loading constrain slicing, not background preparation.
internal fun canPrepareStaticMessage(message: UIMessage, appearance: ChatAppearance, enabled: Boolean, loading: Boolean): Boolean {
    if (!enabled || message.isContextCheckpoint) return false
    if (message.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT)) return false
    if (message.annotations.any { it is UIMessageAnnotation.SubagentNotice || it is UIMessageAnnotation.ExternalNotice }) return false
    return message.parts.any { it is UIMessagePart.Text && it.text.length >= LONG_MESSAGE_MIN_CHARS }
}

internal fun canSliceMessage(message: UIMessage, appearance: ChatAppearance): Boolean {
    val bubble = if (message.role == MessageRole.USER) appearance.userBubble else appearance.assistantBubble
    return appearance.layout.rendererId == "builtin.standard" &&
        (!bubble.visible || bubble.opacity <= 0f || bubble.material == BubbleMaterial.SOLID)
}

internal data class StaticMessageInput(val nodeId: Uuid, val message: UIMessage, val assistant: Assistant?, val folding: Boolean, val notice: String,
    val streaming: Boolean = false, val retainHostWhilePreparing: Boolean = streaming)
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
internal suspend fun prepareStaticMessage(input: StaticMessageInput, epoch: Long,
    parsers: MutableMap<Int, MarkdownStreamParser> = mutableMapOf()): StaticMessageDocument {
    val markdown = linkedMapOf<Int, MarkdownRenderDocument>()
    input.message.parts.forEachIndexed { index, part ->
        coroutineContext.ensureActive()
        if (part is UIMessagePart.Text && part.text.length >= LONG_MESSAGE_MIN_CHARS) {
            val source = messageMarkdownText(part.text, input.notice, input.assistant, input.message.role)
            markdown[index] = parsers.getOrPut(index) { MarkdownStreamParser() }
                .parseRenderDocument(normalizeMarkdownForDisplay(source), streaming = input.streaming)
        }
    }
    return StaticMessageDocument(input, epoch,
        messageDisplayDocument(input.message.id, input.message.parts, input.message.role, input.folding), markdown)
}

/** Safe to keep the preceding display while an append is parsed. Structural edits never borrow it. */
internal fun StaticMessageInput.canRetain(previous: StaticMessageInput): Boolean =
    nodeId == previous.nodeId && message.id == previous.message.id && message.role == previous.message.role &&
        assistant == previous.assistant && folding == previous.folding && notice == previous.notice &&
        message.parts.size == previous.message.parts.size && message.parts.zip(previous.message.parts).all { (next, old) ->
            if (next is UIMessagePart.Text && old is UIMessagePart.Text) next.text.startsWith(old.text) else next == old
        }

/** Owned exclusively by one serial background collector; parsers are never shared across jobs. */
internal class MessageDocumentSession(private val epoch: Long) {
    private val parsers = mutableMapOf<Int, MarkdownStreamParser>()
    private var previous: StaticMessageDocument? = null
    suspend fun prepare(input: StaticMessageInput): StaticMessageDocument {
        previous?.takeIf { it.input == input }?.let { return it }
        return prepareStaticMessage(input, epoch, parsers).also { previous = it }
    }
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
    val streamed = remember(conversationId) { mutableSetOf<Uuid>() }
    if (loading) nodes.lastOrNull()?.currentMessage?.takeIf { it.role == MessageRole.ASSISTANT }?.let { streamed += it.id }
    val inputs = remember(nodes, assistant, appearance, enabled, loading, folding, notice) {
        nodes.mapIndexedNotNull { index, node ->
            node.currentMessage.takeIf { canPrepareStaticMessage(it, appearance, enabled, loading && index == nodes.lastIndex) }
                ?.let { StaticMessageInput(node.id, it, assistant, folding, notice,
                    streaming = loading && index == nodes.lastIndex && it.role == MessageRole.ASSISTANT,
                    retainHostWhilePreparing = it.id in streamed) }
        }
    }
    val sequence = remember(conversationId) { AtomicLong() }
    val latestInputs by rememberUpdatedState(inputs)
    var result by remember(conversationId) { mutableStateOf(emptyMap<Uuid, StaticMessageDocument>() to emptySet<Uuid>()) }
    LaunchedEffect(conversationId) {
        val sessions = mutableMapOf<Uuid, Pair<StaticMessageInput, MessageDocumentSession>>()
        snapshotFlow { latestInputs }.conflate().collect { snapshot ->
        result = withContext(Dispatchers.Default) {
            sessions.keys.retainAll(snapshot.map { it.nodeId }.toSet())
            val prepared = linkedMapOf<Uuid, StaticMessageDocument>()
            val failed = mutableSetOf<Uuid>()
            for (input in snapshot) {
                ensureActive()
                val session = sessions[input.nodeId]?.takeIf { input.canRetain(it.first) }?.second
                    ?: MessageDocumentSession(sequence.incrementAndGet())
                sessions[input.nodeId] = input to session
                try { prepared[input.nodeId] = session.prepare(input) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed += input.nodeId; sessions.remove(input.nodeId) }
            }
            prepared to failed
        }
        }
    }
    val valid = remember(inputs, result) {
        inputs.mapNotNull { input -> result.first[input.nodeId]?.takeIf { input.canRetain(it.input) }
            ?.let { input.nodeId to it } }.toMap()
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
    deferredNodes: Set<Uuid> = emptySet(),
): ChatRenderIndex {
    val rows = mutableListOf<ChatRenderRow>()
    val pending = documents.pending
    nodes.forEachIndexed { nodeIndex, node ->
        val message = node.currentMessage
        val document = documents.ready[node.id]
        val lazy = node.id !in deferredNodes && canSliceMessage(message, appearance) &&
            document?.display?.body?.any { it.textDocumentId?.partIndex?.let(document::lazyUnits) != null } == true
        val boundaryGap = if (nodeIndex > 0 || hasCheckpoint) appearance.layout.listSpacingDp else 0f
        fun add(suffix: String, slice: ChatMessageSlice?, gap: Float, first: Boolean = false) {
            rows += ChatRenderRow(if (first) node.id.toString() else "${node.id}:${message.id}:$suffix", nodeIndex, node.id, message.id, slice, gap, first)
        }
        if (!lazy && (node.id !in pending || node.id in deferredNodes ||
                documents.inputs.any { it.nodeId == node.id && it.retainHostWhilePreparing } || !canSliceMessage(message, appearance))) {
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
