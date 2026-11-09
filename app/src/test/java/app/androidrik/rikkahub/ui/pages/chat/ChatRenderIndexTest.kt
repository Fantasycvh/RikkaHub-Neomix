package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.ChatLayout
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.ui.components.message.ChatMessageSlice
import app.androidrik.rikkahub.ui.components.message.plainTextChunks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatRenderIndexTest {
    @Test fun `stream session retains prefix identity and finalizes syntax without changing epoch`() = runBlocking {
        val message = UIMessage.assistant(("early paragraph\n\n".repeat(600)))
        val input = StaticMessageInput(Uuid.random(), message, null, false, "truncated", streaming = true)
        val session = MessageDocumentSession(72)
        val first = session.prepare(input)
        val nextInput = input.copy(message = message.copy(parts = listOf(UIMessagePart.Text(message.toText() + "\n\n```kotlin\nval a = 1"))))
        val next = session.prepare(nextInput)
        assertSame(first.markdown.getValue(0).units.first(), next.markdown.getValue(0).units.first())
        assertEquals(first.epoch, next.epoch)
        assertTrue(nextInput.canRetain(input))
        val finished = session.prepare(nextInput.copy(streaming = false))
        assertEquals(next.epoch, finished.epoch)
        assertEquals(nextInput.message.toText(), finished.markdown.getValue(0).document.source)
        assertFalse(nextInput.copy(message = UIMessage.assistant("different branch")).canRetain(input))
        assertFalse(nextInput.copy(message = message.copy(parts = listOf(UIMessagePart.Text("edited")))).canRetain(input))
    }

    private val longText = (0..149).joinToString("\n\n") { "段落 $it：" + "正文与内容。".repeat(18) }
    private val appearance = ChatAppearance()
    private fun prepared(node: MessageNode, epoch: Long = 1) = runBlocking {
        prepareStaticMessage(StaticMessageInput(node.id, node.currentMessage, null, false, "TRUNCATED"), epoch)
    }
    private fun index(nodes: List<MessageNode>, document: StaticMessageDocument?, checkpoint: Boolean = false): ChatRenderIndex {
        val ready = document?.let { mapOf(it.input.nodeId to it) }.orEmpty()
        return buildChatRenderIndex(nodes, StaticDocuments(document?.let { listOf(it.input) }.orEmpty(), ready, emptySet()),
            appearance, checkpoint, listOf("ConversationGoal", "ScrollBottomKey"))
    }

    @Test fun `logical message and alternate branch targets account for every unit and checkpoint`() {
        val first = UIMessage.user("question").toMessageNode()
        val long = UIMessage.assistant(longText).toMessageNode()
        val alternative = UIMessage.assistant("other branch")
        val last = MessageNode(messages = listOf(UIMessage.user("last"), alternative))
        val result = index(listOf(first, long, last), prepared(long), checkpoint = true)
        assertEquals(1, result.target(first.currentMessage.id))
        assertEquals(2, result.target(long.id))
        assertTrue(result.target(last.id) > 10)
        assertEquals(result.target(last.id), result.target(alternative.id))
        assertEquals(-1, result.target(Uuid.random()))
        assertEquals(result.rows.size + 3, result.totalCount)
        assertEquals(result.totalCount - 1, result.keyToIndex["ScrollBottomKey"])
        assertEquals(result.rows.size, result.rows.map { it.key }.distinct().size)
    }

    @Test fun `jump arrows navigate messages instead of individual paragraphs`() {
        val first = UIMessage.user("question").toMessageNode()
        val long = UIMessage.assistant(longText).toMessageNode()
        val last = UIMessage.user("last").toMessageNode()
        val result = index(listOf(first, long, last), prepared(long))
        val middle = result.nodeRanges.getValue(long.id).first + 5
        assertEquals(result.target(first.id), result.previousMessage(middle))
        assertEquals(result.target(last.id), result.nextMessage(middle))
        assertEquals(result.lastIndex, result.nextMessage(result.target(last.id)))
        assertEquals(result.target(last.id), result.previousMessage(result.lastIndex))
    }

    @Test fun `surviving unit keys and offsets remain anchored when earlier messages are inserted`() {
        val long = UIMessage.assistant(longText).toMessageNode()
        val document = prepared(long)
        val before = index(listOf(long), document)
        val row = before.rows[5]
        val anchor = before.anchor(row.key, 37)!!
        val after = index(listOf(UIMessage.user("inserted").toMessageNode(), long), document)
        assertEquals(before.keyToIndex.getValue(row.key) + 1 to 37, after.restore(anchor))
        assertEquals(row.key, after.rows[after.restore(anchor)!!.first].key)
    }

    @Test fun `reparsed document epochs restore by display offset and branch switches restore to node header`() {
        val long = UIMessage.assistant(longText).toMessageNode()
        val before = index(listOf(long), prepared(long, 1))
        val row = before.rows[5]
        val anchor = before.anchor(row.key, 12)!!
        val after = index(listOf(long), prepared(long, 2))
        val restored = after.restore(anchor)!!
        assertEquals(12, restored.second)
        assertEquals((row.slice as ChatMessageSlice.Markdown).unit.sourceRange,
            (after.rows[restored.first].slice as ChatMessageSlice.Markdown).unit.sourceRange)
        val branch = long.copy(messages = listOf(UIMessage.assistant("replacement")))
        assertEquals(0 to 0, index(listOf(branch), null).restore(anchor))
    }

    @Test fun `placeholder does not duplicate parsing in a giant atomic paragraph fallback`() {
        val long = UIMessage.assistant("文".repeat(16_000)).toMessageNode()
        val document = prepared(long)
        val pending = buildChatRenderIndex(listOf(long), StaticDocuments(listOf(document.input), emptyMap(), emptySet()),
            appearance, false, listOf("ScrollBottomKey"))
        assertEquals(3, pending.rows.size)
        assertTrue(pending.rows[1].slice is ChatMessageSlice.Preparing)
        val ready = index(listOf(long), document)
        assertEquals(1, ready.rows.size)
        assertTrue(ready.rows.single().slice is ChatMessageSlice.Prepared)
        assertSame(document.markdown[0], (ready.rows.single().slice as ChatMessageSlice.Prepared).documents[0])
    }

    @Test fun `all layouts prepare while slicing requires standard solid geometry`() {
        val message = UIMessage.assistant(longText)
        assertTrue(canPrepareStaticMessage(message, appearance, true, false))
        assertTrue(canPrepareStaticMessage(message, appearance.copy(assistantBubble = ChatBubble()), true, false))
        assertTrue(canPrepareStaticMessage(message, appearance, true, true))
        assertFalse(canPrepareStaticMessage(message, appearance, false, false))
        assertTrue(canPrepareStaticMessage(message, appearance.copy(layout = ChatLayout(rendererId = "builtin.card")), true, false))
        assertFalse(canSliceMessage(message, appearance.copy(layout = ChatLayout(rendererId = "builtin.card"))))
        assertFalse(canSliceMessage(message, appearance.copy(assistantBubble = ChatBubble(material = BubbleMaterial.GRADIENT))))
    }

    @Test fun `giant atoms preview literal source without changing parsed nodes or original data`() {
        for (source in listOf("文".repeat(100_000), "```kotlin\n" + "val a = 1\n".repeat(5000) + "```",
            "- item\n".repeat(5000), "|a|b|\n|---|---|\n" + "|one|two|\n".repeat(5000))) {
            val node = UIMessage.assistant(source).toMessageNode()
            val document = prepared(node)
            val result = index(listOf(node), document)
            val slices = result.rows.mapNotNull { it.slice as? ChatMessageSlice.Markdown }
            if (source == "文".repeat(100_000)) {
                assertTrue(result.rows.single().slice is ChatMessageSlice.Prepared)
                assertEquals(source, document.markdown.getValue(0).document.source)
                continue
            }
            assertTrue(slices.any { it.preview != null })
            assertTrue(slices.filter { it.preview != null }.all { it.preview!!.length <= 3000 })
            assertEquals(source, document.markdown.getValue(0).document.source)
            assertEquals(document.markdown.getValue(0).document.blocks, slices.flatMap { it.unit.blocks })
            assertEquals(source, node.currentMessage.toText())
        }
    }

    @Test fun `reading whole message can defer promotion without dropping prepared content`() {
        val node = UIMessage.assistant(longText).toMessageNode()
        val document = prepared(node)
        val result = buildChatRenderIndex(listOf(node), StaticDocuments(listOf(document.input), mapOf(node.id to document), emptySet()),
            appearance, false, listOf("ScrollBottomKey"), deferredNodes = setOf(node.id))
        assertEquals(1, result.rows.size)
        assertTrue(result.rows.single().slice is ChatMessageSlice.Prepared)
    }

    @Test fun `multiple text parts and original attachments stay in order without losing source`() {
        val message = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text(longText),
            UIMessagePart.Image("file:///image.png"), UIMessagePart.Text(longText)))
        val node = message.toMessageNode()
        val document = prepared(node)
        val result = index(listOf(node), document)
        val markdown = result.rows.mapNotNull { it.slice as? ChatMessageSlice.Markdown }
        assertEquals(setOf(0, 2), markdown.map { it.partIndex }.toSet())
        val imageRow = result.rows.indexOfFirst { it.slice is ChatMessageSlice.Part }
        assertTrue(imageRow > 10)
        assertTrue(result.rows.drop(imageRow + 1).any { (it.slice as? ChatMessageSlice.Markdown)?.partIndex == 2 })
        assertSame(message.parts, document.display.sourceParts)
        for (part in listOf(0, 2)) {
            val parsed = document.markdown.getValue(part)
            assertEquals(longText, parsed.document.source)
            assertEquals(parsed.document.blocks, markdown.filter { it.partIndex == part }.flatMap { it.unit.blocks })
        }
    }

    @Test fun `plain source chunks retain CRLF Markdown emoji and boundaries exactly`() {
        val text = ("line\r\n😀\n```raw```\n".repeat(5_000)) + "end"
        val chunks = plainTextChunks(text, 41)
        assertEquals(text, chunks.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(chunks.all { it.end - it.start <= 41 })
        assertTrue(chunks.drop(1).none { text[it.start].isLowSurrogate() })
        assertEquals(emptyList<Any>(), plainTextChunks(""))
    }

    @Test fun `small static syntax units share bounded rows without splitting or dropping nodes`() {
        val node = UIMessage.assistant(longText + "\n\n```kotlin\n" + "val a = 1\n".repeat(300) + "```\n\nlast").toMessageNode()
        val document = prepared(node)
        val original = document.markdown.getValue(0).units.filter { it.blocks.isNotEmpty() }
        val rows = document.lazyUnits(0)!!
        assertTrue(rows.size < original.size / 2)
        assertEquals(original.flatMap { it.blocks }, rows.flatMap { it.blocks })
        assertEquals(original.filter { it.sourceRange.end - it.sourceRange.start > 1024 },
            rows.filter { it.sourceRange.end - it.sourceRange.start > 1024 })
        for (row in rows) {
            val first = original.first { it.id == row.id }
            assertEquals(first.firstBlockIndex, row.firstBlockIndex)
            assertEquals(first.sourceRange.start, row.sourceRange.start)
            assertTrue(original.any { it.sourceRange.end == row.sourceRange.end })
        }
    }

    @Test fun `scroll requests replace stale commands and distinguish an empty conversation before send`() {
        val controller = ChatListScrollController()
        controller.scrollToBottomAfter(null)
        val waiting = controller.request as ChatListScrollController.Request.Bottom
        assertTrue(waiting.waitForChange)
        controller.scrollToMessage(Uuid.random())
        controller.consume(waiting.serial)
        assertTrue(controller.request is ChatListScrollController.Request.Message)
        controller.cancel()
        assertNull(controller.request)
        controller.scrollToBottom()
        assertFalse((controller.request as ChatListScrollController.Request.Bottom).waitForChange)
    }
}
