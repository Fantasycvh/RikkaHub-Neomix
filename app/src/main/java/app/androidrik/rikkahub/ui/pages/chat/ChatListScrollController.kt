package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import kotlin.uuid.Uuid

/** Logical requests survive async document preparation; callers never guess a LazyColumn index. */
class ChatListScrollController {
    internal sealed interface Request {
        val serial: Long
        data class Bottom(override val serial: Long, val waitForChange: Boolean, val waitForChangeFrom: Uuid?) : Request
        data class Message(override val serial: Long, val id: Uuid) : Request
    }
    private var sequence = 0L
    internal var request by mutableStateOf<Request?>(null)
        private set
    /** Small logical anchor only; never save parsed documents or message text in an Android Bundle. */
    internal var readAnchor: ChatReadAnchor? = null
    internal var readAtBottom: Boolean = true
    fun scrollToBottom() { request = Request.Bottom(++sequence, false, null) }
    fun scrollToBottomAfter(messageId: Uuid?) { request = Request.Bottom(++sequence, true, messageId) }
    fun scrollToMessage(id: Uuid) { request = Request.Message(++sequence, id) }
    internal fun consume(serial: Long) { if (request?.serial == serial) request = null }
    internal fun cancel() { request = null }
    companion object {
        val Saver = listSaver<ChatListScrollController, String>(
            save = { controller -> controller.readAnchor?.let {
                listOf(it.key, it.nodeId?.toString().orEmpty(), it.messageId?.toString().orEmpty(),
                    it.partIndex?.toString().orEmpty(), it.sourceOffset?.toString().orEmpty(), it.pixelOffset.toString(), controller.readAtBottom.toString())
            }.orEmpty() },
            restore = { values -> ChatListScrollController().apply {
                if (values.size >= 6) {
                    readAnchor = ChatReadAnchor(values[0], values[1].takeIf(String::isNotEmpty)?.let(Uuid::parse),
                        values[2].takeIf(String::isNotEmpty)?.let(Uuid::parse), values[3].toIntOrNull(), values[4].toIntOrNull(), values[5].toInt())
                    readAtBottom = values.getOrNull(6)?.toBooleanStrictOrNull() ?: false
                }
            } },
        )
    }
}
